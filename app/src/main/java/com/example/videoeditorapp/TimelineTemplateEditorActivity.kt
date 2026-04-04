package com.example.videoeditorapp

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MergingMediaSource
import com.example.videoeditorapp.databinding.ActivityTimelineTemplateEditorBinding
import com.example.videoeditorapp.databinding.DialogExportOptionsBinding
import com.example.videoeditorapp.databinding.DialogExportProgressBinding
import com.example.videoeditorapp.databinding.DialogInputBinding
import com.example.videoeditorapp.databinding.ViewEditorPreviewBinding
import com.example.videoeditorapp.model.timeline.*
import com.example.videoeditorapp.service.ExportService
import com.example.videoeditorapp.service.ExportState
import com.example.videoeditorapp.ui.editor.pickers.*
import com.example.videoeditorapp.ui.timeline.TimelineTool
import com.example.videoeditorapp.ui.timeline.TimelineView
import com.example.videoeditorapp.utils.*
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import com.example.videoeditorapp.utils.setupSharedPreviewResize
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AdditionMode {
    APPEND,
    OVERLAY
}

class TimelineTemplateEditorActivity : AppCompatActivity() {
    private var minPreviewHeight = 0
    private var maxPreviewHeight = 0
    private lateinit var binding: ActivityTimelineTemplateEditorBinding
    private lateinit var previewBinding: ViewEditorPreviewBinding
    private lateinit var project: TimelineProject
    private var exoPlayer: ExoPlayer? = null
    private val historyManager = HistoryManager<TimelineProject>(maxHistorySize = 30)

    private var currentTimeMs = 0L
    private var isSeeking = false
    private var selectedClip: TimelineClip? = null
    private var currentAdditionMode = AdditionMode.APPEND

    // refreshEverything() removed as it was unused.

    private val DEFAULT_IMAGE_DURATION_MS = 5_000L
    private var projectName = ""

    // Resolution and Aspect Ratio state
    private var selectedResolution = "ORIGINAL"
    private var selectedAspectRatio = "ORIGINAL"

    private var activeTimelineMap: List<Pair<Long, Long>> = emptyList()
    private var wasPlayingBeforeScrub = false
    @RequiresApi(Build.VERSION_CODES.O)
    private val pickMediaLauncher =
            registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
                if (uris.isEmpty()) return@registerForActivityResult

                // Show loading indicator
                previewBinding.progressBar.visibility = View.VISIBLE

                saveHistory()

                lifecycleScope.launch {
                    uris.forEach { uri -> addMediaClip(uri) }

                    withContext(Dispatchers.Main) {
                        previewBinding.progressBar.visibility = View.GONE

                        updateUIDuration()
                        previewBinding.timelineView.setProject(project)
                        previewBinding.timelineView.seekTo(currentTimeMs)
                        previewBinding.timelineView.invalidate()
                        rebuildPlayerFromTimeline(currentTimeMs)
                    }
                }
            }

    private val hubLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val type = result.data?.getStringExtra("ASSET_TYPE")
                    val category = result.data?.getStringExtra("ASSET_CATEGORY")
                    val path =
                            result.data?.getStringExtra("ASSET_PATH")
                                    ?: return@registerForActivityResult
                    addAssetToTimeline(path, type, category)
                }
            }

    private var pendingWatermarkPicker: (() -> Unit)? = null
    private val pickWatermarkLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    val path = copyUriToTempFile(uri)
                    project.watermarkPath = path
                    pendingWatermarkPicker?.invoke()
                    pendingWatermarkPicker = null
                }
            }

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1️⃣ Inflate activity layout
        binding = ActivityTimelineTemplateEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2️⃣ Get preview binding from included layout
        previewBinding = binding.editorPreview

        // 3️⃣ Edge-to-edge setup (Must be AFTER previewBinding init)
        setupEdgeToEdge()

        val projectId = intent.getStringExtra("PROJECT_ID")
        projectName = intent.getStringExtra("PROJECT_NAME") ?: "My Movie"

        setupToolbar()

        if (savedInstanceState != null) {
            val savedProject =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        savedInstanceState.getParcelable("project", TimelineProject::class.java)
                    } else {
                        @Suppress("DEPRECATION") savedInstanceState.getParcelable("project")
                    }

            if (savedProject != null) {
                project = savedProject
            } else {
                val projectPath = intent.getStringExtra("PROJECT_PATH")
                restoreProjectFromIntent(projectPath, projectId, projectName)
            }
            currentTimeMs = savedInstanceState.getLong("currentTimeMs", 0L)
        } else {
            val projectPath = intent.getStringExtra("PROJECT_PATH")
            restoreProjectFromIntent(projectPath, projectId, projectName)
        }

        if (!::project.isInitialized) {
            setupProject()
        }
        project.templateId = "TIMELINE"
        project.name = projectName

        // 🍏 V4: Ensure timeline is ready before player
        setupTimeline()

        // Pass project to TimelineView immediately
        previewBinding.timelineView.setProject(project)

        // 🍏 V4: Handle Scrubbing Pauses
        previewBinding.timelineView.onInteractionStart = {
            if (exoPlayer?.isPlaying == true) {
                exoPlayer?.pause()
                wasPlayingBeforeScrub = true
            }
        }
        previewBinding.timelineView.onInteractionEnd = {
            if (wasPlayingBeforeScrub) {
                exoPlayer?.play()
                wasPlayingBeforeScrub = false
            }
        }

        // 🍏 V4: Immediate Link/Unlink on Long Press (Video Only)
        previewBinding.timelineView.onClipLongPressed = { clip ->
            if (clip.type == ClipType.VIDEO) {
                toggleLinkUnlink(clip)
            }
        }

        // Initialized dynamically now

        setupPlayer()
        attachPlayerToView()
        setupControls()

        setupSharedPreviewResize(
                previewBinding.previewResizeHandle,
                previewBinding.root,
                R.id.previewSplitGuideline,
                R.id.landscapeSplitGuideline
        )
        updateUIDuration()
        minPreviewHeight = ViewUtils.dpToPx(this, 260)
        maxPreviewHeight = ViewUtils.dpToPx(this, 620)

        // Sync view with project
        previewBinding.timelineView.seekTo(currentTimeMs)
        rebuildPlayerFromTimeline(currentTimeMs)
        //        updateExportButtonState()
        checkAndRequestNotificationPermission()
        setupHistoryControls()
        startAutoSave()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("project", project)
        outState.putLong("currentTimeMs", currentTimeMs)
    }

    //    private fun updateExportButtonState() {
    //        // binding.editorPreview.btnExport.isEnabled = hasClips
    //        // binding.editorPreview.btnExport.alpha = if (hasClips) 1.0f else 0.5f
    //    }

    // -------------------- PROJECT --------------------
    private fun setupHistoryControls() {
        binding.btnUndoContainer.setOnClickListener { performUndo() }
        binding.btnRedoContainer.setOnClickListener { performRedo() }
        updateHistoryButtons()
    }

    private fun saveHistory() {
        historyManager.saveState(project.deepCopy())
        updateHistoryButtons()
    }

    private fun performUndo() {
        val previousState = historyManager.undo(project.deepCopy())
        if (previousState != null) {
            project = previousState
            onProjectStateRestored()
        }
    }

    private fun performRedo() {
        val nextState = historyManager.redo(project.deepCopy())
        if (nextState != null) {
            project = nextState
            onProjectStateRestored()
        }
    }

    private fun onProjectStateRestored() {
        updateHistoryButtons()
        // Here we need to refresh the timeline UI and Player
        refreshTimelineFull()
        saveProject() // Auto-save to persistence
    }

    private fun updateHistoryButtons() {
        binding.btnUndo.apply {
            isEnabled = historyManager.canUndo()
            alpha = if (isEnabled) 1.0f else 0.5f
        }
        binding.btnRedo.apply {
            isEnabled = historyManager.canRedo()
            alpha = if (isEnabled) 1.0f else 0.5f
        }
    }

    private fun refreshTimelineFull() {
        previewBinding.timelineView.setProject(project)
        rebuildPlayerFromTimeline(currentTimeMs)
        updateUIDuration()
        previewBinding.timelineView.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupToolbar() {
        // 🍏 Fixed Navigation: Using your custom back container
        binding.btnBackContainer.setOnClickListener { finish() }

        // 🍏 Fixed Title: Updating the TextView directly
        binding.tvToolbarTitle.text = projectName.uppercase()

        // 🍏 Fixed Save: Target the specific CardView container for better touch area
        binding.btnSaveContainer.setOnClickListener {
            saveProject()
            Toast.makeText(this, "Project Saved", Toast.LENGTH_SHORT).show()
        }

        // 🍏 Fixed Sidebar/Info: The "Edit" button logic
        binding.btnInfoContainer.setOnClickListener { showClipInfoDialog() }

        // 🍏 Project Settings & Export Hub
        binding.btnSettingsContainer?.setOnClickListener { showProjectSettingsDialog() }

        // 🍏 Optional: Setup Undo/Redo logic
        // binding.btnUndoContainer.setOnClickListener { }
        // binding.btnRedoContainer.setOnClickListener { }
    }
    private fun saveProject() {
        if (::project.isInitialized) {
            com.example.videoeditorapp.utils.ProjectManager.saveProject(this, project)
        }
    }

    private fun restoreProjectFromIntent(
            projectPath: String?,
            projectId: String?,
            projectName: String?
    ) {
        var loadedProject: TimelineProject? = null

        // 1. Try loading by Path (Resume last)
        if (!projectPath.isNullOrEmpty()) {
            try {
                val file = File(projectPath)
                if (file.exists()) {
                    val json = file.readText()
                    // Create Gson manually to avoid cycle or use ProjectManager's if public (it's
                    // private there)
                    loadedProject =
                            com.google.gson.GsonBuilder()
                                    .create()
                                    .fromJson(json, TimelineProject::class.java)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Try loading by ID (My Projects)
        if (loadedProject == null && !projectId.isNullOrEmpty()) {
            loadedProject =
                    com.example.videoeditorapp.utils.ProjectManager.loadProject(this, projectId)
        }

        // 3. Fallback / New Project
        if (loadedProject != null) {
            project = loadedProject
        } else {
            setupProject()
            project.name = projectName ?: "My Movie"
            // If we have a project ID but file was missing, keep the ID? No, new ID is safer for
            // setupProject()
        }

        // Ensure binding is available before setting project
        if (::binding.isInitialized) {
            previewBinding.timelineView.setProject(project)
        }
    }

    // Deprecated single-arg version, redirecting
    private fun restoreProject(projectName: String) {
        restoreProjectFromIntent(null, null, projectName)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // 1️⃣ Re-inflate layout to switch between portrait/landscape XML
        val oldPlayer = previewBinding.playerView.player
        previewBinding.playerView.player = null // detach temporarily

        binding = ActivityTimelineTemplateEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        previewBinding = binding.editorPreview

        // 2️⃣ Restore Player
        previewBinding.playerView.player = oldPlayer

        // 3️⃣ Re-setup Edge-to-Edge since view hierarchy changed
        setupEdgeToEdge()

        // 4️⃣ Re-bind Logic
        setupToolbar()
        setupTimeline()

        // Restore listeners
        setupControls()
        setupPreviewResize()

        // Sync view state
        previewBinding.timelineView.setProject(project)
        previewBinding.timelineView.seekTo(currentTimeMs)
        updateUIDuration()
        updateUIDuration()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        captureThumbnail() // Capture before saving so the path is included
        saveProject()
    }

    private fun captureThumbnail() {
        try {
            // Extract frame from the FIRST video clip or image clip
            val firstClip =
                    project.tracks.flatMap { it.clips }.find {
                        it.type == ClipType.VIDEO || it.type == ClipType.IMAGE
                    }
            if (firstClip != null) {
                // Run on background thread but don't block main
                java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                    try {
                        val mmr = android.media.MediaMetadataRetriever()
                        mmr.setDataSource(firstClip.filePath)
                        val bitmap =
                                if (firstClip.type == ClipType.VIDEO) {
                                    mmr.getFrameAtTime(firstClip.sourceStartTimeMs * 1000)
                                } else {
                                    android.graphics.BitmapFactory.decodeFile(firstClip.filePath)
                                }

                        if (bitmap != null) {
                            val thumbFile = File(filesDir, "project_thumb_${project.id}.jpg")
                            java.io.FileOutputStream(thumbFile).use { out ->
                                bitmap.compress(
                                        android.graphics.Bitmap.CompressFormat.JPEG,
                                        80, // Lower quality for faster write
                                        out
                                )
                            }
                            project.thumbnailPath = thumbFile.absolutePath
                            // Explicitly save project again since we're in a thread
                            saveProject()
                        }
                        mmr.release()
                    } catch (e: Exception) {
                        Log.e("TimelineEditor", "Thumbnail capture failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        exoPlayer?.release()
        super.onDestroy()
    }

    private fun setupEdgeToEdge() {
        // Apply status bar padding to toolbar
        setupEditorEdgeToEdge(binding.toolbarTimeline, null)

        // Apply navigation bar padding to bottom nav wrapper
        // Use previewBinding.bottomNavWrapper to ensure the navigation bar doesn't overlap tools
        ViewCompat.setOnApplyWindowInsetsListener(previewBinding.bottomNavWrapper) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom)
            insets
        }
    }

    // -------------------- PLAYER --------------------

    private fun setupPlayer() {
        exoPlayer?.release()

        exoPlayer =
                ExoPlayer.Builder(this).build().apply {
                    previewBinding.playerView.player = this

                    addListener(
                            object : Player.Listener {

                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    // 🔹 Always keep UI in sync (safety net)
                                    runOnUiThread {
                                        val iconRes =
                                                if (isPlaying) R.drawable.ic_pause
                                                else R.drawable.ic_play

                                        // 🍏 FIX: Reference the ImageView inside the included
                                        // layout and use setImageResource
                                        binding.editorPreview.btnPlayPause.setImageResource(iconRes)

                                        // Contrast is handled by @color/selector_play_button_tint
                                    }

                                    // 🔹 Start playhead loop ONLY if needed
                                    if (isPlaying) {
                                        startPlayheadUpdate()
                                    }
                                }

                                @OptIn(UnstableApi::class)
                                override fun onMediaItemTransition(
                                        mediaItem: MediaItem?,
                                        reason: Int
                                ) {
                                    val clipId = mediaItem?.mediaId ?: return
                                    if (clipId.startsWith("GAP_")) {
                                        setVideoEffects(emptyList())
                                        return
                                    }

                                    val clip =
                                            project.tracks.flatMap { it.clips }.find {
                                                it.id == clipId
                                            }
                                                    ?: return
                                    val overlayIds =
                                            clip.metadata["OVERLAYS"]?.split(",") ?: emptyList()
                                    val overlays =
                                            project.tracks.flatMap { it.clips }.filter {
                                                it.id in overlayIds
                                            }

                                    val effects =
                                            Media3EffectEngine.getCombinedEffects(
                                                    project,
                                                    clip,
                                                    overlays
                                            )
                                    setVideoEffects(effects)
                                }

                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state == Player.STATE_ENDED) {
                                        runOnUiThread {
                                            // 🍏 Target the ImageView inside the CardView
                                            binding.editorPreview.btnPlayPause.setImageResource(
                                                    R.drawable.ic_play
                                            )

                                            // Contrast is handled by
                                            // @color/selector_play_button_tint
                                        }
                                    }
                                }
                            }
                    )
                }
    }

    @UnstableApi
    private fun setVideoEffects(effects: List<androidx.media3.common.Effect>) {
        exoPlayer?.setVideoEffects(effects)
    }
    private fun attachPlayerToView() {
        previewBinding.playerView.player = exoPlayer
    }

    private fun clearControlListeners() {
        previewBinding.btnPlayPause.setOnClickListener(null)
        previewBinding.btnPrevClip.setOnClickListener(null)
        previewBinding.btnNextClip.setOnClickListener(null)
        previewBinding.btnStop.setOnClickListener(null)

        previewBinding.btnSelect.setOnClickListener(null)
        previewBinding.btnTrim.setOnClickListener(null)
        previewBinding.btnSpeed.setOnClickListener(null)
        previewBinding.btnVolume.setOnClickListener(null)
        previewBinding.btnCopyClip.setOnClickListener(null)
        previewBinding.btnCropClip.setOnClickListener(null)
        previewBinding.btnMoveToFront.setOnClickListener(null)
        previewBinding.btnMoveToBack.setOnClickListener(null)
        previewBinding.btnFrameBack.setOnClickListener(null)
        previewBinding.btnFrameFwd.setOnClickListener(null)
    }

    // -------------------- CONTROLS --------------------

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupControls() {

        // ... Existing ...

        // 🍏 V4: Frame Stepping
        previewBinding.btnFrameBack?.setOnClickListener { stepFrame(-1) }
        previewBinding.btnFrameFwd?.setOnClickListener { stepFrame(1) }

        // 🍏 V4: Long Press for Advanced Actions
        previewBinding.timelineView.onClipLongPressed = { clip ->
            if (clip.type == ClipType.VIDEO) {
                toggleLinkUnlink(clip)
            }
        }

        // TAB NAVIGATION
        previewBinding.tabEdit.setOnClickListener {
            val clip = selectedClip
            if (clip != null) {
                when (clip.type) {
                    ClipType.TEXT -> showTextPropertiesDialog(clip)
                    ClipType.VIDEO, ClipType.AUDIO -> showAdjustPanel()
                    ClipType.IMAGE, ClipType.STICKER, ClipType.EMOJI, ClipType.GIF -> {
                        showOverlayPropertiesDialog(clip)
                    }
                    else -> {}
                }
            } else {
                Toast.makeText(this, "Select a clip to edit properties", Toast.LENGTH_SHORT).show()
            }
        }
        //        previewBinding.tabAudio?.setOnClickListener {
        //            currentAdditionMode = AdditionMode.OVERLAY
        //            val clip = selectedClip
        //            if (clip?.type == ClipType.AUDIO) {
        //                showAudioMixerDialog(clip)
        //            } else {
        //                pickMediaLauncher.launch("audio/*")
        //            }
        //        }
        //        previewBinding.tabText.setOnClickListener {
        //            currentAdditionMode = AdditionMode.OVERLAY
        //            showTextPropertiesDialog(null)
        //        }
        //        previewBinding.tabOverlay?.setOnClickListener {
        //            currentAdditionMode = AdditionMode.OVERLAY
        //            pickMediaLauncher.launch("*/*") // Support both Video PIP and Images
        //        }
        previewBinding.tabEffects.setOnClickListener { showEffectsPicker() }
        previewBinding.tabFilters.setOnClickListener { showFiltersPicker() }
        previewBinding.tabHub.setOnClickListener {
            hubLauncher.launch(Intent(this, AssetStoreActivity::class.java))
        }
        previewBinding.tabReverse.setOnClickListener { selectedClip?.let { reverseClip(it) } }
        previewBinding.tabOpacity.setOnClickListener { showOpacityDialog() }
        previewBinding.tabFade.setOnClickListener { showFadeDialog() }

        previewBinding.btnPlayPause.setOnClickListener {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
        }

        previewBinding.btnStop.setOnClickListener {
            exoPlayer?.pause()
            exoPlayer?.seekTo(0)
            previewBinding.timelineView.seekTo(0)
            currentTimeMs = 0L
            previewBinding.tvTimeCode.text = ViewUtils.formatTime(0)
        }

        previewBinding.btnPrevClip.setOnClickListener {
            val proj = project
            if (proj.tracks.isNotEmpty()) {
                val allClips = proj.tracks.flatMap { it.clips }.sortedBy { it.startTimeMs }

                // Find nearest previous clip start OR 0 if none
                val current = allClips.findLast { it.startTimeMs < currentTimeMs - 50 }
                val targetTime = current?.startTimeMs ?: 0L

                currentTimeMs = targetTime
                rebuildPlayerFromTimeline(currentTimeMs)
                previewBinding.timelineView.seekTo(currentTimeMs)
            }
        }

        previewBinding.btnNextClip.setOnClickListener {
            val proj = project
            if (proj.tracks.isNotEmpty()) {
                val allClips = proj.tracks.flatMap { it.clips }.sortedBy { it.startTimeMs }
                val next = allClips.find { it.startTimeMs > currentTimeMs + 50 }

                // If no next clip, jump to end of timeline
                val targetTime = next?.startTimeMs ?: proj.getDurationMs()

                currentTimeMs = targetTime
                rebuildPlayerFromTimeline(currentTimeMs)
                previewBinding.timelineView.seekTo(currentTimeMs)
            }
        }

        // FAB Speed Dial
        binding.fabAdd.setOnClickListener { toggleFabMenu() }

        binding.fabAddAppend?.setOnClickListener {
            currentAdditionMode = AdditionMode.APPEND
            switchFabTier(2)
        }

        binding.fabAddOverlay?.setOnClickListener {
            currentAdditionMode = AdditionMode.OVERLAY
            switchFabTier(2)
        }

        binding.fabActionExport?.setOnClickListener {
            toggleFabMenu()
            showProjectSettingsDialog()
        }

        // TIER 2 Listeners
        binding.btnFabBack?.setOnClickListener { switchFabTier(1) }
        binding.btnTypeMedia?.setOnClickListener {
            toggleFabMenu()
            com.example.videoeditorapp.utils.PermissionHelper.checkMediaPermissions(this) {
                // DIRECT PICK: Video and Image for main tracks/overlays
                pickMediaLauncher.launch("video/*,image/*")
            }
        }
        binding.btnTypeAudio?.setOnClickListener {
            toggleFabMenu()
            com.example.videoeditorapp.utils.PermissionHelper.checkMediaPermissions(this) {
                // DIRECT PICK: Audio for music/sfx
                pickMediaLauncher.launch("audio/*")
            }
        }
        binding.btnTypeText?.setOnClickListener {
            toggleFabMenu()
            showTextPropertiesDialog(null)
        }
        binding.btnTypeSticker?.setOnClickListener {
            toggleFabMenu()
            showStickerPicker()
        }
        binding.btnTypeEmoji?.setOnClickListener {
            toggleFabMenu()
            showEmojiPicker()
        }
        binding.btnTypeGif?.setOnClickListener {
            toggleFabMenu()
            showGifPicker()
        }
        binding.btnTypeImage?.setOnClickListener {
            toggleFabMenu()
            com.example.videoeditorapp.utils.PermissionHelper.checkMediaPermissions(this) {
                pickMediaLauncher.launch("image/*")
            }
        }

        // --- TOOLS ---
        previewBinding.btnSelect.setOnClickListener {
            previewBinding.timelineView.setTool(TimelineTool.SELECT)
            Toast.makeText(this, "Select / Move Mode", Toast.LENGTH_SHORT).show()
            updateToolUI()
        }
        previewBinding.btnSplit.setOnClickListener {
            if (selectedClip == null) {
                Toast.makeText(this, "Select a clip to split first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            splitClipAtPlayhead(selectedClip!!, splitAllLinked = true)
        }
        previewBinding.btnTrim.setOnClickListener {
            previewBinding.timelineView.setTool(TimelineTool.TRIM)
            Toast.makeText(this, "Trim Mode: Drag clip edges to resize", Toast.LENGTH_SHORT).show()
            updateToolUI()
        }
        previewBinding.btnCopyClip?.setOnClickListener {
            val clip = selectedClip
            if (clip == null) {
                Toast.makeText(this, "Select a clip to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.example.videoeditorapp.model.timeline.ClipClipboard.copy(clip)
            Toast.makeText(this, "Clip copied to clipboard", Toast.LENGTH_SHORT).show()
            updateToolUI()
        }
        previewBinding.btnPasteClip?.setOnClickListener {
            val clipToPaste = com.example.videoeditorapp.model.timeline.ClipClipboard.get()
            if (clipToPaste == null) {
                Toast.makeText(this, "Nothing to paste", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveHistory()
            (previewBinding.timelineView as TimelineView).pasteClipAtCurrentTime(clipToPaste)
            updateToolUI()
        }

        previewBinding.btnCropClip?.setOnClickListener { showCropDialog() }

        previewBinding.btnMoveToFront?.setOnClickListener {
            saveHistory()
            moveSelectedClipToFront()
            Toast.makeText(this, "Brought to Front (Layering)", Toast.LENGTH_SHORT).show()
        }
        previewBinding.btnMoveToBack?.setOnClickListener {
            saveHistory()
            moveSelectedClipToBack()
            Toast.makeText(this, "Sent to Back (Layering)", Toast.LENGTH_SHORT).show()
        }
        previewBinding.btnDeleteClip.setOnClickListener {
            saveHistory()
            (previewBinding.timelineView as TimelineView).deleteSelectedClip()
            clearSelection()
        }
        previewBinding.btnSpeed.setOnClickListener {
            previewBinding.timelineView.setTool(TimelineTool.SPEED)
            showSpeedDialog()
            updateToolUI()
        }
        previewBinding.btnVolume.setOnClickListener {
            previewBinding.timelineView.setTool(TimelineTool.VOLUME)
            showVolumeDialog()
            updateToolUI()
        }

        // Listen for timeline tool changes (internal)
        (previewBinding.timelineView as TimelineView).onToolChanged = { updateToolUI() }

        // Update initially
        updateToolUI()

        clearSelection()
    }

    private fun showEmojiPicker() {
        EmojiPickerBottomSheet { emoji ->
                    addMediaToTimeline(
                            path = "emoji://$emoji",
                            type = ClipType.EMOJI,
                            duration = 3000L
                    )
                }
                .show(supportFragmentManager, "emoji_picker")
    }

    private fun showStickerPicker() {
        StickerPickerBottomSheet { stickerPath ->
                    addMediaToTimeline(
                            path = stickerPath,
                            type = ClipType.STICKER,
                            duration = 3000L
                    )
                }
                .show(supportFragmentManager, "sticker_picker")
    }

    private fun showGifPicker() {
        GifPickerBottomSheet { gifUrl ->
                    addMediaToTimeline(path = gifUrl, type = ClipType.GIF, duration = 5000L)
                }
                .show(supportFragmentManager, "gif_picker")
    }

    private var isFabMenuOpen = false
    private fun toggleFabMenu() {
        isFabMenuOpen = !isFabMenuOpen
        val container = binding.fabActionPanel
        val fabAdd = binding.fabAdd

        if (isFabMenuOpen) {
            // Reset to Tier 1 when opening
            binding.fabTier1?.visibility = View.VISIBLE
            binding.fabTier1?.alpha = 1f
            binding.fabTier1?.translationX = 0f
            binding.fabTier2?.visibility = View.GONE

            container?.visibility = View.VISIBLE
            container?.alpha = 0f
            container?.translationY = 50f
            container
                    ?.animate()
                    ?.alpha(1f)
                    ?.translationY(0f)
                    ?.setDuration(300)
                    ?.setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                    ?.start()

            fabAdd.animate()?.rotation(45f)?.setDuration(250)?.start()

            // 🍎 Change FAB to Red when open
            fabAdd.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF4757"))
        } else {
            container
                    ?.animate()
                    ?.alpha(0f)
                    ?.translationY(50f)
                    ?.setDuration(200)
                    ?.withEndAction { container.visibility = View.GONE }
                    ?.start() // Added ?. here
            fabAdd.animate()?.rotation(0f)?.setDuration(250)?.start()

            // 🍎 Restore FAB to Cyan when closed
            fabAdd.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00D2D3"))
        }
    }

    private fun switchFabTier(tier: Int) {
        val t1 = binding.fabTier1 ?: return
        val t2 = binding.fabTier2 ?: return

        t1.clearAnimation()
        t2.clearAnimation()

        if (tier == 2) {
            // Animating 1 -> 2 (Slide t1 Left, t2 in from Right)
            t1.animate()
                    .alpha(0f)
                    .translationX(-50f)
                    .setDuration(150)
                    .withEndAction {
                        t1.visibility = View.GONE
                        t2.visibility = View.VISIBLE
                        t2.alpha = 0f
                        t2.translationX = 50f
                        t2.animate().alpha(1f).translationX(0f).setDuration(150).start()
                    }
                    .start()
        } else {
            // Animating 2 -> 1 (Slide t2 Right, t1 in from Left)
            t2.animate()
                    .alpha(0f)
                    .translationX(50f)
                    .setDuration(150)
                    .withEndAction {
                        t2.visibility = View.GONE
                        t1.visibility = View.VISIBLE
                        t1.alpha = 0f
                        t1.translationX = -50f
                        t1.animate().alpha(1f).translationX(0f).setDuration(150).start()
                    }
                    .start()
        }
    }

    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private val autoSaveRunnable =
            object : Runnable {
                override fun run() {
                    val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                    val intervalStr = prefs.getString("auto_save", "1m") ?: "1m"

                    if (intervalStr != "off") {
                        saveProject()
                        Log.d("TimelineEditor", "Auto-saved project")
                    }

                    val intervalMs =
                            when (intervalStr) {
                                "30s" -> 30_000L
                                "1m" -> 60_000L
                                "2m" -> 120_000L
                                else -> 60_000L
                            }

                    autoSaveHandler.postDelayed(this, intervalMs)
                }
            }

    private fun startAutoSave() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        autoSaveHandler.postDelayed(autoSaveRunnable, 60_000L) // Start in 1 min
    }

    private fun updateToolUI() {
        val activeTool =
                (previewBinding.timelineView as? TimelineView)?.getActiveTool()
                        ?: TimelineTool.SELECT
        val clip = selectedClip

        updateContextualToolbar(clip)

        fun dp(v: Int) = ViewUtils.dpToPx(this, v)
        fun getThemeColor(attr: Int): Int {
            val typedValue = TypedValue()
            theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
        fun updateCard(view: View?, isActive: Boolean) {

            val cyan = Color.parseColor("#00D2D3")
            val activeFill = Color.parseColor("#4D00D2D3")
            val defaultStroke = Color.parseColor("#1AFFFFFF")
            val defaultBg = Color.parseColor("#0DFFFFFF")

            var strokeWidth = if (isActive) dp(2) else dp(1)
            val strokeColor = if (isActive) cyan else defaultStroke
            val bgColor = if (isActive) activeFill else defaultBg

            when {
                // DELETE BUTTON — match by ID, not by object equality
                view?.id == R.id.btnDeleteClip -> {
                    val enabled = clip != null

                    val redStroke = Color.parseColor("#FF5252")
                    val redFill = Color.parseColor("#1AFF5252")
                    val redIcon = Color.parseColor("#FF5252")

                    val defaultStroke = Color.parseColor("#1AFFFFFF")
                    val defaultBg = Color.parseColor("#0DFFFFFF")

                    val icon = previewBinding.iconDelete
                    val defaultIcon =
                            getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

                    (view as MaterialCardView).apply {
                        strokeWidth = if (enabled) dp(2) else dp(1)
                        setStrokeColor(
                                ColorStateList.valueOf(if (enabled) redStroke else defaultStroke)
                        )
                        setCardBackgroundColor(if (enabled) redFill else defaultBg)
                    }

                    icon?.setColorFilter(if (enabled) redIcon else defaultIcon)
                }

                // ALL OTHER TOOL BUTTONS
                view is MaterialCardView -> {
                    view.strokeWidth = if (isActive) dp(2) else dp(1)
                    view.setStrokeColor(ColorStateList.valueOf(strokeColor))
                    view.setCardBackgroundColor(bgColor)
                }
            }
            view?.alpha = if (isActive) 1f else 0.85f
        }

        updateCard(previewBinding.btnSelect, activeTool == TimelineTool.SELECT)
        updateCard(previewBinding.btnTrim, activeTool == TimelineTool.TRIM)
        updateCard(previewBinding.btnSplit, activeTool == TimelineTool.SPLIT)
        updateCard(previewBinding.btnSpeed, activeTool == TimelineTool.SPEED)
        updateCard(previewBinding.btnVolume, activeTool == TimelineTool.VOLUME)

        val isSel = clip != null
        updateCard(previewBinding.btnDeleteClip, isSel)

        if (ClipClipboard.hasClip()) {
            updateCard(previewBinding.btnPasteClip, true)
        } else {
            updateCard(previewBinding.btnCopyClip, false)
        }

        updateLevelVisuals()
    }

    private fun updateContextualToolbar(clip: TimelineClip?) {
        val isSel = clip != null
        val type = clip?.type

        // Hide all specific tools first
        previewBinding.btnSplit.visibility =
                if (isSel &&
                                type != ClipType.TEXT &&
                                type != ClipType.STICKER &&
                                type != ClipType.EMOJI
                )
                        View.VISIBLE
                else View.GONE
        previewBinding.btnTrim.visibility = if (isSel) View.VISIBLE else View.GONE

        // 📋 Copy/Paste Toggle logic (Unified Slot)
        val hasClipboard = com.example.videoeditorapp.model.timeline.ClipClipboard.hasClip()
        if (hasClipboard) {
            // Priority: Paste
            previewBinding.btnCopyClip?.visibility = View.GONE
            previewBinding.btnPasteClip?.visibility = View.VISIBLE
        } else {
            // Show Copy only if Selected
            previewBinding.btnCopyClip?.visibility = if (isSel) View.VISIBLE else View.GONE
            previewBinding.btnPasteClip?.visibility = View.GONE
        }

        previewBinding.btnDeleteClip.visibility = if (isSel) View.VISIBLE else View.GONE

        // Layering
        previewBinding.btnMoveToFront?.visibility =
                if (isSel && type != ClipType.AUDIO) View.VISIBLE else View.GONE
        previewBinding.btnMoveToBack?.visibility =
                if (isSel && type != ClipType.AUDIO) View.VISIBLE else View.GONE

        // Specifics
        previewBinding.btnSpeed.visibility =
                if (isSel && (type == ClipType.VIDEO || type == ClipType.AUDIO)) View.VISIBLE
                else View.GONE
        previewBinding.btnVolume.visibility =
                if (isSel && (type == ClipType.VIDEO || type == ClipType.AUDIO)) View.VISIBLE
                else View.GONE

        // Crop: Video & Image Only
        previewBinding.btnCropClip?.visibility =
                View.GONE // Moved to bottom bar or specialized toolbar
        // Fade: Video, Image & Audio
        previewBinding.tabFade?.visibility =
                if (isSel &&
                                (type == ClipType.VIDEO ||
                                        type == ClipType.IMAGE ||
                                        type == ClipType.AUDIO)
                )
                        View.VISIBLE
                else View.GONE

        // Opacity: Overlays (Text/Image/Sticker/Gif)
        previewBinding.tabOpacity?.visibility =
                if (isSel &&
                                (type == ClipType.TEXT ||
                                        type == ClipType.IMAGE ||
                                        type == ClipType.STICKER ||
                                        type == ClipType.GIF)
                )
                        View.VISIBLE
                else View.GONE

        // Reset visibility for tabEdit as it's the primary edit/adjust tab
        previewBinding.tabEdit.visibility = if (isSel) View.VISIBLE else View.GONE
    }

    private fun updateLevelVisuals() {
        val clip = selectedClip ?: return

        // Volume Fill (0.0 to 2.0 range)
        val volFactor = clip.audioVolume / 2.0f
        previewBinding.viewVolumeFill?.let { view ->
            val volParams = view.layoutParams
            volParams.height =
                    ((previewBinding.btnVolume.height ?: 0) * volFactor.coerceIn(0f, 1f)).toInt()
            view.layoutParams = volParams
        }

        // Speed Fill (0.25 to 2.0 range)
        val speedFactor = (clip.playbackSpeed - 0.25f) / (2.0f - 0.25f)
        previewBinding.viewSpeedFill?.let { view ->
            val speedParams = view.layoutParams
            speedParams.height =
                    ((previewBinding.btnSpeed.height ?: 0) * speedFactor.coerceIn(0f, 1f)).toInt()
            view.layoutParams = speedParams
        }
    }

    // Obsolete FAB menu logic removed to match new layout structure

    private fun showClipInfoDialog() {
        val clip =
                selectedClip
                        ?: run {
                            Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show()
                            return
                        }

        val file = File(clip.filePath)

        val message = buildString {
            append("Name: ${file.name}\n")
            append("Path: ${clip.filePath}\n")
            append("Start: ${ViewUtils.formatTime(clip.startTimeMs)}\n")
            append("Duration: ${ViewUtils.formatTime(clip.durationMs)}\n")
            append("Speed: ${clip.playbackSpeed}x\n")
            append("Volume: ${(clip.audioVolume * 100).toInt()}%\n")

            if (clip.effects.isNotEmpty()) {
                append("\nEffects / Filters:\n")
                clip.effects.forEach { append("- ${it.type} (${(it.intensity * 100).toInt()}%)\n") }
            }
        }

        // Inflate reusable dialog
        val dlg = com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(layoutInflater)

        // Create dialog FIRST so we can reference it
        val dialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setView(dlg.root)
                        .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Assign UI fields
        dlg.tvTitle.text = "Clip Information"
        dlg.tvMessage.text = message

        // Button: Metadata
        dlg.btnSecondary.text = "Metadata"
        dlg.btnSecondary.setOnClickListener {
            // TODO: Show technical metadata etc.
        }

        // Button: Close
        dlg.btnPrimary.text = "Close"
        dlg.btnPrimary.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // -------------------- TIMELINE --------------------

    private fun setupTimeline() {
        previewBinding.timelineView.setProject(project)

        // Playhead / scrubbing
        previewBinding.timelineView.onScrollListener = { timeMs ->
            if (!isSeeking) {
                isSeeking = true
                currentTimeMs = timeMs

                seekPlayerWithTimelineMap(timeMs, activeTimelineMap)

                previewBinding.tvTimeCode.text = ViewUtils.formatTime(timeMs)
                updatePreviewForTime(timeMs)
                isSeeking = false
            }
        }

        // Clip selection (UI enable/disable only)
        previewBinding.timelineView.onClipSelected = { clip ->
            selectedClip = clip
            updateBottomBarVisibility()
            updateToolUI() // 🔹 Refresh highlighting

            // Overlay Manipulation Logic
            if (clip != null &&
                            (clip.type == ClipType.TEXT ||
                                    clip.type == ClipType.IMAGE ||
                                    clip.type == ClipType.STICKER)
            ) {
                previewBinding.overlayManipulationView.visibility = View.VISIBLE
                previewBinding.overlayManipulationView.setTarget(clip) {
                    rebuildPlayerFromTimeline(currentTimeMs)
                    previewBinding.timelineView.invalidate()
                }
            } else {
                previewBinding.overlayManipulationView.visibility = View.GONE
            }
        }

        // Tool state sync
        previewBinding.timelineView.onToolChanged = { updateToolUI() }

        previewBinding.timelineView.onTimelineChanged = {
            updateUIDuration()
            rebuildPlayerFromTimeline(currentTimeMs)
        }
    }

    private fun setupPreviewResize() {
        val handle = previewBinding.previewResizeHandle ?: return
        handle.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val guideline =
                if (isLandscape) {
                    previewBinding.root.findViewById<androidx.constraintlayout.widget.Guideline>(
                            R.id.landscapeSplitGuideline
                    )
                } else {
                    previewBinding.root.findViewById<androidx.constraintlayout.widget.Guideline>(
                            R.id.previewSplitGuideline
                    )
                }

        if (guideline != null) {
            attachResizeGesture(handle) { dx, dy ->
                val parentSize =
                        if (isLandscape) (previewBinding.root.width ?: 0)
                        else (previewBinding.root.height ?: 0)
                if (parentSize == 0) return@attachResizeGesture

                val lp =
                        guideline.layoutParams as
                                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                val delta = if (isLandscape) dx else dy
                val change = delta / parentSize.toFloat()

                // Limit: 20% to 75%
                val newPercent = (lp.guidePercent + change).coerceIn(0.2f, 0.75f)
                guideline.setGuidelinePercent(newPercent)
                previewBinding.root.requestLayout()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachResizeGesture(handle: View, onDrag: (dx: Float, dy: Float) -> Unit) {
        var lastX = 0f
        var lastY = 0f
        var dragging = false

        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    lastX = event.rawX
                    lastY = event.rawY
                    dragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY
                    onDrag(dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    false // 🔑 LET SYSTEM FINISH GESTURE
                }
                else -> false
            }
        }
    }
    private fun setupProject() {
        // Load default resolution from settings
        val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val defaultRes = prefs.getString("default_res", "original") ?: "original"

        // Map "1080", "720" etc to approximate aspect ratios or resolution caps if needed.
        // For now, we just store it or use strict project defaults.
        // Assuming current logic defaults to ORIGINAL aspect ratio:

        project =
                TimelineProject(
                        name = projectName,
                        tracks =
                                mutableListOf(
                                        TimelineTrack("video", TrackType.VIDEO),
                                        TimelineTrack("audio", TrackType.AUDIO)
                                )
                )

        // If we had a resolution capped preset, we would apply it here.
        // For now, VideoEditorApp seems to determine output resolution dynamically based on clips
        // (getProjectMaxResolution)
        // so 'default_res' might be intended for Aspect Ratio or Export default?
        // Since 'default_res' in Settings is 480/720/1080/Original, it likely implies Export
        // Quality.
        // We will store this reference or simple ignore if not fully implemented in Export logic
        // yet.
        // However, user specifically asked for "other settings too", so let's log it or setup a
        // placeholder.
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun addMediaClip(uri: Uri) {
        saveHistory()
        when (contentResolver.getType(uri)?.substringBefore("/")) {
            "video" -> addVideoClip(uri)
            "audio" -> addAudioClip(uri)
            "image" -> addImageClip(uri)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun addVideoClip(uri: Uri) {
        val mmr = MediaMetadataRetriever()
        try {
            val duration =
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        mmr.setDataSource(this@TimelineTemplateEditorActivity, uri)
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                    }
                            ?: return

            val path =
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        copyUriToPersistentFile(
                                uri,
                                "video_clip_${System.currentTimeMillis()}.mp4",
                                project.id
                        )
                    }
                            ?: return

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val trackType =
                        if (currentAdditionMode == AdditionMode.APPEND) TrackType.VIDEO
                        else TrackType.OVERLAY
                val track =
                        if (trackType == TrackType.VIDEO) {
                            project.tracks.find { it.type == TrackType.VIDEO }
                                    ?: TimelineTrack(
                                                    id = UUID.randomUUID().toString(),
                                                    type = TrackType.VIDEO
                                            )
                                            .also { project.tracks.add(it) }
                        } else {
                            findSmartTrack(TrackType.OVERLAY, currentTimeMs, duration)
                        }

                val startTime =
                        if (currentAdditionMode == AdditionMode.APPEND) {
                            track.clips.lastOrNull()?.endTimeMs ?: 0L
                        } else {
                            currentTimeMs
                        }

                val clip =
                        TimelineClip(
                                id = UUID.randomUUID().toString(),
                                filePath = path,
                                startTimeMs = startTime,
                                durationMs = duration,
                                sourceStartTimeMs = 0L,
                                sourceDurationMs = duration,
                                type = ClipType.VIDEO
                        )
                track.clips.add(clip)
                previewBinding.timelineView.animateClip(clip.id)

                if (trackType == TrackType.VIDEO) {
                    val videoAudioTrack = findSmartTrack(TrackType.AUDIO, startTime, duration)
                    val audioPart =
                            clip.copy(id = UUID.randomUUID().toString(), type = ClipType.AUDIO)
                    videoAudioTrack.clips.add(audioPart)
                    previewBinding.timelineView.animateClip(audioPart.id)
                }

                // 🌟 V5: Auto-trigger Project Settings on FIRST clip
                val totalClipsCount = project.tracks.sumOf { it.clips.size }
                if (totalClipsCount == 1) {
                    android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ showProjectSettingsDialog() }, 500)
                }
            }
            rebuildPlayerFromTimeline(currentTimeMs)
            previewBinding.timelineView.invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            withContext(kotlinx.coroutines.Dispatchers.IO) { mmr.release() }
        }
    }

    private suspend fun addAudioClip(uri: Uri) {
        val mmr = MediaMetadataRetriever()
        try {
            val duration =
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        mmr.setDataSource(this@TimelineTemplateEditorActivity, uri)
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                    }
                            ?: return

            val path =
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        copyUriToPersistentFile(
                                uri,
                                "audio_clip_${System.currentTimeMillis()}.mp3",
                                project.id
                        )
                    }
                            ?: return

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val start =
                        if (currentAdditionMode == AdditionMode.APPEND) {
                            val videoTrack = project.tracks.find { it.type == TrackType.VIDEO }
                            videoTrack?.clips?.lastOrNull()?.endTimeMs ?: 0L
                        } else {
                            currentTimeMs
                        }

                val clip =
                        TimelineClip(
                                id = UUID.randomUUID().toString(),
                                filePath = path,
                                startTimeMs = start,
                                durationMs = duration,
                                sourceStartTimeMs = 0L,
                                sourceDurationMs = duration,
                                type = ClipType.AUDIO
                        )
                val track = findSmartTrack(TrackType.AUDIO, start, duration)
                track.clips.add(clip)
                previewBinding.timelineView.animateClip(clip.id)
                rebuildPlayerFromTimeline(currentTimeMs)
                previewBinding.timelineView.invalidate()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            withContext(kotlinx.coroutines.Dispatchers.IO) { mmr.release() }
        }
    }

    // 🍏 V4: Long Press Dialog Implementation
    private fun showClipActionsDialog(clip: TimelineClip) {
        val dialogBinding =
                com.example.videoeditorapp.databinding.DialogClipActionsBinding.inflate(
                        layoutInflater
                )
        val dialog =
                AlertDialog.Builder(this).setView(dialogBinding.root).setCancelable(true).create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 1. Populate Info
        dialogBinding.tvClipTitle.text = clip.type.name + " CLIP"
        dialogBinding.tvClipDuration.text = ViewUtils.formatTime(clip.durationMs)

        // 2. Determine Linked State
        val linkedClips = previewBinding.timelineView.findLinkedClips(clip) ?: emptyList()
        val hasLinkedVideo = linkedClips.any { it.type == ClipType.VIDEO }
        val hasLinkedAudio = linkedClips.any { it.type == ClipType.AUDIO }
        val isLinked = linkedClips.size > 1

        // 3. Configure Split Options
        if (isLinked) {
            dialogBinding.actionSplitLinked.visibility = View.VISIBLE
            dialogBinding.actionSplitVideo.visibility =
                    if (hasLinkedVideo) View.VISIBLE else View.GONE
            dialogBinding.actionSplitAudio.visibility =
                    if (hasLinkedAudio) View.VISIBLE else View.GONE
        } else {
            // Single clip, just split normally
            dialogBinding.actionSplitLinked.visibility = View.VISIBLE
            dialogBinding.actionSplitVideo.visibility = View.GONE
            dialogBinding.actionSplitAudio.visibility = View.GONE
        }

        // 4. Click Listeners
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        // SPLIT ALL
        dialogBinding.actionSplitLinked.setOnClickListener {
            dialog.dismiss()
            splitClipAtPlayhead(clip, splitAllLinked = true)
        }

        // SPLIT VIDEO ONLY
        dialogBinding.actionSplitVideo.setOnClickListener {
            dialog.dismiss()
            splitClipAtPlayhead(clip, splitVideoOnly = true)
        }

        // SPLIT AUDIO ONLY
        dialogBinding.actionSplitAudio.setOnClickListener {
            dialog.dismiss()
            splitClipAtPlayhead(clip, splitAudioOnly = true)
        }

        // TRIM START (Move Start to Playhead)
        dialogBinding.actionTrimStart.setOnClickListener {
            dialog.dismiss()
            trimClipToPlayhead(clip, trimStart = true)
        }

        // TRIM END
        dialogBinding.actionTrimEnd.setOnClickListener {
            dialog.dismiss()
            trimClipToPlayhead(clip, trimStart = false)
        }

        // UNLINK / LINK
        var hasPotentialPartners = false
        if (!isLinked) {
            val partners = mutableListOf<TimelineClip>()
            project.tracks.forEach { track ->
                track.clips.forEach { c ->
                    if (c.filePath == clip.filePath &&
                                    c.startTimeMs == clip.startTimeMs &&
                                    c.durationMs == clip.durationMs
                    ) {
                        partners.add(c)
                    }
                }
            }
            hasPotentialPartners = partners.size > 1
        }

        if (isLinked) {
            dialogBinding.dividerUnlink.visibility = View.VISIBLE
            dialogBinding.actionUnlink.visibility = View.VISIBLE
            dialogBinding.actionLink.visibility = View.GONE
            dialogBinding.actionUnlink.setOnClickListener {
                dialog.dismiss()
                toggleLinkUnlink(clip)
            }
        } else if (hasPotentialPartners) {
            dialogBinding.dividerUnlink.visibility = View.VISIBLE
            dialogBinding.actionUnlink.visibility = View.GONE
            dialogBinding.actionLink.visibility = View.VISIBLE
            dialogBinding.actionLink.setOnClickListener {
                dialog.dismiss()
                toggleLinkUnlink(clip)
            }
        } else {
            dialogBinding.dividerUnlink.visibility = View.GONE
            dialogBinding.actionUnlink.visibility = View.GONE
            dialogBinding.actionLink.visibility = View.GONE
        }

        // DELETE
        dialogBinding.btnDeleteClip.setOnClickListener {
            dialog.dismiss()
            (previewBinding.timelineView as? TimelineView)?.deleteSelectedClip()
            updateToolUI()
        }

        dialog.show()
    }

    private fun trimClipToPlayhead(targetClip: TimelineClip, trimStart: Boolean) {
        val playhead = currentTimeMs
        if (playhead <= targetClip.startTimeMs || playhead >= targetClip.endTimeMs) {
            Toast.makeText(this, "Playhead must be inside clip to trim", Toast.LENGTH_SHORT).show()
            return
        }

        saveHistory()
        val partners = previewBinding.timelineView.findLinkedClips(targetClip) ?: listOf(targetClip)

        partners.forEach { clip ->
            if (trimStart) {
                val delta = playhead - clip.startTimeMs
                clip.sourceStartTimeMs += delta
                clip.startTimeMs = playhead
                clip.durationMs -= delta
            } else {
                clip.durationMs = playhead - clip.startTimeMs
            }
        }

        previewBinding.timelineView.invalidate()
        rebuildPlayerFromTimeline(currentTimeMs)
        Toast.makeText(this, if (trimStart) "Trimmed Left" else "Trimmed Right", Toast.LENGTH_SHORT)
                .show()
    }

    private fun splitClipAtPlayhead(
            targetClip: TimelineClip,
            splitAllLinked: Boolean = false,
            splitVideoOnly: Boolean = false,
            splitAudioOnly: Boolean = false
    ) {
        val splitTime = currentTimeMs

        // Validation: Cursor must be inside clip
        if (splitTime <= targetClip.startTimeMs + 50 || splitTime >= targetClip.endTimeMs - 50) {
            Toast.makeText(this, "Playhead must be inside clip to split", Toast.LENGTH_SHORT).show()
            return
        }

        saveHistory()
        val clipsToSplit = mutableListOf<TimelineClip>()

        if (splitAllLinked) {
            clipsToSplit.addAll(
                    previewBinding.timelineView.findLinkedClips(targetClip) ?: emptyList()
            )
        } else if (splitVideoOnly) {
            clipsToSplit.addAll(
                    previewBinding.timelineView.findLinkedClips(targetClip)?.filter {
                        it.type == ClipType.VIDEO
                    }
                            ?: emptyList()
            )
        } else if (splitAudioOnly) {
            clipsToSplit.addAll(
                    previewBinding.timelineView.findLinkedClips(targetClip)?.filter {
                        it.type == ClipType.AUDIO
                    }
                            ?: emptyList()
            )
        } else {
            clipsToSplit.add(targetClip)
        }

        var anySplit = false
        clipsToSplit.forEach { clip ->
            val newClip = previewBinding.timelineView.splitClip(clip, splitTime)
            if (newClip != null) anySplit = true
        }

        if (anySplit) {
            selectedClip = null
            previewBinding.timelineView.notifyDataChanged()
            rebuildPlayerFromTimeline(currentTimeMs)
            updateToolUI()
            Toast.makeText(this, "Split Successful", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleLinkUnlink(clip: TimelineClip) {
        val currLinked = previewBinding.timelineView.findLinkedClips(clip) ?: listOf(clip)
        val isCurrentlyLinked = currLinked.size > 1

        saveHistory()
        if (isCurrentlyLinked) {
            // Unlink all currently linked
            currLinked.forEach { it.metadata["UNLINKED"] = "true" }
            Toast.makeText(this, "Unlinked Audio & Video", Toast.LENGTH_SHORT).show()
        } else {
            // Try to link with partners
            val proj = project
            val partners = mutableListOf<TimelineClip>()
            proj.tracks.forEach { track ->
                track.clips.forEach { c ->
                    if (c.filePath == clip.filePath &&
                                    c.startTimeMs == clip.startTimeMs &&
                                    c.durationMs == clip.durationMs
                    ) {
                        partners.add(c)
                    }
                }
            }
            if (partners.size > 1) {
                partners.forEach { it.metadata.remove("UNLINKED") }
                Toast.makeText(this, "Linked Audio & Video", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No matching audio/video to link", Toast.LENGTH_SHORT).show()
            }
        }
        previewBinding.timelineView.invalidate()
        updateToolUI()
    }

    // 🍏 V4: Unified Editor Dialog System
    private fun showUnifiedEditor(
            title: String,
            options: List<com.example.videoeditorapp.ui.editor.EditorOption>,
            onValueChanged: (com.example.videoeditorapp.ui.editor.EditorOption, Float) -> Unit
    ) {
        val dialogBinding =
                com.example.videoeditorapp.databinding.DialogUnifiedEditorBinding.inflate(
                        layoutInflater
                )

        // 🍎 FIX: Add LayoutManager for RecyclerView to actually show items
        dialogBinding.rvOptions.layoutManager =
                androidx.recyclerview.widget.LinearLayoutManager(
                        this,
                        androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                        false
                )

        val dialog =
                com.google.android.material.bottomsheet.BottomSheetDialog(
                        this,
                        R.style.Theme_VideoEditorApp_BottomSheet
                )

        dialog.setContentView(dialogBinding.root)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet
            ->
            sheet.background = null
            ViewCompat.setBackground(sheet, ColorDrawable(Color.TRANSPARENT))
        }

        dialog.dismissWithAnimation = true

        dialog.show()

        // Header
        dialogBinding.tvDialogTitle.text = title.uppercase()
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        var currentOption = options.first()

        // Slider Setup
        fun updateSliderForOption(option: com.example.videoeditorapp.ui.editor.EditorOption) {
            currentOption = option

            // Hide all controls first
            dialogBinding.sliderControl.visibility = View.GONE
            dialogBinding.wheelControl.visibility = View.GONE
            dialogBinding.circularControl.visibility = View.GONE
            dialogBinding.tvSliderLabel.visibility = View.VISIBLE
            dialogBinding.tvSliderValue.visibility = View.VISIBLE

            // Handle Action Type
            if (option.type == com.example.videoeditorapp.ui.editor.OptionType.ACTION) {
                dialogBinding.tvSliderLabel.visibility = View.INVISIBLE
                dialogBinding.tvSliderValue.visibility = View.INVISIBLE
                return
            }

            dialogBinding.tvSliderLabel.text = option.label

            // 🚀 FORCE SLIDER for all per user request (WHEEL/CIRCULAR are not precise enough)
            val range = option.maxValue - option.minValue
            val calculatedStepSize =
                    when {
                        range > 10 -> 1.0f
                        range > 1 -> 0.1f
                        else -> 0.01f
                    }

            // Material Slider crash prevention: Value must be (minValue + n * stepSize)
            // We align minValue to be a multiple of stepSize to keep things clean
            val alignedMin =
                    (Math.floor((option.minValue / calculatedStepSize).toDouble()) *
                                    calculatedStepSize)
                            .toFloat()
            val alignedMax =
                    (Math.ceil((option.maxValue / calculatedStepSize).toDouble()) *
                                    calculatedStepSize)
                            .toFloat()

            dialogBinding.sliderControl.visibility = View.VISIBLE
            dialogBinding.sliderControl.stepSize = calculatedStepSize
            dialogBinding.sliderControl.valueFrom = alignedMin
            dialogBinding.sliderControl.valueTo =
                    alignedMax.coerceAtLeast(alignedMin + calculatedStepSize)

            val snappedValue =
                    (Math.round((option.value / calculatedStepSize).toDouble()) *
                                    calculatedStepSize)
                            .toFloat()
            dialogBinding.sliderControl.value = snappedValue.coerceIn(alignedMin, alignedMax)

            val displayValue =
                    if (option.maxValue == 1f && option.minValue == 0f) {
                        "${(option.value * 100).toInt()}%"
                    } else {
                        String.format("%.1f", option.value)
                    }
            dialogBinding.tvSliderValue.text = displayValue
        }

        updateSliderForOption(currentOption)

        // Adapter
        val adapter =
                com.example.videoeditorapp.ui.editor.EditorOptionsAdapter(options) { selected ->
                    updateSliderForOption(selected)
                    if (selected.type == com.example.videoeditorapp.ui.editor.OptionType.ACTION) {
                        onValueChanged(selected, 0f)
                    }
                }
        dialogBinding.rvOptions.adapter = adapter

        // Helper for formatting
        fun formatDisplayValue(
                option: com.example.videoeditorapp.ui.editor.EditorOption,
                value: Float
        ): String {
            val labelUpper = option.label.uppercase()
            return when {
                labelUpper.contains("(MS)") ||
                        labelUpper.contains("FADE") ||
                        labelUpper.contains("DELAY") -> {
                    "${value.toInt()}ms"
                }
                option.maxValue == 1f && option.minValue == 0f -> {
                    "${(value * 100).toInt()}%"
                }
                else -> String.format("%.1f", value)
            }
        }

        // Listeners for all controls
        dialogBinding.sliderControl.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentOption.value = value
                adapter.updateValue(currentOption.id, value)
                dialogBinding.tvSliderValue.text = formatDisplayValue(currentOption, value)
                onValueChanged(currentOption, value)
            }
        }

        dialogBinding.wheelControl.setOnValueChangedListener { value ->
            currentOption.value = value
            adapter.updateValue(currentOption.id, value)
            dialogBinding.tvSliderValue.text = formatDisplayValue(currentOption, value)
            onValueChanged(currentOption, value)
        }

        dialogBinding.circularControl.setOnValueChangedListener { value ->
            currentOption.value = value
            adapter.updateValue(currentOption.id, value)
            dialogBinding.tvSliderValue.text = formatDisplayValue(currentOption, value)
            onValueChanged(currentOption, value)
        }

        dialogBinding.btnDone.setOnClickListener {
            saveProject()
            dialog.dismiss()
            Toast.makeText(this, "Changes Applied", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private suspend fun addImageClip(uri: Uri) {
        val duration = DEFAULT_IMAGE_DURATION_MS
        val path =
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    copyUriToPersistentFile(
                            uri,
                            "image_clip_${System.currentTimeMillis()}.png",
                            project.id
                    )
                }
                        ?: return

        withContext(kotlinx.coroutines.Dispatchers.Main) {
            val start =
                    if (currentAdditionMode == AdditionMode.APPEND) {
                        val videoTrack = project.tracks.find { it.type == TrackType.VIDEO }
                        videoTrack?.clips?.lastOrNull()?.endTimeMs ?: 0L
                    } else {
                        currentTimeMs
                    }

            val track = findSmartTrack(TrackType.OVERLAY, start, duration)
            val clip =
                    TimelineClip(
                            id = UUID.randomUUID().toString(),
                            filePath = path,
                            startTimeMs = start,
                            durationMs = duration,
                            sourceStartTimeMs = 0L,
                            sourceDurationMs = duration,
                            type = ClipType.IMAGE
                    )
            track.clips.add(clip)
            previewBinding.timelineView.animateClip(clip.id)
        }
    }

    // -------------------- EXPORT --------------------
    private fun validateTimeline(): Boolean {
        val missingFiles = mutableListOf<String>()
        project.tracks.forEach { track ->
            // for now)
            track.clips.forEach { clip ->
                val exists =
                        if (File(clip.filePath).exists()) true
                        else {
                            // Try resolving asset path
                            com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(
                                    this,
                                    clip.filePath
                            ) != null
                        }
                if (!exists) {
                    missingFiles.add(File(clip.filePath).name)
                }
            }
        }

        if (missingFiles.isNotEmpty()) {
            val message =
                    "The following source files are missing from storage:\n\n- " +
                            missingFiles.take(5).joinToString("\n- ") +
                            if (missingFiles.size > 5) "\n...and ${missingFiles.size - 5} others"
                            else ""

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Missing Files")
                    .setMessage(
                            message +
                                    "\nPlease re-import these files or remove them from the timeline."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            return false
        }

        // Logical checks
        project.tracks.forEach { track ->
            val sorted = track.clips.sortedBy { it.startTimeMs }
            for (i in sorted.indices) {
                val clip = sorted[i]
                if (clip.durationMs <= 0 ||
                                clip.sourceStartTimeMs < 0 ||
                                clip.sourceStartTimeMs + clip.durationMs > clip.sourceDurationMs
                ) {
                    return false
                }
                if (i > 0) {
                    val prev = sorted[i - 1]
                    val prevEnd = prev.startTimeMs + prev.durationMs
                    // Allow 1ms overlap tolerance
                    if (clip.startTimeMs < prevEnd - 1) return false
                }
            }
        }
        return true
    }

    private fun getProjectMaxResolution(): String {
        var currentMax = "480p"
        project.tracks.flatMap { it.clips }.forEach { clip ->
            if (clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE) {
                val (w, h) = getVideoDimensions(clip.filePath)
                val maxDim = maxOf(w, h)
                if (maxDim >= 3840) return "4K"
                if (maxDim >= 2560) currentMax = "2K"
                if (maxDim >= 1920 && currentMax != "2K") currentMax = "1080p"
                if (maxDim >= 1280 && currentMax == "480p" && currentMax != "1080p")
                        currentMax = "720p"
            }
        }
        return currentMax
    }

    private fun checkAndRequestNotificationPermission() {
        com.example.videoeditorapp.utils.PermissionHelper.checkNotificationPermission(this)
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        com.example.videoeditorapp.utils.PermissionHelper.handlePermissionResult(
                requestCode,
                grantResults,
                onNotificationGranted = {
                    Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
                },
                onMediaGranted = {
                    Toast.makeText(this, "Media access granted", Toast.LENGTH_SHORT).show()
                },
                onDenied = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        )
    }

    private fun getRecommendedSettings(): Pair<String, String> {
        val firstClip =
                project.tracks.flatMap { it.clips }.firstOrNull {
                    it.type == ClipType.VIDEO || it.type == ClipType.IMAGE
                }
                        ?: return "1080p" to "Original"

        val (w, h) = getVideoDimensions(firstClip.filePath)
        val ratio = w.toFloat() / h.toFloat()
        val maxDim = maxOf(w, h)

        val recommendedRes =
                when {
                    maxDim >= 3840 -> "4K"
                    maxDim >= 2560 -> "2K"
                    maxDim >= 1920 -> "1080p"
                    maxDim >= 1280 -> "720p"
                    else -> "480p"
                }

        val recommendedAsp =
                when {
                    ratio > 1.7f && ratio < 1.8f -> "YouTube" // 16:9
                    ratio > 0.5f && ratio < 0.6f -> "9:16" // 9:16
                    ratio > 0.9f && ratio < 1.1f -> "Post" // 1:1
                    ratio > 2.3f -> "21:9" // Cinematic
                    else -> "Original"
                }

        return recommendedRes to recommendedAsp
    }

    private fun isCodecSupported(mime: String): Boolean {
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        } catch (e: Exception) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showProjectSettingsDialog() {
        val darkContext = android.view.ContextThemeWrapper(this, R.style.Theme_VideoEditorApp_Dark)
        val dialogBinding =
                com.example.videoeditorapp.databinding.DialogExportOptionsBinding.inflate(
                        android.view.LayoutInflater.from(darkContext)
                )

        dialogBinding.tvTitle.text = "Project Settings"

        val maxRes = getProjectMaxResolution()

        val resChips =
                mapOf(
                        "Original" to dialogBinding.chipResOriginal,
                        "480p" to dialogBinding.chipRes480,
                        "720p" to dialogBinding.chipRes720,
                        "1080p" to dialogBinding.chipRes1080,
                        "2K" to dialogBinding.chipRes2K,
                        "4K" to dialogBinding.chipRes4K
                )

        val resOrder = listOf("480p", "720p", "1080p", "2K", "4K")
        val maxIndex = resOrder.indexOf(maxRes)
        resOrder.forEach { r ->
            val chip = resChips[r]
            if (resOrder.indexOf(r) > maxIndex) {
                chip?.isEnabled = false
                chip?.alpha = 0.2f
            }
        }

        val dialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setView(dialogBinding.root)
                        .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val appPrefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val defaultRes = appPrefs.getString("default_res", "1080p") ?: "1080p"
        val resolvedDefaultRes = if (defaultRes.endsWith("p")) defaultRes else "${defaultRes}p"

        var selectedRes = project.exportResolution ?: resolvedDefaultRes
        var selectedQuality =
                project.renderQuality ?: appPrefs.getString("export_quality", "High") ?: "High"
        var selectedAsp = project.activePreset.label

        val defaultCodec =
                appPrefs.getString("default_codec", "h264_mediacodec") ?: "h264_mediacodec"
        var selectedCodec = project.metadata["CODEC"] ?: defaultCodec

        val defaultAudioQual = appPrefs.getString("default_audio_quality", "192K") ?: "192K"
        var selectedAudioQuality = project.metadata["AUDIO_QUALITY"] ?: defaultAudioQual

        val (recRes, recAsp) = getRecommendedSettings()

        val recResLabels =
                mapOf(
                        "Original" to dialogBinding.tvRecOriginal,
                        "480p" to dialogBinding.tvRec480,
                        "720p" to dialogBinding.tvRec720,
                        "1080p" to dialogBinding.tvRec1080,
                        "2K" to dialogBinding.tvRec2K,
                        "4K" to dialogBinding.tvRec4K
                )

        val recAspLabels =
                mapOf(
                        "Original" to dialogBinding.tvRecAspOriginal,
                        "9:16" to dialogBinding.tvRecAspPortrait,
                        "YouTube" to dialogBinding.tvRecAspLandscape,
                        "Post" to dialogBinding.tvRecAspSquare,
                        "Reel" to dialogBinding.tvRecAspReel,
                        "21:9" to dialogBinding.tvRecAspCinematic
                )

        fun updateSummary() {
            val (w, h) = calculateExportDimensions(selectedRes, selectedAsp)
            dialogBinding.tvExportSummary.text =
                    "OUTPUT: ${selectedRes.uppercase()} @ ${selectedAsp.uppercase()}\nQUALITY: ${selectedQuality.uppercase()} (AUDIO:${selectedAudioQuality})\nRESOLVED: ${w}x${h}px"

            if (project.watermarkPath != null) {
                dialogBinding.tvWatermarkStatus.text =
                        "OVERLAY_ACTIVE: ${File(project.watermarkPath!!).name}"
                dialogBinding.btnClearWatermark.visibility = View.VISIBLE
            } else {
                dialogBinding.tvWatermarkStatus.text = "NO_OVERLAY_ACTIVE"
                dialogBinding.btnClearWatermark.visibility = View.GONE
            }
        }

        fun updateUI() {
            // Recommendation labels update
            resChips.forEach { (r, chip) ->
                val baseText =
                        when (r) {
                            "Original" -> "ORIGINAL"
                            "4K" -> "4K_UHD"
                            "2K" -> "2K_QHD"
                            else -> r.uppercase()
                        }
                chip.text = baseText
                chip.isChecked = (r == selectedRes)
            }
            recResLabels.forEach { (key, label) ->
                label.visibility = if (key == recRes) View.VISIBLE else View.GONE
            }

            val aspChips =
                    mapOf(
                            "Original" to dialogBinding.chipAspectOriginal,
                            "9:16" to dialogBinding.chipAspectPortrait,
                            "YouTube" to dialogBinding.chipAspectLandscape,
                            "Post" to dialogBinding.chipAspectSquare,
                            "Reel" to dialogBinding.chipAspectReel,
                            "21:9" to dialogBinding.chipAspectCinematic
                    )
            aspChips.forEach { (a, chip) ->
                val baseText = a.uppercase()
                chip.text = baseText
                chip.isChecked = (a == selectedAsp)
            }
            recAspLabels.forEach { (key, label) ->
                label.visibility = if (key == recAsp) View.VISIBLE else View.GONE
            }

            val qualityChips =
                    mapOf(
                            "Low" to dialogBinding.chipQualityLow,
                            "Medium" to dialogBinding.chipQualityMed,
                            "High" to dialogBinding.chipQualityHigh
                    )
            qualityChips.forEach { (q, chip) -> chip.isChecked = (q == selectedQuality) }

            val codecChips =
                    mapOf(
                            "h264_mediacodec" to dialogBinding.chipCodecH264,
                            "hevc_mediacodec" to dialogBinding.chipCodecH265
                    )
            val h264Supported = isCodecSupported("video/avc")
            val h265Supported = isCodecSupported("video/hevc")
            codecChips.forEach { (c, chip) ->
                val supported = if (c == "h264_mediacodec") h264Supported else h265Supported
                chip.isEnabled = supported
                chip.alpha = if (supported) 1.0f else 0.4f
                chip.isChecked = (c == selectedCodec)
            }

            val audioChips =
                    mapOf(
                            "128K" to dialogBinding.chipAudioLow,
                            "192K" to dialogBinding.chipAudioMed,
                            "320K" to dialogBinding.chipAudioHigh
                    )
            audioChips.forEach { (q, chip) -> chip.isChecked = (q == selectedAudioQuality) }

            updateSummary()
        }

        resChips.forEach { (r, chip) ->
            chip.setOnClickListener {
                selectedRes = r
                updateUI()
            }
        }

        val aspChipsList =
                mapOf(
                        "Original" to dialogBinding.chipAspectOriginal,
                        "9:16" to dialogBinding.chipAspectPortrait,
                        "YouTube" to dialogBinding.chipAspectLandscape,
                        "Post" to dialogBinding.chipAspectSquare,
                        "Reel" to dialogBinding.chipAspectReel,
                        "21:9" to dialogBinding.chipAspectCinematic
                )
        aspChipsList.forEach { (a, chip) ->
            chip.setOnClickListener {
                selectedAsp = a
                updateUI()
            }
        }

        dialogBinding.chipQualityLow.setOnClickListener {
            selectedQuality = "Low"
            updateUI()
        }
        dialogBinding.chipQualityMed.setOnClickListener {
            selectedQuality = "Medium"
            updateUI()
        }
        dialogBinding.chipQualityHigh.setOnClickListener {
            selectedQuality = "High"
            updateUI()
        }

        dialogBinding.chipCodecH264.setOnClickListener {
            selectedCodec = "h264_mediacodec"
            updateUI()
        }
        dialogBinding.chipCodecH265.setOnClickListener {
            if (isCodecSupported("video/hevc")) {
                selectedCodec = "hevc_mediacodec"
                updateUI()
            } else {
                Toast.makeText(
                                this,
                                "H.265 (HEVC) not supported on this device",
                                Toast.LENGTH_SHORT
                        )
                        .show()
                updateUI() // Keep H.264 selected
            }
        }

        dialogBinding.chipAudioLow.setOnClickListener {
            selectedAudioQuality = "128K"
            updateUI()
        }
        dialogBinding.chipAudioMed.setOnClickListener {
            selectedAudioQuality = "192K"
            updateUI()
        }
        dialogBinding.chipAudioHigh.setOnClickListener {
            selectedAudioQuality = "320K"
            updateUI()
        }

        dialogBinding.btnChooseWatermark.setOnClickListener {
            pendingWatermarkPicker = { updateUI() }
            pickWatermarkLauncher.launch("image/*")
        }
        dialogBinding.btnClearWatermark.setOnClickListener {
            project.watermarkPath = null
            updateUI()
        }

        dialogBinding.btnCancel.text = "Apply Changes"
        dialogBinding.btnCancel.setOnClickListener {
            project.exportResolution = selectedRes
            project.renderQuality = selectedQuality
            project.activePreset =
                    ExportPreset.values().find { it.label == selectedAsp } ?: ExportPreset.ORIGINAL
            project.metadata["CODEC"] = selectedCodec
            project.metadata["AUDIO_QUALITY"] = selectedAudioQuality
            dialog.dismiss()
            Toast.makeText(this, "Settings Applied", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnExportConfirm.setOnClickListener {
            if (project.getDurationMs() <= 0) {
                Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            project.exportResolution = selectedRes
            project.renderQuality = selectedQuality
            project.activePreset =
                    ExportPreset.values().find { it.label == selectedAsp } ?: ExportPreset.ORIGINAL
            project.metadata["CODEC"] = selectedCodec
            project.metadata["AUDIO_QUALITY"] = selectedAudioQuality

            dialog.dismiss()
            checkAndRequestNotificationPermission()
            startExportService(selectedRes, selectedAsp)
        }

        dialog.show()
        updateUI()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startExportService(resolution: String, aspectRatio: String) {
        // Use standardized naming with resolution and versioning
        val fileName =
                com.example.videoeditorapp.utils.NamingUtils.generateExportFilename(
                        this,
                        projectName,
                        resolution
                )
        val outFile =
                File(com.example.videoeditorapp.utils.NamingUtils.getExportDirectory(), fileName)
        // outFile.parentFile?.mkdirs() // Handled by naming utils

        // Calculate actual dimensions based on selection
        val (width, height) = calculateExportDimensions(resolution, aspectRatio)

        Log.d("Export", "--- Export Request Details ---")
        Log.d("Export", "Project Name: $projectName")
        Log.d("Export", "Tracks: ${project.tracks.size}")
        project.tracks.forEachIndexed { index, track ->
            Log.d("Export", "  Track #$index Type: ${track.type}, Clips: ${track.clips.size}")
        }
        Log.d("Export", "Resolution: $resolution -> ${width}x${height}")
        Log.d("Export", "Aspect Ratio: $aspectRatio")
        Log.d("Export", "-----------------------------")

        try {
            val args =
                    FFmpegTimelineUtils.generateTimelineExportArguments(
                            this,
                            project,
                            outFile.absolutePath,
                            width,
                            height
                    )

            val dialogBinding = DialogExportProgressBinding.inflate(layoutInflater)
            val progressDialog =
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setView(dialogBinding.root)
                            .setCancelable(false)
                            .create()

            progressDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            progressDialog.show()

            Log.d("Export", "Starting Export Service with ${args.size} arguments")
            Log.d("Export", "Output Path: ${outFile.absolutePath}")
            Log.d("Export", "Command: ${args.joinToString(" ")}")

            val durationMs = project.getDurationMs()
            Log.d("Export", "Project duration: $durationMs ms")

            val serviceIntent =
                    Intent(this, ExportService::class.java).apply {
                        action = ExportService.ACTION_START
                        putExtra(ExportService.EXTRA_CMD, args)
                        putExtra(ExportService.EXTRA_OUTPUT_PATH, outFile.absolutePath)
                        putExtra(ExportService.EXTRA_DURATION, durationMs)
                        putExtra(ExportService.EXTRA_PROJECT_NAME, projectName)
                    }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d("Export", "startForegroundService called")

            Handler(Looper.getMainLooper())
                    .post(
                            object : Runnable {
                                override fun run() {
                                    dialogBinding.progressBar.progress = ExportState.progress
                                    dialogBinding.tvProgress.text = "${ExportState.progress}%"
                                    if (ExportState.isRunning) {
                                        Handler(Looper.getMainLooper()).postDelayed(this, 500)
                                    } else {
                                        progressDialog.dismiss()
                                        openExportScreen(outFile.absolutePath, projectName)
                                    }
                                }
                            }
                    )
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Export failed to minimize: ${e.message}", Toast.LENGTH_LONG)
                    .show()
        }
    }

    // Duplicated function removed

    // -------------------- UTILS --------------------

    private fun copyUriToTempFile(uri: Uri): String? {
        return try {
            val dir = File(filesDir, "timeline_media")
            if (!dir.exists()) dir.mkdirs()

            val mime = contentResolver.getType(uri) ?: return null
            val ext =
                    when {
                        mime.startsWith("video") -> "mp4"
                        mime.startsWith("audio") -> "mp3"
                        mime.startsWith("image") -> "png"
                        else -> "dat"
                    }

            val file = File(dir, "clip_${System.currentTimeMillis()}.$ext")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }

            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
    private var playheadRunnable: Runnable? = null

    private fun startPlayheadUpdate() {
        if (playheadRunnable != null) return // 🔒 prevent duplicates

        playheadRunnable =
                object : Runnable {
                    override fun run() {
                        val player = exoPlayer

                        if (player != null && player.isPlaying) {
                            currentTimeMs = playerPositionToTimelineTime()
                            previewBinding.timelineView.seekTo(currentTimeMs)
                            previewBinding.tvTimeCode.text = ViewUtils.formatTime(currentTimeMs)

                            // 🍏 V4: Update Overlay Preview Sync
                            syncOverlayPreview(currentTimeMs)

                            Handler(Looper.getMainLooper()).postDelayed(this, 100)
                        } else {
                            playheadRunnable = null // 🧹 clean exit
                        }
                    }
                }

        Handler(Looper.getMainLooper()).post(playheadRunnable!!)
    }
    private fun playerPositionToTimelineTime(): Long {
        val player = exoPlayer ?: return 0L
        if (activeTimelineMap.isEmpty()) return 0L

        val itemIndex = player.currentMediaItemIndex
        if (itemIndex !in activeTimelineMap.indices) return 0L

        val (timelineStartMs, durationMs) = activeTimelineMap[itemIndex]

        val positionInItem = player.currentPosition.coerceIn(0L, durationMs)

        return timelineStartMs + positionInItem
    }
    private fun seekPlayerWithTimelineMap(timeMs: Long, map: List<Pair<Long, Long>>) {
        val player = exoPlayer ?: return
        if (map.isEmpty()) return

        for (i in map.indices) {
            val (start, duration) = map[i]
            val end = start + duration

            if (timeMs in start until end) {
                val offsetInClip = timeMs - start
                // Important: Use the index of the MediaItem in the playlist
                player.seekTo(i, offsetInClip)
                return
            }
        }

        // Fallback: If at the very end of the timeline
        if (timeMs >= (map.last().first + map.last().second)) {
            player.seekTo(map.size - 1, map.last().second)
        }
    }
    private fun updatePreviewForTime(timeMs: Long) {
        seekPlayerWithTimelineMap(timeMs, activeTimelineMap)
        syncOverlayPreview(timeMs)
    }

    private fun syncOverlayPreview(timeMs: Long) {
        previewBinding.overlayManipulationView.visibility = android.view.View.VISIBLE
        previewBinding.overlayManipulationView.updatePreview(timeMs, project.tracks)
    }

    private fun updateUIDuration() {
        val durationMs =
                if (activeTimelineMap.isNotEmpty()) {
                    activeTimelineMap.sumOf { it.second }
                } else {
                    project.getDurationMs()
                }

        previewBinding.tvDuration.text = ViewUtils.formatTime(durationMs)

        // Toggle Empty Placeholder
        val hasClips = project.tracks.any { it.clips.isNotEmpty() }
        previewBinding.layoutEmptyPreview?.visibility = if (hasClips) View.GONE else View.VISIBLE
    }

    @OptIn(UnstableApi::class)
    private fun rebuildPlayerFromTimeline(seekTimeMs: Long = currentTimeMs) {
        try {
            val player = exoPlayer ?: return
            val wasPlaying = player.isPlaying
            val currentPos = if (seekTimeMs >= 0) seekTimeMs else player.currentPosition

            // 1. Get All Tracks
            val videoTracks = project.tracks.filter { it.type == TrackType.VIDEO }
            // Use first video track for primary map, but we'll merge all in player
            val primaryClips = videoTracks.getOrNull(0)?.clips.orEmpty().sortedBy { it.startTimeMs }

            val audioTracks = project.tracks.filter { it.type == TrackType.AUDIO }
            val videoAudioTracks = project.tracks.filter { it.type == TrackType.VIDEO_AUDIO }

            val maxDuration = project.getDurationMs()

            // 1.5. Pre-calculate Overlay Metadata
            // Map which overlays belong to which video clip for the EffectEngine
            val allOverlays =
                    project.tracks.filter { it.type == TrackType.OVERLAY }.flatMap { it.clips }
            val mainVideoClips = videoTracks.flatMap { it.clips }

            mainVideoClips.forEach { videoClip ->
                val videoStart = videoClip.startTimeMs
                val videoEnd = videoClip.endTimeMs

                val overlapping =
                        allOverlays.filter { overlay ->
                            val overlayStart = overlay.startTimeMs
                            val overlayEnd = overlay.endTimeMs
                            (overlayStart < videoEnd) && (overlayEnd > videoStart)
                        }

                if (overlapping.isNotEmpty()) {
                    videoClip.metadata["OVERLAYS"] = overlapping.joinToString(",") { it.id }
                } else {
                    videoClip.metadata.remove("OVERLAYS")
                }
            }

            // 2. Build Sources
            val sourcesToMerge = mutableListOf<androidx.media3.exoplayer.source.MediaSource>()

            // A. Video Sources (Supports multiple for stacking)
            videoTracks.forEach { track ->
                val clips = track.clips.sortedBy { it.startTimeMs }
                if (clips.isNotEmpty()) {
                    val videoSource =
                            buildTrackMediaSource(
                                    clips,
                                    isAudio = false,
                                    totalDurationMs = maxDuration
                            )
                    sourcesToMerge.add(videoSource)
                }
            }

            // B. Audio Sources (Background Music + Extracted Video Audio)
            (videoAudioTracks + audioTracks).forEach { audioTrack ->
                val filteredClips =
                        audioTrack.clips.sortedBy { it.startTimeMs }.filter audioClipFilterLoop@{
                                audioClip ->
                            val resolvedPath =
                                    com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(
                                            this,
                                            audioClip.filePath
                                    )
                            if (resolvedPath == null) return@audioClipFilterLoop false
                            val file = File(resolvedPath)
                            FFmpegTimelineUtils.hasAudioStream(file.absolutePath)
                        }
                if (filteredClips.isNotEmpty()) {
                    val audioSource =
                            buildTrackMediaSource(
                                    filteredClips,
                                    isAudio = true,
                                    totalDurationMs = maxDuration
                            )
                    sourcesToMerge.add(audioSource)
                }
            }

            // C. Overlay Tracks (Only for Video types, Text/Images are rendered by
            // OverlayManipulationView)
            val overlayTracks = project.tracks.filter { it.type == TrackType.OVERLAY }
            overlayTracks.forEach { track ->
                val videoClips =
                        track.clips.filter { it.type == ClipType.VIDEO }.sortedBy { it.startTimeMs }
                if (videoClips.isNotEmpty()) {
                    val overlaySource =
                            buildTrackMediaSource(
                                    videoClips,
                                    isAudio = false,
                                    totalDurationMs = maxDuration
                            )
                    sourcesToMerge.add(overlaySource)
                }
            }

            // 3. Merge All
            if (sourcesToMerge.isEmpty()) return

            val finalSource =
                    if (sourcesToMerge.size > 1) {
                        MergingMediaSource(*sourcesToMerge.toTypedArray())
                    } else {
                        sourcesToMerge[0]
                    }

            // 4. Set to Player and Restore State
            player.setMediaSource(finalSource)
            player.prepare()
            player.seekTo(currentPos)
            if (wasPlaying) {
                player.play()
            }

            rebuildTimelineMap(primaryClips)
            updateUIDuration()
            syncOverlayPreview(currentPos)
        } catch (e: Exception) {
            Log.e("TimelineEditor", "Error rebuilding player", e)
            Toast.makeText(this, "Playback Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun moveSelectedClipToFront() {
        (previewBinding.timelineView as? TimelineView)?.moveSelectedClipToFront()
        rebuildPlayerFromTimeline()
    }

    fun moveSelectedClipToBack() {
        (previewBinding.timelineView as? TimelineView)?.moveSelectedClipToBack()
        rebuildPlayerFromTimeline()
    }

    private fun rebuildTimelineMap(clips: List<TimelineClip>) {
        val map = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        clips.forEach { clip ->
            if (clip.startTimeMs > cursor) {
                val gap = clip.startTimeMs - cursor
                map.add(cursor to gap)
                cursor += gap
            }
            map.add(cursor to clip.durationMs)
            cursor += clip.durationMs
        }
        activeTimelineMap = map
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    @SuppressLint("UnsafeOptInUsageError")
    private fun buildTrackMediaSource(
            clips: List<TimelineClip>,
            isAudio: Boolean,
            totalDurationMs: Long = 0L
    ): androidx.media3.exoplayer.source.MediaSource {
        val sources = mutableListOf<androidx.media3.exoplayer.source.MediaSource>()
        val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
        var cursor = 0L

        clips.forEach { clip ->
            // GAP handling
            if (clip.startTimeMs > cursor) {
                val gapDuration = clip.startTimeMs - cursor
                if (isAudio) {
                    // Audio Gap -> Silence using generated silent WAV file
                    val silentFile = getSilentAudioFile()
                    val gapItem =
                            MediaItem.Builder()
                                    .setMediaId("GAP_AUDIO_$cursor")
                                    .setUri(Uri.fromFile(silentFile))
                                    .setClippingConfiguration(
                                            MediaItem.ClippingConfiguration.Builder()
                                                    .setEndPositionMs(gapDuration)
                                                    .build()
                                    )
                                    .build()
                    sources.add(factory.createMediaSource(gapItem))
                } else {
                    // Video Gap -> Black Image
                    val gapItem =
                            MediaItem.Builder()
                                    .setMediaId("GAP_$cursor")
                                    .setUri(Uri.fromFile(getBlackPlaceholderFile()))
                                    .setImageDurationMs(gapDuration)
                                    .build()
                    sources.add(factory.createMediaSource(gapItem))
                }
                cursor += gapDuration
            }

            // CLIP handling
            val resolvedPath =
                    com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(
                            this,
                            clip.filePath
                    )
            val finalPath = resolvedPath ?: clip.filePath
            val isRemote = finalPath.startsWith("http")

            if (!isRemote && !File(finalPath).exists()) {
                Log.e("TimelineEditor", "Missing file for clip: ${clip.id} at ${clip.filePath}")
                // Add a placeholder instead of failing
                val placeholderItem =
                        if (isAudio) {
                            MediaItem.Builder()
                                    .setMediaId("${clip.id}_MISSING")
                                    .setUri(Uri.fromFile(getSilentAudioFile()))
                                    .setClippingConfiguration(
                                            MediaItem.ClippingConfiguration.Builder()
                                                    .setEndPositionMs(clip.durationMs)
                                                    .build()
                                    )
                                    .build()
                        } else {
                            MediaItem.Builder()
                                    .setMediaId("${clip.id}_MISSING")
                                    .setUri(Uri.fromFile(getBlackPlaceholderFile()))
                                    .setImageDurationMs(clip.durationMs)
                                    .build()
                        }
                sources.add(factory.createMediaSource(placeholderItem))
                cursor += clip.durationMs
                return@forEach
            }

            val mediaItem =
                    MediaItem.Builder()
                            .setMediaId(clip.id)
                            .setUri(
                                    if (isRemote) Uri.parse(finalPath)
                                    else Uri.fromFile(File(finalPath))
                            )
                            .setClippingConfiguration(
                                    MediaItem.ClippingConfiguration.Builder()
                                            .setStartPositionMs(clip.sourceStartTimeMs)
                                            .setEndPositionMs(
                                                    clip.sourceStartTimeMs + clip.durationMs
                                            )
                                            .build()
                            )
                            .build()

            sources.add(factory.createMediaSource(mediaItem))
            cursor += clip.durationMs
        }

        // TAIL GAP handling (Ensures MergingMediaSource works correctly with same length inputs)
        if (totalDurationMs > cursor) {
            val tailGap = totalDurationMs - cursor
            if (isAudio) {
                val silentFile = getSilentAudioFile()
                val gapItem =
                        MediaItem.Builder()
                                .setMediaId("TAIL_GAP_AUDIO_$cursor")
                                .setUri(Uri.fromFile(silentFile))
                                .setClippingConfiguration(
                                        MediaItem.ClippingConfiguration.Builder()
                                                .setEndPositionMs(tailGap)
                                                .build()
                                )
                                .build()
                sources.add(factory.createMediaSource(gapItem))
            } else {
                val gapItem =
                        MediaItem.Builder()
                                .setMediaId("TAIL_GAP_$cursor")
                                .setUri(Uri.fromFile(getBlackPlaceholderFile()))
                                .setImageDurationMs(tailGap)
                                .build()
                sources.add(factory.createMediaSource(gapItem))
            }
        }

        // Handle empty track case by returning a dummy silence/black gap
        if (sources.isEmpty()) {
            val dummyDuration = if (totalDurationMs > 0) totalDurationMs else 1000L
            val dummyItem =
                    if (isAudio) {
                        MediaItem.Builder()
                                .setUri(Uri.fromFile(getSilentAudioFile()))
                                .setClippingConfiguration(
                                        MediaItem.ClippingConfiguration.Builder()
                                                .setEndPositionMs(dummyDuration)
                                                .build()
                                )
                                .build()
                    } else {
                        MediaItem.Builder()
                                .setUri(Uri.fromFile(getBlackPlaceholderFile()))
                                .setImageDurationMs(dummyDuration)
                                .build()
                    }
            return factory.createMediaSource(dummyItem)
        }

        val builder = ConcatenatingMediaSource2.Builder()
        sources.forEach { builder.add(it) }
        return builder.build()
    }

    private fun stepFrame(direction: Int) {
        exoPlayer?.pause() // Ensure pause when stepping
        val frameMs = 40L // Approx 25fps safe step
        var target = currentTimeMs + (direction * frameMs)
        val duration = project.getDurationMs()
        target = target.coerceIn(0, duration)

        currentTimeMs = target
        previewBinding.timelineView.seekTo(target)
        rebuildPlayerFromTimeline(target)
        updateUIDuration()
    }

    private fun getSilentAudioFile(): File {
        val file = File(cacheDir, "silence_10s.wav")
        if (file.exists()) return file

        try {
            // Generates a 10s silent WAV file
            val sampleRate = 44100
            val durationSec = 10
            val numSamples = sampleRate * durationSec
            val numChannels = 2 // Stereo
            val bytesPerSample = 2 // 16-bit
            val byteRate = sampleRate * numChannels * bytesPerSample
            val dataSize = numSamples * numChannels * bytesPerSample
            val totalSize = 36 + dataSize

            val header =
                    java.nio.ByteBuffer.allocate(44).apply {
                        order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        put("RIFF".toByteArray())
                        putInt(totalSize)
                        put("WAVE".toByteArray())
                        put("fmt ".toByteArray())
                        putInt(16) // Subchunk1Size
                        putShort(1) // AudioFormat
                        putShort(numChannels.toShort())
                        putInt(sampleRate)
                        putInt(byteRate)
                        putShort((numChannels * bytesPerSample).toShort())
                        putShort((8 * bytesPerSample).toShort())
                        put("data".toByteArray())
                        putInt(dataSize)
                    }

            java.io.FileOutputStream(file).use { out ->
                out.write(header.array())
                val buffer = ByteArray(4096)
                var written = 0
                while (written < dataSize) {
                    val count = kotlin.math.min(buffer.size, dataSize - written)
                    out.write(buffer, 0, count)
                    written += count
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return file
    }

    private fun buildClipSource(player: ExoPlayer, clip: TimelineClip) {
        player.clearMediaItems()

        val resolvedPath =
                com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(this, clip.filePath)
        val finalPath = resolvedPath ?: clip.filePath
        val isRemote = finalPath.startsWith("http")

        val mediaItem =
                MediaItem.Builder()
                        .setMediaId(clip.id)
                        .setUri(
                                if (isRemote) Uri.parse(finalPath)
                                else Uri.fromFile(File(finalPath))
                        )
                        .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                        .setStartPositionMs(clip.sourceStartTimeMs)
                                        .setEndPositionMs(clip.sourceStartTimeMs + clip.durationMs)
                                        .build()
                        )
                        .build()

        player.addMediaItem(mediaItem)
        player.prepare()
        player.seekTo(0L)

        previewBinding.tvDuration.text = ViewUtils.formatTime(clip.durationMs)
        previewBinding.tvTimeCode.text = "00:00"
    }
    private fun getBlackPlaceholderFile(): File {
        val file = File(cacheDir, "gap_pixel.png")
        if (file.exists()) return file

        val bitmap =
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)
        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
    private fun openExportScreen(path: String, name: String) {
        startActivity(
                Intent(this, ExportActivity::class.java)
                        .putExtra(ExportService.EXTRA_OUTPUT_PATH, path)
                        .putExtra(ExportService.EXTRA_PROJECT_NAME, name)
        )
    }

    private fun calculateExportDimensions(resolution: String, aspectRatio: String): Pair<Int, Int> {
        val res = if (resolution == "Original") getProjectMaxResolution() else resolution
        val baseHeight =
                when (res) {
                    "4K" -> 2160
                    "2K" -> 1440
                    "1080p" -> 1080
                    "720p" -> 720
                    "480p" -> 480
                    else -> 1080
                }
        val baseWidth =
                when (resolution) {
                    "4K" -> 3840
                    "2K" -> 2560
                    "1080p" -> 1920
                    "720p" -> 1280
                    "480p" -> 854
                    else -> 1920
                }

        val preset = ExportPreset.values().find { it.label == aspectRatio } ?: project.activePreset

        return when {
            preset == ExportPreset.ORIGINAL -> {
                val firstVideo =
                        project.tracks.flatMap { it.clips }.firstOrNull {
                            it.type == ClipType.VIDEO || it.type == ClipType.IMAGE
                        }
                if (firstVideo != null) {
                    val (w, h) = getVideoDimensions(firstVideo.filePath)
                    val ratio = w.toFloat() / h.toFloat()
                    (baseHeight * ratio).toInt() to baseHeight
                } else baseWidth to baseHeight
            }
            preset.aspectWidth == preset.aspectHeight -> {
                val size = minOf(baseWidth, baseHeight)
                size to size
            }
            preset.aspectWidth > preset.aspectHeight -> {
                // Landscape
                val width = baseWidth
                val height = (width * preset.aspectHeight / preset.aspectWidth)
                width to height
            }
            else -> {
                // Portrait
                val height = baseHeight
                val width = (height * preset.aspectWidth / preset.aspectHeight)
                width to height
            }
        }.let { (w, h) ->
            // 🚀 PARITY FIX: Standard encoders (h264) require EVEN dimensions
            (w / 2 * 2) to (h / 2 * 2)
        }
    }

    private fun getVideoDimensions(path: String): Pair<Int, Int> {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val width =
                    retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull()
                            ?: 1920
            val height =
                    retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull()
                            ?: 1080
            retriever.release()
            width to height
        } catch (e: Exception) {
            1920 to 1080
        }
    }

    private fun setVideoAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val aspectRatio = videoWidth.toFloat() / videoHeight.toFloat()

        previewBinding.previewContainer.post {
            val maxW = previewBinding.previewContainer.width?.toFloat() ?: 0f
            if (maxW == 0f) return@post

            // We follow the same logic as other editors or let 'fit' handle the padding,
            // but we can adjust the guideline if we want to snap.
            // For now, ensuring 'fit' is used and adding this for consistency/extension.
            Log.d(
                    "TimelineEditor",
                    "Setting aspect ratio: $aspectRatio for dims: ${videoWidth}x${videoHeight}"
            )
        }
    }

    private fun resolveMediaToLocalFile(path: String, type: ClipType): String {
        return AssetUtils.getCachedAssetPath(this, path) ?: path
    }

    private fun addMediaToTimeline(path: String, type: ClipType, duration: Long) {
        // Resolve abstract paths (emoji://, res://) to actual files for FFmpeg/Rendering
        val resolvedPath = resolveMediaToLocalFile(path, type)
        val start =
                if (currentAdditionMode == AdditionMode.APPEND) {
                    // Find end of main video track for appending
                    val videoTrack = project.tracks.find { it.type == TrackType.VIDEO }
                    videoTrack?.clips?.lastOrNull()?.endTimeMs ?: 0L
                } else {
                    // Use current playhead position for overlays
                    currentTimeMs
                }

        // Determine track type: Audio goes to AUDIO, everything else to OVERLAY (for this unified
        // call)
        // or to VIDEO if it's a main media append.
        val targetTrackType =
                when {
                    type == ClipType.AUDIO -> TrackType.AUDIO
                    currentAdditionMode == AdditionMode.APPEND &&
                            (type == ClipType.VIDEO || type == ClipType.IMAGE) -> TrackType.VIDEO
                    else -> TrackType.OVERLAY
                }

        val track = findSmartTrack(targetTrackType, start, duration)
        val clip =
                TimelineClip(
                        id = java.util.UUID.randomUUID().toString(),
                        filePath = resolvedPath,
                        startTimeMs = start,
                        durationMs = duration,
                        sourceStartTimeMs = 0L,
                        sourceDurationMs = duration,
                        type = type
                )

        track.clips.add(clip)
        track.clips.sortBy { it.startTimeMs }

        // Refresh UI
        rebuildPlayerFromTimeline(currentTimeMs)
        previewBinding.timelineView.invalidate()
        // Trigger generic data changed if needed (TimelineView should handle invalidate)
        // (previewBinding.timelineView as? TimelineView)?.notifyDataChanged()
        previewBinding.timelineView.animateClip(clip.id)

        android.widget.Toast.makeText(
                        this,
                        "Added ${type.name} to timeline",
                        android.widget.Toast.LENGTH_SHORT
                )
                .show()
    }

    /**
     * Finds a track of [type] that has free space at [startTime]
     * - [startTime+duration]. If no such track exists, creates a new one. Keeps Video track
     * singular if desired, or allows multi-track video too.
     */
    private fun findSmartTrack(type: TrackType, startTime: Long, duration: Long): TimelineTrack {
        // For VIDEO, we might want to stick to single track for now unless we support
        // Picture-in-Picture properly
        // BUT user asked for "stacking", so we enable it for all compatible types.

        val candidates = project.tracks.filter { it.type == type }

        for (track in candidates) {
            val hasCollision =
                    track.clips.any { clip ->
                        val startA = clip.startTimeMs
                        val endA = clip.startTimeMs + clip.durationMs
                        val startB = startTime
                        val endB = startTime + duration

                        // Overlap check
                        (startA < endB) && (endA > startB)
                    }

            if (!hasCollision) {
                return track
            }
        }

        // No free space or no track found, create a new one for OVERLAY
        if (type == TrackType.OVERLAY) {
            val newTrack =
                    TimelineTrack(id = UUID.randomUUID().toString(), type = TrackType.OVERLAY)
            project.tracks.add(newTrack)
            return newTrack
        }

        // For VIDEO, we usually stick to the first video track in basic mode,
        // unless multi-track is required.
        // If no existing track of the specified type is found, create a new one.
        return candidates.firstOrNull()
                ?: TimelineTrack(id = UUID.randomUUID().toString(), type = type).also {
                    project.tracks.add(it)
                }
    }

    private fun getOrCreateTrack(type: TrackType): TimelineTrack {
        return findSmartTrack(type, currentTimeMs, 0)
    }
    private fun showSpeedDialog() {
        val clip = selectedClip ?: return

        // Create Option
        val speedOption =
                com.example.videoeditorapp.ui.editor.EditorOption(
                        id = "speed",
                        iconRes = R.drawable.ic_speed,
                        label = "Playback Speed",
                        value = clip.playbackSpeed,
                        minValue = 0.2f,
                        maxValue = 4.0f,
                        isSelected = true,
                        type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                        controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                )

        showUnifiedEditor(title = "Speed Control", options = listOf(speedOption)) { option, value ->
            // Apply Change
            clip.playbackSpeed = value

            // Rebuild Player
            // Note: ExoPlayer supports speed change on the fly
            exoPlayer?.setPlaybackSpeed(value)

            // Sync with timeline
            rebuildPlayerFromTimeline(currentTimeMs)

            previewBinding.timelineView?.invalidate()

            // Update Toolbar visuals
            updateLevelVisuals()
        }
    }

    private fun reverseClip(clip: TimelineClip) {
        saveHistory()
        if (clip.metadata["REVERSED"] == "true") {
            clip.metadata.remove("REVERSED")
            Toast.makeText(this, "Reverse Disabled", Toast.LENGTH_SHORT).show()
        } else {
            clip.metadata["REVERSED"] = "true"
            Toast.makeText(this, "Reverse Enabled", Toast.LENGTH_SHORT).show()
        }
        rebuildPlayerFromTimeline(currentTimeMs)
        updateToolUI()
        previewBinding.timelineView.invalidate()
    }

    private fun showFadeDialog(clipToEdit: TimelineClip? = null) {
        val clip = clipToEdit ?: selectedClip ?: return
        val options = mutableListOf<com.example.videoeditorapp.ui.editor.EditorOption>()

        // Video Fades
        if (clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE) {
            val fadeIn = clip.metadata["VIDEO_FADE_IN"]?.toFloatOrNull() ?: 0f
            val fadeOut = clip.metadata["VIDEO_FADE_OUT"]?.toFloatOrNull() ?: 0f

            options.add(
                    com.example.videoeditorapp.ui.editor.EditorOption(
                            "video_fade_in",
                            R.drawable.ic_fade,
                            "Video Fade In (ms)",
                            fadeIn,
                            0f,
                            2000f,
                            type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                            controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                    )
            )
            options.add(
                    com.example.videoeditorapp.ui.editor.EditorOption(
                            "video_fade_out",
                            R.drawable.ic_fade,
                            "Video Fade Out (ms)",
                            fadeOut,
                            0f,
                            2000f,
                            type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                            controlType = com.example.videoeditorapp.ui.editor.ControlType.WHEEL
                    )
            )
        }

        // Audio Fades
        if (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO) {
            val afFadeIn = clip.metadata["AUDIO_FADE_IN"]?.toFloatOrNull() ?: 0f
            val afFadeOut = clip.metadata["AUDIO_FADE_OUT"]?.toFloatOrNull() ?: 0f

            options.add(
                    com.example.videoeditorapp.ui.editor.EditorOption(
                            "audio_fade_in",
                            R.drawable.ic_volume_up,
                            "Audio Fade In (ms)",
                            afFadeIn,
                            0f,
                            2000f,
                            type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                            controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                    )
            )
            options.add(
                    com.example.videoeditorapp.ui.editor.EditorOption(
                            "audio_fade_out",
                            R.drawable.ic_volume_off,
                            "Audio Fade Out (ms)",
                            afFadeOut,
                            0f,
                            2000f,
                            type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                            controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                    )
            )
        }

        if (options.isEmpty()) {
            Toast.makeText(this, "Fades not available for this clip type", Toast.LENGTH_SHORT)
                    .show()
            return
        }

        showUnifiedEditor("Transitions & Fades", options) { option, value ->
            when (option.id) {
                "video_fade_in" -> clip.metadata["VIDEO_FADE_IN"] = value.toLong().toString()
                "video_fade_out" -> clip.metadata["VIDEO_FADE_OUT"] = value.toLong().toString()
                "audio_fade_in" -> clip.metadata["AUDIO_FADE_IN"] = value.toLong().toString()
                "audio_fade_out" -> clip.metadata["AUDIO_FADE_OUT"] = value.toLong().toString()
            }
        }
    }

    private fun showOpacityDialog(clipToEdit: TimelineClip? = null) {
        val clip = clipToEdit ?: selectedClip ?: return

        val opacityOption =
                com.example.videoeditorapp.ui.editor.EditorOption(
                        id = "opacity",
                        iconRes = R.drawable.ic_opacity,
                        label = "Clip Opacity",
                        value = clip.overlayOpacity,
                        minValue = 0.0f,
                        maxValue = 1.0f,
                        isSelected = true,
                        type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                        controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                )

        showUnifiedEditor(title = "Opacity Control", options = listOf(opacityOption)) { _, value ->
            clip.overlayOpacity = value
            rebuildPlayerFromTimeline(currentTimeMs)
            updateToolUI()
        }
    }

    private fun showVolumeDialog() {
        val clip = selectedClip ?: return

        val volumeOption =
                com.example.videoeditorapp.ui.editor.EditorOption(
                        id = "volume",
                        iconRes = R.drawable.ic_volume_up,
                        label = "Clip Volume",
                        value = clip.audioVolume,
                        minValue = 0f,
                        maxValue = 2.0f,
                        isSelected = true,
                        type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                        controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                )

        showUnifiedEditor(title = "Volume Control", options = listOf(volumeOption)) { option, value
            ->
            clip.audioVolume = value
            rebuildPlayerFromTimeline(currentTimeMs)
            updateToolUI()
        }
    }

    private fun showCropDialog() {
        val clip = selectedClip ?: return
        val currentPreset = clip.metadata["CROP_PRESET"] ?: "ORIGINAL"

        val options =
                listOf(
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "ORIGINAL",
                                R.drawable.ic_crop,
                                "Original",
                                0f,
                                isSelected = currentPreset == "ORIGINAL",
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "1:1",
                                R.drawable.ic_crop,
                                "1:1 (Square)",
                                0f,
                                isSelected = currentPreset == "1:1",
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "16:9",
                                R.drawable.ic_crop,
                                "16:9 (HD)",
                                0f,
                                isSelected = currentPreset == "16:9",
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "9:16",
                                R.drawable.ic_crop,
                                "9:16 (Reel)",
                                0f,
                                isSelected = currentPreset == "9:16",
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "4:5",
                                R.drawable.ic_crop,
                                "4:5 (Post)",
                                0f,
                                isSelected = currentPreset == "4:5",
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        )
                )

        showUnifiedEditor("Crop Aspect Ratio", options) { option, _ ->
            if (option.id == "ORIGINAL") {
                clip.metadata.remove("CROP_PRESET")
            } else {
                clip.metadata["CROP_PRESET"] = option.id
            }
            rebuildPlayerFromTimeline(currentTimeMs)
            updateToolUI()
        }
    }
    private fun clearSelection() {
        selectedClip = null
        previewBinding.timelineView?.invalidate()
        updateBottomBarVisibility()
    }
    private fun updateBottomBarVisibility() {
        val container = previewBinding.bottomNavWrapper

        container.post {
            val fullHeight = container.height.toFloat() ?: 0f
            if (fullHeight == 0f) return@post

            if (selectedClip != null) {
                val clip = selectedClip!!

                // 🍏 Contextual Tab Visibility
                previewBinding.tabEdit.visibility = View.VISIBLE // Always show primary edit

                //                previewBinding.tabAudio?.visibility =
                //                        if (clip.type == ClipType.VIDEO || clip.type ==
                // ClipType.AUDIO) View.VISIBLE
                //                        else View.GONE
                //                previewBinding.tabText.visibility =
                //                        if (clip.type == ClipType.TEXT) View.VISIBLE else
                // View.GONE
                //                previewBinding.tabOverlay?.visibility =
                //                        if (clip.type == ClipType.VIDEO) View.VISIBLE else
                // View.GONE
                previewBinding.tabEffects.visibility =
                        if (clip.type == ClipType.VIDEO) View.VISIBLE else View.GONE
                previewBinding.tabFilters.visibility =
                        if (clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE) View.VISIBLE
                        else View.GONE
                //                previewBinding.tabAdjust?.visibility =
                //                        if (clip.type == ClipType.VIDEO || clip.type ==
                // ClipType.IMAGE) View.VISIBLE
                //                        else View.GONE
                previewBinding.tabReverse.visibility =
                        if (clip.type == ClipType.VIDEO) View.VISIBLE else View.GONE
                previewBinding.tabOpacity.visibility =
                        if (clip.type != ClipType.AUDIO) View.VISIBLE else View.GONE
                previewBinding.tabFade.visibility =
                        if (clip.type == ClipType.AUDIO || clip.type == ClipType.VIDEO) View.VISIBLE
                        else View.GONE
                previewBinding.tabHub.visibility = View.VISIBLE

                if (container.visibility != View.VISIBLE) {
                    container.translationY = fullHeight
                    container.alpha = 0f
                    container.visibility = View.VISIBLE
                }
                container.animate().translationY(0f).alpha(1f).setDuration(220).start()
            } else {
                // 🍏 Show all tabs if NOTHING selected (Main Addition Mode)
                previewBinding.tabEdit.visibility = View.VISIBLE
                //                previewBinding.tabAudio?.visibility = View.VISIBLE
                //                previewBinding.tabText.visibility = View.VISIBLE
                //                previewBinding.tabOverlay?.visibility = View.VISIBLE
                previewBinding.tabEffects.visibility = View.VISIBLE
                previewBinding.tabFilters.visibility = View.VISIBLE
                //                previewBinding.tabAdjust?.visibility = View.VISIBLE
                previewBinding.tabReverse.visibility = View.VISIBLE
                previewBinding.tabOpacity.visibility = View.VISIBLE
                previewBinding.tabFade.visibility = View.VISIBLE
                previewBinding.tabHub.visibility = View.VISIBLE

                if (container.visibility != View.VISIBLE) {
                    container.translationY = fullHeight
                    container.alpha = 0f
                    container.visibility = View.VISIBLE
                }
                container.animate().translationY(0f).alpha(1f).setDuration(220).start()
            }
        }
    }
    private fun addTextClip(text: String, size: Float, shadow: Float) {
        val duration = 3000L
        val track = findSmartTrack(TrackType.OVERLAY, currentTimeMs, duration)

        val clip =
                TimelineClip(
                                filePath = "TEXT_CLIP",
                                startTimeMs = currentTimeMs,
                                durationMs = 3000L,
                                sourceDurationMs = 3000L,
                                type = ClipType.TEXT
                        )
                        .apply {
                            textSettings["TEXT"] = text
                            textSettings["SIZE"] = size.toString()
                            textSettings["SHADOW"] = shadow.toString()
                            textSettings["COLOR"] = "#FFFFFF"
                        }

        track.clips.add(clip)
        previewBinding.timelineView.animateClip(clip.id)
        rebuildPlayerFromTimeline(currentTimeMs)
        updateUIDuration()
        updateEditorVisibility()
        previewBinding.timelineView.invalidate()
    }
    private fun showTextPropertiesDialog(existingClip: TimelineClip? = null) {
        if (existingClip == null) {
            // New Text Clip Creation Flow
            val inputBinding = DialogInputBinding.inflate(layoutInflater)
            inputBinding.dialogTitle.text = "ADD TEXT OVERLAY"
            inputBinding.tilInput.hint = "TYPE YOUR TEXT..."

            val dialog =
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setView(inputBinding.root)
                            .setCancelable(true)
                            .create()

            inputBinding.btnConfirm.setOnClickListener {
                val text = inputBinding.etInput.text.toString().ifEmpty { "MODERN TEXT" }
                addTextClip(text, 50f, 2f)
                dialog.dismiss()
            }
            inputBinding.btnCancel.setOnClickListener { dialog.dismiss() }

            dialog.show()
            return
        }

        val clip = existingClip

        fun getSetting(key: String, def: Float): Float {
            return clip.textSettings[key]?.toFloatOrNull() ?: def
        }

        val currentAlign = clip.textSettings["ALIGN"] ?: "CENTER"
        val alignOption =
                com.example.videoeditorapp.ui.editor.EditorOption(
                        "ALIGN",
                        when (currentAlign) {
                            "LEFT" -> R.drawable.ic_align_left
                            "RIGHT" -> R.drawable.ic_align_right
                            else -> R.drawable.ic_align_center
                        },
                        "Align: ${currentAlign.lowercase().capitalize()}",
                        type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                )

        val options =
                listOf(
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "SIZE",
                                R.drawable.ic_title,
                                "Size",
                                getSetting("SIZE", 40f),
                                10f,
                                200f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        alignOption,
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "COLOR",
                                R.drawable.ic_color_lens,
                                "Color",
                                0f,
                                0f,
                                0f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "ANIM_FADE",
                                R.drawable.ic_auto_awesome,
                                "Fade In",
                                if (getSetting("ANIM_FADE", 0f) > 0) 1f else 0f,
                                0f,
                                1f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "ANIM_SLIDE",
                                R.drawable.ic_fast_forward,
                                "Slide Effect",
                                0f,
                                0f,
                                0f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "EDIT_TEXT",
                                R.drawable.ic_edit,
                                "Edit Text",
                                0f,
                                0f,
                                0f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        )
                )

        showUnifiedEditor("Motion Text // FX", options) { option, value ->
            when (option.id) {
                "SIZE" -> clip.textSettings["SIZE"] = value.toString()
                "ANIM_FADE" -> clip.textSettings["ANIM_FADE"] = if (value > 0.5f) "1000" else "0"
                "ANIM_SLIDE" -> {
                    val current = clip.textSettings["ANIM_SLIDE"] ?: "NONE"
                    val next =
                            when (current) {
                                "NONE" -> "LEFT_TO_RIGHT"
                                "LEFT_TO_RIGHT" -> "BOTTOM_UP"
                                else -> "NONE"
                            }
                    clip.textSettings["ANIM_SLIDE"] = next
                    option.label = "Slide: $next"
                    Toast.makeText(this, "Motion: $next", Toast.LENGTH_SHORT).show()
                }
                "ALIGN" -> {
                    val next =
                            when (clip.textSettings["ALIGN"]) {
                                "LEFT" -> "CENTER"
                                "CENTER" -> "RIGHT"
                                else -> "LEFT"
                            }
                    clip.textSettings["ALIGN"] = next
                    option.label = "Align: ${next.lowercase().capitalize()}"
                    option.iconRes =
                            when (next) {
                                "LEFT" -> R.drawable.ic_align_left
                                "RIGHT" -> R.drawable.ic_align_right
                                else -> R.drawable.ic_align_center
                            }
                }
                "COLOR" -> showColorPickerDialog(clip)
                "EDIT_TEXT" -> showTextInputDialog(clip)
            }
            rebuildPlayerFromTimeline(currentTimeMs)
        }
    }

    private fun showColorPickerDialog(clip: TimelineClip) {
        val dialogBinding =
                com.example.videoeditorapp.databinding.DialogColorPickerBinding.inflate(
                        layoutInflater
                )
        val dialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setView(dialogBinding.root)
                        .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val currentColorHex = clip.textSettings["COLOR"] ?: "#FFFFFF"
        var currentColor =
                try {
                    Color.parseColor(currentColorHex)
                } catch (e: Exception) {
                    Color.WHITE
                }

        fun updateUI(color: Int, fromUser: Boolean = false) {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            if (!fromUser) {
                dialogBinding.seekRedDialog.progress = r
                dialogBinding.seekGreenDialog.progress = g
                dialogBinding.seekBlueDialog.progress = b
            }

            dialogBinding.tvRedValue.text = r.toString()
            dialogBinding.tvGreenValue.text = g.toString()
            dialogBinding.tvBlueValue.text = b.toString()

            dialogBinding.viewPreview.setBackgroundColor(color)
            val hex = String.format("#%02X%02X%02X", r, g, b)
            if (!fromUser) {
                dialogBinding.etHex.setText(hex)
            }
        }

        updateUI(currentColor)

        val changeListener =
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            p0: android.widget.SeekBar?,
                            p1: Int,
                            p2: Boolean
                    ) {
                        if (p2) {
                            val r = dialogBinding.seekRedDialog.progress
                            val g = dialogBinding.seekGreenDialog.progress
                            val b = dialogBinding.seekBlueDialog.progress
                            currentColor = Color.rgb(r, g, b)
                            updateUI(currentColor, true)
                        }
                    }
                    override fun onStartTrackingTouch(p0: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(p0: android.widget.SeekBar?) {}
                }

        dialogBinding.seekRedDialog.setOnSeekBarChangeListener(changeListener)
        dialogBinding.seekGreenDialog.setOnSeekBarChangeListener(changeListener)
        dialogBinding.seekBlueDialog.setOnSeekBarChangeListener(changeListener)

        dialogBinding.btnDone.setOnClickListener {
            val r = dialogBinding.seekRedDialog.progress
            val g = dialogBinding.seekGreenDialog.progress
            val b = dialogBinding.seekBlueDialog.progress
            val finalHex = String.format("#%02X%02X%02X", r, g, b)
            clip.textSettings["COLOR"] = finalHex
            rebuildPlayerFromTimeline(currentTimeMs)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showOverlayPropertiesDialog(clip: TimelineClip) {
        val options =
                listOf(
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "OPACITY",
                                R.drawable.ic_visibility,
                                "Opacity",
                                clip.overlayOpacity,
                                0f,
                                1f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "SCALE",
                                R.drawable.ic_zoom_in,
                                "Scale",
                                clip.overlayScale,
                                0.1f,
                                5.0f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                                controlType = com.example.videoeditorapp.ui.editor.ControlType.WHEEL
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "ROTATION",
                                R.drawable.ic_rotate_right,
                                "Rotate",
                                clip.overlayRotation,
                                0f,
                                360f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                                controlType = com.example.videoeditorapp.ui.editor.ControlType.WHEEL
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "FILTERS",
                                R.drawable.ic_auto_awesome,
                                "Filters",
                                0f,
                                0f,
                                0f,
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        )
                )

        showUnifiedEditor("Overlay Options", options) { option, value ->
            when (option.id) {
                "OPACITY" -> clip.overlayOpacity = value
                "SCALE" -> clip.overlayScale = value
                "ROTATION" -> clip.overlayRotation = value
                "FILTERS" -> showFiltersPicker()
            }
            // Real-time update logic needed for overlay preview?
            // rebuildPlayerFromTimeline handles it but might be heavy for sliders.
            // Ideally we manipulate the view directly or throttle.
            // For now, rebuild is safe.
            rebuildPlayerFromTimeline(currentTimeMs)
            previewBinding.timelineView?.invalidate()
        }
    }

    private fun showTextInputDialog(clip: TimelineClip) {
        val input = android.widget.EditText(this)
        input.setText(clip.textSettings["TEXT"] ?: "")
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Edit Text")
                .setView(input)
                .setPositiveButton("Done") { _, _ ->
                    clip.textSettings["TEXT"] = input.text.toString()
                    rebuildPlayerFromTimeline(currentTimeMs)
                }
                .show()
    }

    private fun showAudioMixerDialog(clip: TimelineClip) {

        val dlg = com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(layoutInflater)

        dlg.tvTitle.text = "Audio Special Effects"
        dlg.tvMessage.visibility = View.GONE

        val container = dlg.tvMessage.parent as LinearLayout

        var dialog: androidx.appcompat.app.AlertDialog? = null

        dlg.btnPrimary.visibility = View.GONE
        dlg.btnSecondary.visibility = View.VISIBLE
        dlg.btnSecondary.text = "Close"
        dlg.btnSecondary.setOnClickListener { dialog?.dismiss() }

        // ---------------------------------------------------------
        // ECHO SWITCH
        // ---------------------------------------------------------
        val echoSwitch =
                com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                    text = "Echo"
                    isChecked =
                            clip.effects.any {
                                it.type == "ECHO" && it.category == EffectCategory.AUDIO
                            }
                    setPadding(0, 16, 0, 16)
                }

        echoSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (clip.effects.none { it.type == "ECHO" }) {
                    clip.effects.add(TimelineEffect(type = "ECHO", category = EffectCategory.AUDIO))
                }
            } else {
                clip.effects.removeAll { it.type == "ECHO" }
            }
            rebuildPlayerFromTimeline(currentTimeMs)
        }

        container.addView(echoSwitch)

        // ---------------------------------------------------------
        // REVERB SWITCH
        // ---------------------------------------------------------
        val reverbSwitch =
                com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                    text = "Reverb"
                    isChecked =
                            clip.effects.any {
                                it.type == "REVERB" && it.category == EffectCategory.AUDIO
                            }
                    setPadding(0, 16, 0, 16)
                }

        reverbSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (clip.effects.none { it.type == "REVERB" }) {
                    clip.effects.add(
                            TimelineEffect(type = "REVERB", category = EffectCategory.AUDIO)
                    )
                }
            } else {
                clip.effects.removeAll { it.type == "REVERB" }
            }
            rebuildPlayerFromTimeline(currentTimeMs)
        }

        container.addView(reverbSwitch)

        // ---------------------------------------------------------
        // RESET BUTTON
        // ---------------------------------------------------------
        val resetBtn =
                com.google.android.material.button.MaterialButton(this).apply {
                    text = "Reset Audio Effects"
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { topMargin = 24 }
                }

        resetBtn.setOnClickListener {
            clip.effects.removeAll { it.category == EffectCategory.AUDIO }
            echoSwitch.isChecked = false
            reverbSwitch.isChecked = false
            rebuildPlayerFromTimeline(currentTimeMs)
        }

        container.addView(resetBtn)

        // ---------------------------------------------------------
        // Create & Show Dialog
        // ---------------------------------------------------------
        dialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setView(dlg.root)
                        .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
    private fun addAssetToTimeline(path: String, type: String?, category: String?) {
        val file = File(path)
        if (!file.exists()) return

        // 🍏 Handle Effects/Presets (Apply to selection)
        if (type == "EFFECT_PRESET") {
            val clip = selectedClip
            if (clip == null) {
                Toast.makeText(this, "Please select a clip to apply effect", Toast.LENGTH_LONG)
                        .show()
                return
            }
            saveHistory()
            applyPresetToClip(clip, path)
            rebuildPlayerFromTimeline(currentTimeMs)
            previewBinding.timelineView.invalidate()
            Toast.makeText(this, "Applied preset to selected clip", Toast.LENGTH_SHORT).show()
            return
        }

        saveHistory()

        val clipType =
                when (category) {
                    "MUSIC", "SFX" -> ClipType.AUDIO
                    "STICKERS" -> ClipType.STICKER
                    else -> ClipType.VIDEO
                }

        val duration =
                if (clipType == ClipType.AUDIO || clipType == ClipType.VIDEO) {
                    getMediaDuration(path)
                } else {
                    DEFAULT_IMAGE_DURATION_MS
                }

        val clip =
                TimelineClip(
                        id = UUID.randomUUID().toString(),
                        filePath = path,
                        type = clipType,
                        startTimeMs = currentTimeMs,
                        durationMs = duration,
                        sourceStartTimeMs = 0,
                        sourceDurationMs = duration,
                        metadata = mutableMapOf("FROM_HUB" to "true") // 🍏 Asset Store source tag
                )

        // Find appropriate track
        val trackType =
                when (clipType) {
                    ClipType.VIDEO -> TrackType.VIDEO
                    ClipType.AUDIO -> TrackType.AUDIO
                    ClipType.STICKER, ClipType.IMAGE, ClipType.TEXT -> TrackType.OVERLAY
                    else -> TrackType.VIDEO
                }

        val track = findSmartTrack(trackType, currentTimeMs, duration)
        track.clips.add(clip)

        // 🔗 AUTO-LINK AUDIO for Video Clips
        if (clipType == ClipType.VIDEO &&
                        com.example.videoeditorapp.model.timeline.FFmpegTimelineUtils
                                .hasAudioStream(path)
        ) {
            val audioClip =
                    clip.copy(
                            id = UUID.randomUUID().toString(),
                            type = ClipType.AUDIO,
                            // Linked metadata is implicit if exact match, but we can tag it
                            )
            val audioTrack = findSmartTrack(TrackType.VIDEO_AUDIO, currentTimeMs, duration)
            audioTrack.clips.add(audioClip)
            audioTrack.clips.sortBy { it.startTimeMs }
        }
        previewBinding.timelineView.animateClip(clip.id)

        (previewBinding.timelineView as? TimelineView)?.notifyDataChanged()
        rebuildPlayerFromTimeline(currentTimeMs)
        Toast.makeText(this, "Added ${file.name} to timeline", Toast.LENGTH_SHORT).show()
    }

    private fun applyPresetToClip(clip: TimelineClip, path: String) {
        // Placeholder for JSON preset parsing
        // For now, we'll map common filenames to stub effects
        val fileName = File(path).name.lowercase()
        when {
            fileName.contains("glitch") -> {
                clip.effects.add(TimelineEffect(type = "GLITCH", intensity = 0.8f))
            }
            fileName.contains("grain") -> {
                clip.effects.add(TimelineEffect(type = "GRAIN", intensity = 0.5f))
            }
            fileName.contains("vignette") -> {
                clip.effects.add(TimelineEffect(type = "VIGNETTE", intensity = 0.7f))
            }
            else -> {
                // Default generic effect
                clip.effects.add(TimelineEffect(type = "PRESET_FX", intensity = 1.0f))
            }
        }
    }

    private fun getMediaDuration(path: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            time?.toLong() ?: DEFAULT_IMAGE_DURATION_MS
        } catch (e: Exception) {
            DEFAULT_IMAGE_DURATION_MS
        }
    }

    private fun copyFileToPersistent(file: File, fileName: String): String? {
        return try {
            val destFile = File(getExternalFilesDir(null), "media/$fileName")
            destFile.parentFile?.mkdirs()
            file.inputStream().use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun copyUriToPersistentFile(
            uri: Uri,
            preferredName: String? = null,
            subDir: String? = null
    ): String? =
            withContext(Dispatchers.IO) {
                try {
                    val extension =
                            MimeTypeMap.getSingleton()
                                    .getExtensionFromMimeType(contentResolver.getType(uri))
                                    ?: "tmp"
                    val fileName =
                            preferredName ?: "imported_${System.currentTimeMillis()}.$extension"

                    val baseDir =
                            com.example.videoeditorapp.utils.StorageManager.getImportedMediaDir(
                                    this@TimelineTemplateEditorActivity
                            )
                    val parentDir = if (subDir != null) File(baseDir, subDir) else baseDir
                    val destFile = File(parentDir, fileName)
                    destFile.parentFile?.mkdirs()

                    contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    destFile.absolutePath
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

    private fun showPropertySheet() {
        val clip = selectedClip ?: return

        val dlg = com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(layoutInflater)

        dlg.tvTitle.text = "Clip Properties"
        dlg.tvMessage.text = ""

        val container = dlg.tvMessage.parent as LinearLayout
        dlg.tvMessage.visibility = View.GONE

        var dialog: androidx.appcompat.app.AlertDialog? = null

        // Hide primary button
        dlg.btnPrimary.visibility = View.GONE

        // Secondary button becomes "Close"
        dlg.btnSecondary.visibility = View.VISIBLE
        dlg.btnSecondary.text = "Close"
        dlg.btnSecondary.setOnClickListener { dialog?.dismiss() }

        // ---------------------------------------------------------
        // SCALE CONTROL
        // ---------------------------------------------------------
        val scaleTitle =
                TextView(this).apply {
                    text = "Scale"
                    textSize = 16f
                    setTextColor(getColor(android.R.color.white))
                    setPadding(0, 20, 0, 6)
                }
        container.addView(scaleTitle)

        val scaleSlider =
                com.google.android.material.slider.Slider(this).apply {
                    valueFrom = 0.2f
                    valueTo = 3.0f
                    stepSize = 0.1f
                    value = clip.overlayScale
                }
        scaleSlider.addOnChangeListener { _, value, _ ->
            clip.overlayScale = value
            rebuildPlayerFromTimeline(currentTimeMs)
            previewBinding.timelineView.invalidate()
        }
        container.addView(scaleSlider)

        // ---------------------------------------------------------
        // OPACITY CONTROL
        // ---------------------------------------------------------
        val opacityTitle =
                TextView(this).apply {
                    text = "Opacity"
                    textSize = 16f
                    setTextColor(getColor(android.R.color.white))
                    setPadding(0, 20, 0, 6)
                }
        container.addView(opacityTitle)

        val opacitySlider =
                com.google.android.material.slider.Slider(this).apply {
                    valueFrom = 0.0f
                    valueTo = 1.0f
                    stepSize = 0.05f
                    value = clip.overlayOpacity
                }
        opacitySlider.addOnChangeListener { _, value, _ ->
            clip.overlayOpacity = value
            rebuildPlayerFromTimeline(currentTimeMs)
            previewBinding.timelineView?.invalidate()
        }
        container.addView(opacitySlider)

        // ---------------------------------------------------------
        // EDIT TEXT BUTTON (only if clip is text)
        // ---------------------------------------------------------
        if (clip.type == ClipType.TEXT) {
            val editTextBtn =
                    com.google.android.material.button.MaterialButton(this).apply {
                        text = "Edit Text"
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply { topMargin = 24 }
                    }
            editTextBtn.setOnClickListener {
                dialog?.dismiss()
                showTextPropertiesDialog(clip)
            }
            container.addView(editTextBtn)
        }

        // ---------------------------------------------------------
        // Create Dialog
        // ---------------------------------------------------------
        dialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setView(dlg.root)
                        .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showAdjustPanel() {
        val clip = selectedClip ?: return

        fun getEffectValue(type: String, def: Float = 1.0f): Float {
            return clip.effects.find { it.type == type }?.intensity ?: def
        }

        val adjustmentOptions =
                listOf(
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "EXPOSURE",
                                R.drawable.ic_adjust,
                                "Exposure",
                                getEffectValue("EXPOSURE"),
                                0.0f,
                                2.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "BRIGHTNESS",
                                R.drawable.ic_adjust,
                                "Brightness",
                                getEffectValue("BRIGHTNESS"),
                                0.0f,
                                2.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "CONTRAST",
                                R.drawable.ic_adjust,
                                "Contrast",
                                getEffectValue("CONTRAST"),
                                0.0f,
                                2.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "SATURATION",
                                R.drawable.ic_adjust,
                                "Saturation",
                                getEffectValue("SATURATION"),
                                0.0f,
                                2.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "VIGNETTE",
                                R.drawable.ic_adjust,
                                "Vignette",
                                getEffectValue("VIGNETTE", 0f),
                                0.0f,
                                1.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "SHARPEN",
                                R.drawable.ic_adjust,
                                "Sharpness",
                                getEffectValue("SHARPEN", 0f),
                                0.0f,
                                1.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "HIGHLIGHTS",
                                R.drawable.ic_adjust,
                                "Highlights",
                                getEffectValue("HIGHLIGHTS"),
                                0.0f,
                                2.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "SHADOWS",
                                R.drawable.ic_adjust,
                                "Shadows",
                                getEffectValue("SHADOWS"),
                                0.0f,
                                2.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "TEMPERATURE",
                                R.drawable.ic_adjust,
                                "Temp",
                                getEffectValue("TEMPERATURE"),
                                0.5f,
                                1.5f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "TINT",
                                R.drawable.ic_adjust,
                                "Tint",
                                getEffectValue("TINT"),
                                0.5f,
                                1.5f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                "GRAIN",
                                R.drawable.ic_adjust,
                                "Grain",
                                getEffectValue("GRAIN", 0f),
                                0.0f,
                                1.0f,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        )
                )

        showUnifiedEditor("Adjustments", adjustmentOptions) { option, value ->
            updateClipEffect(clip, option.id, value)
            rebuildPlayerFromTimeline(currentTimeMs)
        }
    }
    private fun showFiltersPicker() {
        val clip = selectedClip ?: return

        fun getEffectValue(type: String, def: Float = 0.0f): Float {
            return clip.effects.find { it.type == type }?.intensity ?: def
        }

        val filterOptions =
                listOf(
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "GRAYSCALE",
                                iconRes = R.drawable.ic_filter,
                                label = "B&W",
                                value = getEffectValue("GRAYSCALE"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "NOIR",
                                iconRes = R.drawable.ic_filter,
                                label = "Noir",
                                value = getEffectValue("NOIR"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "SEPIA",
                                iconRes = R.drawable.ic_filter,
                                label = "Sepia",
                                value = getEffectValue("SEPIA"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "VINTAGE",
                                iconRes = R.drawable.ic_filter,
                                label = "Vintage",
                                value = getEffectValue("VINTAGE"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "COOL",
                                iconRes = R.drawable.ic_filter,
                                label = "Cool",
                                value = getEffectValue("COOL"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "WARM",
                                iconRes = R.drawable.ic_filter,
                                label = "Warm",
                                value = getEffectValue("WARM"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "LOMO",
                                iconRes = R.drawable.ic_filter,
                                label = "Lomo",
                                value = getEffectValue("LOMO"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "POLAROID",
                                iconRes = R.drawable.ic_filter,
                                label = "Instant",
                                value = getEffectValue("POLAROID"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "NONE",
                                iconRes = R.drawable.ic_close,
                                label = "Reset",
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        )
                )

        showUnifiedEditor("Cinematic Filters", filterOptions) { option, value ->
            if (option.id == "NONE") {
                clip.effects.clear()
            } else {
                updateClipEffect(clip, option.id, value)
            }
            rebuildPlayerFromTimeline(currentTimeMs)
        }
    }
    private fun showEffectsPicker() {
        val clip = selectedClip ?: return

        fun getEffectValue(type: String, def: Float = 0.0f): Float {
            return clip.effects.find { it.type == type }?.intensity ?: def
        }

        val effectOptions =
                listOf(
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "BLUR",
                                iconRes = R.drawable.ic_magic,
                                label = "Blur",
                                value = getEffectValue("BLUR"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "GLITCH",
                                iconRes = R.drawable.ic_magic,
                                label = "Glitch",
                                value = getEffectValue("GLITCH"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "SHAKE",
                                iconRes = R.drawable.ic_magic,
                                label = "Shake",
                                value = getEffectValue("SHAKE"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "OLD_MOVIE",
                                iconRes = R.drawable.ic_magic,
                                label = "Retro",
                                value = getEffectValue("OLD_MOVIE"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "PIXELATE",
                                iconRes = R.drawable.ic_magic,
                                label = "Pixelate",
                                value = getEffectValue("PIXELATE"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "MIRROR",
                                iconRes = R.drawable.ic_magic,
                                label = "Mirror",
                                value = getEffectValue("MIRROR"),
                                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                                controlType =
                                        com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
                        ),
                        com.example.videoeditorapp.ui.editor.EditorOption(
                                id = "RESET",
                                iconRes = R.drawable.ic_close,
                                label = "Reset",
                                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
                        )
                )

        showUnifiedEditor("Special Effects", effectOptions) { option, value ->
            if (option.id == "RESET") {
                clip.effects.removeAll { it.type == "BLUR" || it.type == "SHARPEN" }
            } else {
                updateClipEffect(clip, option.id, value)
            }
            rebuildPlayerFromTimeline(currentTimeMs)
        }
    }

    private fun updateClipEffect(clip: TimelineClip, type: String, intensity: Float) {
        val existing = clip.effects.find { it.type == type }
        if (existing != null) {
            existing.intensity = intensity
        } else {
            clip.effects.add(
                    TimelineEffect(
                            type = type,
                            category = EffectCategory.VIDEO,
                            intensity = intensity
                    )
            )
        }
    }

    //    private fun updatePasteButtonState() {
    //        val hasClip = ClipClipboard.hasClip()
    //        previewBinding.btnPaste?.isEnabled = hasClip
    //        previewBinding.btnPaste?.alpha = if (hasClip) 1.0f else 0.5f
    //    }

    private fun pasteClipFromClipboard() {
        val clipToPaste = ClipClipboard.get() ?: return

        // Create new ID and set time to current playhead
        val newClip =
                clipToPaste.copy(id = UUID.randomUUID().toString(), startTimeMs = currentTimeMs)

        // Find appropriate track
        val trackType =
                when (newClip.type) {
                    ClipType.VIDEO -> TrackType.VIDEO
                    ClipType.AUDIO -> TrackType.AUDIO
                    ClipType.STICKER, ClipType.IMAGE, ClipType.TEXT -> TrackType.OVERLAY
                    else -> TrackType.VIDEO
                }

        val track = project.tracks.find { it.type == trackType } ?: project.tracks.first()
        track.clips.add(newClip)
        track.clips.sortBy { it.startTimeMs }
        previewBinding.timelineView.animateClip(newClip.id)

        Toast.makeText(this, "Pasted at ${currentTimeMs / 1000}s", Toast.LENGTH_SHORT).show()

        rebuildPlayerFromTimeline(currentTimeMs)
        // previewBinding.timelineView?.notifyDataChanged() // If exposed, otherwise
        // refreshTimelineFull or similar
        previewBinding.timelineView.invalidate()
    }

    private fun updateEditorVisibility() {
        val hasClips = project.tracks.any { it.clips.isNotEmpty() }
        val visibility = if (hasClips) View.VISIBLE else View.GONE
        previewBinding.playerView.visibility = visibility
        previewBinding.timelineView.visibility = visibility
    }

    private fun showSplitChoiceDialog() {
        Toast.makeText(this, "Smart Split Dialog Placeholder", Toast.LENGTH_SHORT).show()
    }
}
