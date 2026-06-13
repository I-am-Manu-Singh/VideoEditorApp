package com.example.videoeditorapp

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MergingMediaSource
import com.example.videoeditorapp.model.timeline.*
import com.example.videoeditorapp.ui.timeline.TimelineTool
import com.example.videoeditorapp.ui.timeline.TimelineView
import com.example.videoeditorapp.utils.*
import java.io.File
import java.io.FileOutputStream
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
fun TimelineTemplateEditorActivity.setupTimeline() {
    binding.editorPreview.timelineView.setProject(project)

    binding.editorPreview.timelineView.onInteractionStart = {
        exoPlayer?.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
    }

    binding.editorPreview.timelineView.onInteractionEnd = {
        exoPlayer?.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        seekPlayerWithTimelineMap(currentTimeMs, activeTimelineMap, forceSeek = true)
    }

    binding.editorPreview.timelineView.onScrollListener = { timeMs ->
        val player = exoPlayer
        val isCurrentlyPlaying = player != null && player.isPlaying
        if (!isSeeking && !isCurrentlyPlaying) {
            isSeeking = true
            currentTimeMs = timeMs
            binding.editorPreview.tvTimeCode.text = ViewUtils.formatTime(timeMs)
            updatePreviewForTime(timeMs)
            isSeeking = false
        }
    }

    binding.editorPreview.timelineView.onClipSelected = { clip ->
        selectedClip = clip
        updateBottomBarVisibility()
        updateToolUI() // Refresh highlighting

        if (clip != null &&
            (clip.type == ClipType.TEXT ||
                    clip.type == ClipType.IMAGE ||
                    clip.type == ClipType.STICKER ||
                    clip.type == ClipType.EMOJI ||
                    clip.type == ClipType.GIF)
        ) {
            binding.editorPreview.overlayManipulationView.visibility = View.VISIBLE
            binding.editorPreview.overlayManipulationView.setTarget(clip) {
                // DO NOT call rebuildPlayerFromTimeline here as it destroys performance during drag!
                // Instead, just invalidate the overlay manipulation view and timeline view.
                binding.editorPreview.overlayManipulationView.invalidate()
                binding.editorPreview.timelineView.invalidate()
            }
        } else {
            binding.editorPreview.overlayManipulationView.visibility = View.GONE
        }
    }

    binding.editorPreview.overlayManipulationView.onClipSelectedListener = { clip ->
        binding.editorPreview.timelineView.selectClipDirectly(clip)
    }

    binding.editorPreview.timelineView.onToolChanged = { updateToolUI() }

    binding.editorPreview.timelineView.onTimelineChanged = {
        updateUIDuration()
        rebuildPlayerFromTimeline(currentTimeMs)
    }

    binding.editorPreview.timelineView.onTimelineMenuClick = { menuLeft, menuBottom ->
        val timelineView = binding.editorPreview.timelineView
        var dummyAnchor = binding.editorPreview.findViewWithTag<View>("menu_anchor_dummy")
        if (dummyAnchor == null) {
            dummyAnchor = View(this).apply {
                tag = "menu_anchor_dummy"
                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(1, 1)
                visibility = View.INVISIBLE
            }
            binding.editorPreview.addView(dummyAnchor)
        }
        val lp = dummyAnchor.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        lp.leftToLeft = timelineView.id
        lp.topToTop = timelineView.id
        lp.leftMargin = menuLeft.toInt()
        lp.topMargin = menuBottom.toInt()
        dummyAnchor.layoutParams = lp

        dummyAnchor.post {
            val popup = androidx.appcompat.widget.PopupMenu(this, dummyAnchor)
            
            popup.menu.add(0, 1, 0, "Add Media").setIcon(R.drawable.ic_camera)
            popup.menu.add(0, 2, 1, "Audio Effects").setIcon(R.drawable.ic_music)
            popup.menu.add(0, 3, 2, "Video Effects").setIcon(R.drawable.ic_filter)
            popup.menu.add(0, 4, 3, "Text Overlay Options").setIcon(R.drawable.ic_title)
            popup.menu.add(0, 5, 4, "Other Overlay Options").setIcon(R.drawable.ic_visibility)
            popup.menu.add(0, 6, 5, "Effects").setIcon(R.drawable.ic_magic)
            popup.menu.add(0, 7, 6, "Assets").setIcon(R.drawable.ic_sticker)
            popup.menu.add(0, 8, 7, "Adjustments").setIcon(R.drawable.ic_adjust)
            
            try {
                val fields = popup.javaClass.getDeclaredFields()
                for (field in fields) {
                    if ("mPopup" == field.name) {
                        field.isAccessible = true
                        val menuPopupHelper = field.get(popup)
                        val classPopupHelper = Class.forName(menuPopupHelper.javaClass.name)
                        val setForceIcons = classPopupHelper.getMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                        setForceIcons.invoke(menuPopupHelper, true)
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        val bottomSheet = com.example.videoeditorapp.ui.editor.MediaImportBottomSheet { uri, additionMode ->
                            val targetMode = if (additionMode == com.example.videoeditorapp.ui.editor.AdditionMode.OVERLAY) {
                                com.example.videoeditorapp.AdditionMode.OVERLAY
                            } else {
                                com.example.videoeditorapp.AdditionMode.APPEND
                            }
                            currentAdditionMode = targetMode
                            saveHistory()
                            binding.editorPreview.progressBar.visibility = View.VISIBLE
                            lifecycleScope.launch {
                                addMediaClip(uri)
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
                        bottomSheet.show(supportFragmentManager, "MediaImport")
                        true
                    }
                    2 -> {
                        val clip = selectedClip
                        if (clip != null) {
                            showAudioMixerDialog(clip)
                        } else {
                            Toast.makeText(this, "Please select a clip first to apply audio effects", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    3 -> {
                        val clip = selectedClip
                        if (clip != null) {
                            showFiltersPicker()
                        } else {
                            Toast.makeText(this, "Please select a clip first to apply video effects", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    4 -> {
                        val clip = selectedClip
                        if (clip != null && clip.type == ClipType.TEXT) {
                            showTextPropertiesDialog(clip)
                        } else {
                            showTextPropertiesDialog(null)
                        }
                        true
                    }
                    5 -> {
                        val clip = selectedClip
                        if (clip != null && (clip.type == ClipType.IMAGE || clip.type == ClipType.STICKER || clip.type == ClipType.GIF || clip.type == ClipType.EMOJI)) {
                            showOverlayPropertiesDialog(clip)
                        } else {
                            Toast.makeText(this, "Please select an overlay clip (image, sticker, emoji, gif) first", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    6 -> {
                        val clip = selectedClip
                        if (clip != null) {
                            showEffectsPicker()
                        } else {
                            Toast.makeText(this, "Please select a clip first to apply effects", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    7 -> {
                        val intent = android.content.Intent(this, AssetStoreActivity::class.java)
                        hubLauncher.launch(intent)
                        true
                    }
                    8 -> {
                        val clip = selectedClip
                        if (clip != null) {
                            showAdjustPanel()
                        } else {
                            Toast.makeText(this, "Please select a clip first to adjust parameters", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }
}

fun TimelineTemplateEditorActivity.setupPreviewResize() {
    val handle = binding.editorPreview.previewResizeHandle ?: return
    handle.visibility = View.VISIBLE

    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val guideline = binding.editorPreview.splitGuideline

    if (guideline != null) {
        attachResizeGesture(handle) { dx, dy ->
            val parentSize =
                if (isLandscape) (binding.editorPreview.width ?: 0)
                else (binding.editorPreview.height ?: 0)
            if (parentSize == 0) return@attachResizeGesture

            val lp =
                guideline.layoutParams as
                        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val delta = if (isLandscape) dx else dy
            val change = delta / parentSize.toFloat()

            val newPercent = (lp.guidePercent + change).coerceIn(0.2f, 0.75f)
            guideline.setGuidelinePercent(newPercent)
            binding.editorPreview.requestLayout()
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
fun TimelineTemplateEditorActivity.attachResizeGesture(handle: View, onDrag: (dx: Float, dy: Float) -> Unit) {
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
                false
            }
            else -> false
        }
    }
}

fun TimelineTemplateEditorActivity.setupPlayer() {
    exoPlayer?.release()

    exoPlayer =
        ExoPlayer.Builder(this).build().apply {
            binding.editorPreview.playerView.player = this

            addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        runOnUiThread {
                            val iconRes =
                                if (isPlaying) R.drawable.ic_pause
                                else R.drawable.ic_play

                            binding.editorPreview.btnPlayPause.setImageResource(iconRes)
                        }

                        if (isPlaying) {
                            startPlayheadUpdate()
                        }
                    }

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
                                binding.editorPreview.btnPlayPause.setImageResource(
                                    R.drawable.ic_play
                                )
                            }
                        }
                    }
                }
            )
        }
}

fun TimelineTemplateEditorActivity.setVideoEffects(effects: List<androidx.media3.common.Effect>) {
    exoPlayer?.setVideoEffects(effects)
}

fun TimelineTemplateEditorActivity.attachPlayerToView() {
    binding.editorPreview.playerView.player = exoPlayer
}

fun TimelineTemplateEditorActivity.clearControlListeners() {
    binding.editorPreview.btnPlayPause?.setOnClickListener(null)
    binding.editorPreview.btnPrevClip?.setOnClickListener(null)
    binding.editorPreview.btnNextClip?.setOnClickListener(null)
    binding.editorPreview.btnStop?.setOnClickListener(null)

    binding.editorPreview.btnSelect?.setOnClickListener(null)
    binding.editorPreview.btnTrim?.setOnClickListener(null)
    binding.editorPreview.btnSpeed?.setOnClickListener(null)
    binding.editorPreview.btnVolume?.setOnClickListener(null)
    binding.editorPreview.btnCopyClip?.setOnClickListener(null)
    binding.editorPreview.btnCropClip?.setOnClickListener(null)
    binding.editorPreview.btnMoveToFront?.setOnClickListener(null)
    binding.editorPreview.btnMoveToBack?.setOnClickListener(null)
    binding.editorPreview.btnFrameBack?.setOnClickListener(null)
    binding.editorPreview.btnFrameFwd?.setOnClickListener(null)
}

@RequiresApi(Build.VERSION_CODES.O)
fun TimelineTemplateEditorActivity.setupControls() {
    binding.editorPreview.btnFrameBack?.setOnClickListener { stepFrame(-1) }
    binding.editorPreview.btnFrameFwd?.setOnClickListener { stepFrame(1) }

    binding.editorPreview.timelineView.onClipLongPressed = { clip ->
        if (clip.type == ClipType.VIDEO) {
            toggleLinkUnlink(clip)
        }
    }

    binding.editorPreview.btnPlayPause?.setOnClickListener {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    binding.editorPreview.btnStop?.setOnClickListener {
        exoPlayer?.pause()
        exoPlayer?.seekTo(0)
        binding.editorPreview.timelineView.seekTo(0)
        currentTimeMs = 0L
        binding.editorPreview.tvTimeCode.text = ViewUtils.formatTime(0)
    }

    binding.editorPreview.btnPrevClip?.setOnClickListener {
        val proj = project
        if (proj.tracks.isNotEmpty()) {
            val allClips = proj.tracks.flatMap { it.clips }.sortedBy { it.startTimeMs }

            val current = allClips.findLast { it.startTimeMs < currentTimeMs - 50 }
            val targetTime = current?.startTimeMs ?: 0L

            currentTimeMs = targetTime
            rebuildPlayerFromTimeline(currentTimeMs)
            binding.editorPreview.timelineView.seekTo(currentTimeMs)
        }
    }

    binding.editorPreview.btnNextClip?.setOnClickListener {
        val proj = project
        if (proj.tracks.isNotEmpty()) {
            val allClips = proj.tracks.flatMap { it.clips }.sortedBy { it.startTimeMs }
            val next = allClips.find { it.startTimeMs > currentTimeMs + 50 }

            val targetTime = next?.startTimeMs ?: proj.getDurationMs()

            currentTimeMs = targetTime
            rebuildPlayerFromTimeline(currentTimeMs)
            binding.editorPreview.timelineView.seekTo(currentTimeMs)
        }
    }

    // Clip actions listeners
    binding.editorPreview.btnSelect.setOnClickListener {
        binding.editorPreview.timelineView.setTool(TimelineTool.SELECT)
        updateToolUI()
    }
    binding.editorPreview.btnTrim.setOnClickListener {
        binding.editorPreview.timelineView.setTool(TimelineTool.TRIM)
        updateToolUI()
    }
    binding.editorPreview.btnSplit.setOnClickListener {
        selectedClip?.let {
            saveHistory()
            splitClipAtPlayhead(it, splitAllLinked = true)
        }
    }
    binding.editorPreview.btnDeleteClip.setOnClickListener {
        selectedClip?.let {
            saveHistory()
            binding.editorPreview.timelineView.deleteSelectedClip()
            selectedClip = null
            updateToolUI()
            rebuildPlayerFromTimeline(currentTimeMs)
        }
    }
    binding.editorPreview.btnCopyClip.setOnClickListener {
        selectedClip?.let {
            ClipClipboard.copy(it)
            Toast.makeText(this, "Clip Copied", Toast.LENGTH_SHORT).show()
        }
    }
    binding.editorPreview.btnPasteClip.setOnClickListener {
        if (ClipClipboard.hasClip()) {
            saveHistory()
            pasteClipFromClipboard()
        }
    }
    binding.editorPreview.btnSpeed.setOnClickListener { showSpeedDialog() }
    binding.editorPreview.btnVolume.setOnClickListener { showVolumeDialog() }
    binding.editorPreview.btnSpeedRamp.setOnClickListener { Toast.makeText(this, "Speed Ramp: Coming Soon", Toast.LENGTH_SHORT).show() }
    binding.editorPreview.btnKeyframe.setOnClickListener { Toast.makeText(this, "Keyframe added at ${currentTimeMs}ms", Toast.LENGTH_SHORT).show() }
    binding.editorPreview.btnMasking.setOnClickListener {
        selectedClip?.let {
            showUnifiedEditor("Masking Controls", listOf(
                com.example.videoeditorapp.ui.editor.EditorOption("size", R.drawable.ic_mask, "Shape Radius", 50f, 0f, 100f),
                com.example.videoeditorapp.ui.editor.EditorOption("feather", R.drawable.ic_opacity, "Blend Edge", 10f, 0f, 100f)
            )) { _, _ -> rebuildPlayerFromTimeline(currentTimeMs) }
        }
    }
    binding.editorPreview.btnLut.setOnClickListener {
        selectedClip?.let {
            showUnifiedEditor("3D LUT / Grading", listOf(
                com.example.videoeditorapp.ui.editor.EditorOption("intensity", R.drawable.ic_lut, "Filter Power", 0.8f, 0f, 1.0f)
            )) { _, _ -> rebuildPlayerFromTimeline(currentTimeMs) }
        }
    }
    binding.editorPreview.btnCropClip.setOnClickListener { showCropDialog() }
    binding.editorPreview.btnMoveToFront.setOnClickListener {
        selectedClip?.let {
            saveHistory()
            moveSelectedClipToFront()
        }
    }
    binding.editorPreview.btnMoveToBack.setOnClickListener {
        selectedClip?.let {
            saveHistory()
            moveSelectedClipToBack()
        }
    }


}

fun TimelineTemplateEditorActivity.stepFrame(direction: Int) {
    exoPlayer?.pause()
    val frameMs = 40L
    var target = currentTimeMs + (direction * frameMs)
    val duration = project.getDurationMs()
    target = target.coerceIn(0, duration)

    currentTimeMs = target
    binding.editorPreview.timelineView.seekTo(target)
    rebuildPlayerFromTimeline(target)
    updateUIDuration()
}

fun TimelineTemplateEditorActivity.rebuildTimelineMap(clips: List<TimelineClip>, totalDurationMs: Long = 0L) {
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
    if (totalDurationMs > cursor) {
        val tailGap = totalDurationMs - cursor
        map.add(cursor to tailGap)
    }
    activeTimelineMap = map
}

fun TimelineTemplateEditorActivity.buildTrackMediaSource(
    clips: List<TimelineClip>,
    isAudio: Boolean,
    totalDurationMs: Long = 0L
): androidx.media3.exoplayer.source.MediaSource {
    val sources = mutableListOf<androidx.media3.exoplayer.source.MediaSource>()
    val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
    var cursor = 0L

    clips.forEach { clip ->
        if (clip.startTimeMs > cursor) {
            val gapDuration = clip.startTimeMs - cursor
            if (isAudio) {
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

        val resolvedPath =
            com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(
                this,
                clip.filePath
            )
        val finalPath = resolvedPath ?: clip.filePath
        val isRemote = finalPath.startsWith("http")

        if (!isRemote && !File(finalPath).exists()) {
            Log.e("TimelineEditor", "Missing file for clip: ${clip.id} at ${clip.filePath}")
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
    
    // We must pass the placeholder duration to builder.add() for progressive media sources in ConcatenatingMediaSource2.
    // Let's re-run a loop or construct the sources with their corresponding durations.
    var currentCursor = 0L
    clips.forEach { clip ->
        if (clip.startTimeMs > currentCursor) {
            val gapDuration = clip.startTimeMs - currentCursor
            val gapSource = sources.removeAt(0)
            builder.add(gapSource, gapDuration * 1000L)
            currentCursor += gapDuration
        }
        val clipSource = sources.removeAt(0)
        builder.add(clipSource, clip.durationMs * 1000L)
        currentCursor += clip.durationMs
    }
    if (totalDurationMs > currentCursor) {
        val tailGap = totalDurationMs - currentCursor
        val tailSource = sources.removeAt(0)
        builder.add(tailSource, tailGap * 1000L)
    }

    return builder.build()
}

fun TimelineTemplateEditorActivity.getSilentAudioFile(): File {
    val file = File(cacheDir, "silence_10s.wav")
    if (file.exists()) return file

    try {
        val sampleRate = 44100
        val durationSec = 10
        val numSamples = sampleRate * durationSec
        val numChannels = 2
        val bytesPerSample = 2
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
                putInt(16)
                putShort(1)
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

fun TimelineTemplateEditorActivity.getBlackPlaceholderFile(): File {
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

fun TimelineTemplateEditorActivity.buildClipSource(player: ExoPlayer, clip: TimelineClip) {
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

    binding.editorPreview.tvDuration.text = " / " + ViewUtils.formatTime(clip.durationMs)
    binding.editorPreview.tvTimeCode.text = "00:00"
}

fun TimelineTemplateEditorActivity.seekPlayerWithTimelineMap(timeMs: Long, map: List<Pair<Long, Long>>, forceSeek: Boolean = false) {
    val player = exoPlayer ?: return
    val targetTime = timeMs.coerceAtLeast(0L)
    val now = System.currentTimeMillis()
    if (forceSeek || now - lastSeekTime > 33L) {
        if (forceSeek || Math.abs(player.currentPosition - targetTime) > 80L) {
            player.seekTo(0, targetTime)
            lastSeekTime = now
            updateEffectsForTime(targetTime)
        }
    }
}

fun TimelineTemplateEditorActivity.updateEffectsForTime(timeMs: Long) {
    val proj = project ?: return
    val videoTracks = proj.tracks.filter { it.type == TrackType.VIDEO }
    val primaryClips = videoTracks.flatMap { it.clips }.sortedBy { it.startTimeMs }
    val currentClip = primaryClips.find { timeMs >= it.startTimeMs && timeMs < it.startTimeMs + it.durationMs }
    
    val lastActiveClipId = binding.editorPreview.playerView.tag as? String
    if (currentClip?.id != lastActiveClipId) {
        binding.editorPreview.playerView.tag = currentClip?.id
        if (currentClip != null) {
            val overlayIds = currentClip.metadata["OVERLAYS"]?.split(",") ?: emptyList()
            val overlays = proj.tracks.flatMap { it.clips }.filter { it.id in overlayIds }
            val effects = Media3EffectEngine.getCombinedEffects(proj, currentClip, overlays)
            setVideoEffects(effects)
        } else {
            setVideoEffects(emptyList())
        }
    }
}

fun TimelineTemplateEditorActivity.updatePreviewForTime(timeMs: Long) {
    seekPlayerWithTimelineMap(timeMs, activeTimelineMap)
    syncOverlayPreview(timeMs)
}

fun TimelineTemplateEditorActivity.syncOverlayPreview(timeMs: Long) {
    binding.editorPreview.overlayManipulationView.visibility = android.view.View.VISIBLE
    binding.editorPreview.overlayManipulationView.updatePreview(timeMs, project.tracks)
}

fun TimelineTemplateEditorActivity.startPlayheadUpdate() {
    var playheadRunnable: Runnable? = null
    playheadRunnable =
        object : Runnable {
            override fun run() {
                val player = exoPlayer

                if (player != null && player.isPlaying) {
                    currentTimeMs = playerPositionToTimelineTime()
                    binding.editorPreview.timelineView.seekTo(currentTimeMs)
                    binding.editorPreview.tvTimeCode.text = ViewUtils.formatTime(currentTimeMs)
                    updateEffectsForTime(currentTimeMs)
                    syncOverlayPreview(currentTimeMs)

                    Handler(Looper.getMainLooper()).postDelayed(this, 100)
                }
            }
        }

    Handler(Looper.getMainLooper()).post(playheadRunnable)
}

fun TimelineTemplateEditorActivity.playerPositionToTimelineTime(): Long {
    val player = exoPlayer ?: return 0L
    return player.currentPosition
}

fun TimelineTemplateEditorActivity.updateUIDuration() {
    val durationMs =
        if (activeTimelineMap.isNotEmpty()) {
            activeTimelineMap.sumOf { it.second }
        } else {
            project.getDurationMs()
        }

    binding.editorPreview.tvDuration?.text = " / " + ViewUtils.formatTime(durationMs)

    // Toggle Empty Placeholder
    val hasClips = project.tracks.any { it.clips.isNotEmpty() }
    binding.editorPreview.layoutEmptyPreview?.visibility = if (hasClips) View.GONE else View.VISIBLE
}

@OptIn(UnstableApi::class)
fun TimelineTemplateEditorActivity.rebuildPlayerFromTimeline(seekTimeMs: Long = currentTimeMs) {
    try {
        val player = exoPlayer ?: return
        val wasPlaying = player.isPlaying
        val currentPos = if (seekTimeMs >= 0) seekTimeMs else player.currentPosition

        // 1. Get All Tracks
        val videoTracks = project.tracks.filter { it.type == TrackType.VIDEO }
        // Get all clips from the first video track sorted by startTimeMs
        val primaryClips = videoTracks.flatMap { it.clips }.sortedBy { it.startTimeMs }

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

        rebuildTimelineMap(primaryClips, maxDuration)

        // 4. Set to Player and Restore State
        player.setMediaSource(finalSource)
        player.prepare()
        seekPlayerWithTimelineMap(currentPos, activeTimelineMap, forceSeek = true)
        if (wasPlaying) {
            player.play()
        }

        updateUIDuration()
        syncOverlayPreview(currentPos)

        // Apply visual effects/filters immediately on preview rebuild
        val currentItemIdx = player.currentMediaItemIndex
        if (currentItemIdx in activeTimelineMap.indices && primaryClips.isNotEmpty()) {
            val clip = primaryClips.getOrNull(currentItemIdx)
            if (clip != null) {
                val overlayIds = clip.metadata["OVERLAYS"]?.split(",") ?: emptyList()
                val overlays = project.tracks.flatMap { it.clips }.filter { it.id in overlayIds }
                val effects = Media3EffectEngine.getCombinedEffects(project, clip, overlays)
                setVideoEffects(effects)
            }
        }
    } catch (e: Exception) {
        Log.e("TimelineEditor", "Error rebuilding player", e)
        Toast.makeText(this, "Playback Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
