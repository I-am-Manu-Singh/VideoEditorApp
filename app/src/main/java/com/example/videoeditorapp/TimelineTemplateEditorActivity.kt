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

@UnstableApi
class TimelineTemplateEditorActivity : AppCompatActivity() {
    internal var minPreviewHeight = 0
    internal var maxPreviewHeight = 0
    internal lateinit var binding: ActivityTimelineTemplateEditorBinding
    internal lateinit var project: TimelineProject
    internal var exoPlayer: ExoPlayer? = null
    internal val historyManager = HistoryManager<TimelineProject>(maxHistorySize = 30)

    internal var currentTimeMs = 0L
    internal var isSeeking = false
    internal var lastSeekTime = 0L
    internal var selectedClip: TimelineClip? = null
    internal var currentAdditionMode = AdditionMode.APPEND

    internal val DEFAULT_IMAGE_DURATION_MS = 5_000L
    internal var projectName = ""

    // Resolution and Aspect Ratio state
    internal var selectedResolution = "ORIGINAL"
    internal var selectedAspectRatio = "ORIGINAL"

    internal var activeTimelineMap: List<Pair<Long, Long>> = emptyList()
    internal var wasPlayingBeforeScrub = false
    internal var isFabMenuOpen = false
    internal var playheadRunnable: Runnable? = null
    internal val autoSaveHandler = Handler(Looper.getMainLooper())
    internal val autoSaveRunnable =
        object : Runnable {
            override fun run() {
                val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                val intervalStr = prefs.getString("auto_save", "1m") ?: "1m"

                if (intervalStr != "off") {
                    if (::project.isInitialized && project.tracks.sumOf { it.clips.size } > 0) {
                        saveProject()
                        Log.d("TimelineEditor", "Auto-saved project")
                    }
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

    internal var pendingWatermarkPicker: (() -> Unit)? = null

    @RequiresApi(Build.VERSION_CODES.O)
    internal val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult

            // Show loading indicator
            binding.editorPreview.progressBar.visibility = View.VISIBLE

            saveHistory()

            lifecycleScope.launch {
                uris.forEach { uri -> addMediaClip(uri) }

                withContext(Dispatchers.Main) {
                    binding.editorPreview.progressBar.visibility = View.GONE

                    updateUIDuration()
                    binding.editorPreview.timelineView.setProject(project)
                    binding.editorPreview.timelineView.seekTo(currentTimeMs)
                    binding.editorPreview.timelineView.invalidate()
                    rebuildPlayerFromTimeline(currentTimeMs)
                }
            }
        }

    internal val hubLauncher =
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

    internal val pickWatermarkLauncher =
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

        binding = ActivityTimelineTemplateEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            project.name = projectName
        }
        project.templateId = "TIMELINE"
        projectName = project.name

        setupTimeline()

        binding.editorPreview.timelineView.setProject(project)

        binding.editorPreview.timelineView.onInteractionStart = {
            if (exoPlayer?.isPlaying == true) {
                exoPlayer?.pause()
                wasPlayingBeforeScrub = true
            }
        }
        binding.editorPreview.timelineView.onInteractionEnd = {
            if (wasPlayingBeforeScrub) {
                exoPlayer?.play()
                wasPlayingBeforeScrub = false
            }
        }

        binding.editorPreview.timelineView.onClipLongPressed = { clip ->
            if (clip.type == ClipType.VIDEO) {
                toggleLinkUnlink(clip)
            }
        }

        setupPlayer()
        attachPlayerToView()
        setupControls()

        updateUIDuration()
        minPreviewHeight = ViewUtils.dpToPx(this, 260)
        maxPreviewHeight = ViewUtils.dpToPx(this, 620)

        binding.editorPreview.timelineView.seekTo(currentTimeMs)
        rebuildPlayerFromTimeline(currentTimeMs)
        checkAndRequestNotificationPermission()
        setupHistoryControls()
        startAutoSave()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("project", project)
        outState.putLong("currentTimeMs", currentTimeMs)
    }

    internal fun setupHistoryControls() {
        binding.btnUndoContainer.setOnClickListener { performUndo() }
        binding.btnRedoContainer.setOnClickListener { performRedo() }
        updateHistoryButtons()
    }

    internal fun saveHistory() {
        historyManager.saveState(project.deepCopy())
        updateHistoryButtons()
    }

    internal fun performUndo() {
        val previousState = historyManager.undo(project.deepCopy())
        if (previousState != null) {
            project = previousState
            onProjectStateRestored()
        }
    }

    internal fun performRedo() {
        val nextState = historyManager.redo(project.deepCopy())
        if (nextState != null) {
            project = nextState
            onProjectStateRestored()
        }
    }

    internal fun onProjectStateRestored() {
        updateHistoryButtons()
        refreshTimelineFull()
        saveProject()
    }

    internal fun updateHistoryButtons() {
        binding.btnUndo.apply {
            isEnabled = historyManager.canUndo()
            alpha = if (isEnabled) 1.0f else 0.5f
        }
        binding.btnRedo.apply {
            isEnabled = historyManager.canRedo()
            alpha = if (isEnabled) 1.0f else 0.5f
        }
    }

    internal fun refreshTimelineFull() {
        binding.editorPreview.timelineView.setProject(project)
        rebuildPlayerFromTimeline(currentTimeMs)
        updateUIDuration()
        binding.editorPreview.timelineView.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    internal fun setupToolbar() {
        binding.btnBackContainer.setOnClickListener { finish() }
        binding.tvToolbarTitle.text = projectName.uppercase()
        binding.btnSaveContainer.setOnClickListener {
            saveProject()
            Toast.makeText(this, "Project Saved", Toast.LENGTH_SHORT).show()
        }
        binding.btnInfoContainer.setOnClickListener { showClipInfoDialog() }
        binding.btnSettingsContainer?.setOnClickListener { showProjectSettingsDialog() }
    }

    internal fun saveProject() {
        if (::project.isInitialized) {
            com.example.videoeditorapp.utils.ProjectManager.saveProject(this, project)
        }
    }

    internal fun restoreProjectFromIntent(
        projectPath: String?,
        projectId: String?,
        projectName: String?
    ) {
        var loadedProject: TimelineProject? = null

        if (!projectPath.isNullOrEmpty()) {
            try {
                val file = File(projectPath)
                if (file.exists()) {
                    val json = file.readText()
                    loadedProject =
                        com.google.gson.GsonBuilder()
                            .create()
                            .fromJson(json, TimelineProject::class.java)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (loadedProject == null && !projectId.isNullOrEmpty()) {
            loadedProject =
                com.example.videoeditorapp.utils.ProjectManager.loadProject(this, projectId)
        }

        if (loadedProject != null) {
            project = loadedProject
        } else {
            setupProject()
            project.name = projectName ?: "My Movie"
        }

        if (::binding.isInitialized) {
            binding.editorPreview.timelineView.setProject(project)
        }
    }

    internal fun restoreProject(projectName: String) {
        restoreProjectFromIntent(null, null, projectName)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val oldPlayer = binding.editorPreview.playerView.player
        binding.editorPreview.playerView.player = null

        binding = ActivityTimelineTemplateEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editorPreview.playerView.player = oldPlayer

        setupEdgeToEdge()

        setupToolbar()
        setupTimeline()

        setupControls()
        setupPreviewResize()

        binding.editorPreview.timelineView.setProject(project)
        binding.editorPreview.timelineView.seekTo(currentTimeMs)
        updateUIDuration()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        captureThumbnail()
        if (::project.isInitialized) {
            val totalClips = project.tracks.sumOf { it.clips.size }
            if (totalClips == 0) {
                com.example.videoeditorapp.utils.ProjectManager.deleteProject(this, project.id)
            } else {
                saveProject()
            }
        }
    }

    internal fun captureThumbnail() {
        try {
            val firstClip =
                project.tracks.flatMap { it.clips }.find {
                    it.type == ClipType.VIDEO || it.type == ClipType.IMAGE
                }
            if (firstClip != null) {
                lifecycleScope.launch(Dispatchers.IO) {
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
                                    80,
                                    out
                                )
                            }
                            project.thumbnailPath = thumbFile.absolutePath
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

    internal fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.toolbarTimeline, null)
        ViewCompat.setOnApplyWindowInsetsListener(binding.editorPreview.bottomNavWrapper) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom)
            insets
        }
    }

    internal fun setupProject() {
        project = TimelineProject(id = UUID.randomUUID().toString(), name = projectName).apply {
            tracks.add(TimelineTrack(UUID.randomUUID().toString(), TrackType.VIDEO))
            tracks.add(TimelineTrack(UUID.randomUUID().toString(), TrackType.AUDIO))
        }
    }
}
