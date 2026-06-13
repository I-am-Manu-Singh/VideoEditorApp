package com.example.videoeditorapp

import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import com.example.videoeditorapp.utils.AppDialog
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import com.example.videoeditorapp.databinding.DialogExportProgressBinding
import com.example.videoeditorapp.model.timeline.*
import com.example.videoeditorapp.service.ExportService
import com.example.videoeditorapp.service.ExportState
import com.example.videoeditorapp.utils.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
fun TimelineTemplateEditorActivity.startExportService(resolution: String, aspectRatio: String) {
    val fileName =
        com.example.videoeditorapp.utils.NamingUtils.generateExportFilename(
            this,
            projectName,
            resolution
        )
    val outFile =
        File(com.example.videoeditorapp.utils.NamingUtils.getExportDirectory(), fileName)

    val labIntent = Intent(this, ExportActivity::class.java).apply {
        putExtra("EXTRA_IS_EXPORTING", true)
        putExtra("EXTRA_PROJECT_ID", project.id)
        putExtra(ExportService.EXTRA_OUTPUT_PATH, outFile.absolutePath)
        putExtra(ExportService.EXTRA_PROJECT_NAME, projectName)
        putExtra(ExportService.EXTRA_DURATION, project.getDurationMs())
    }
    startActivity(labIntent)

    val finalResolution =
        project.metadata["EXPORT_RES"] ?: resolution

    val finalAspectRatio =
        project.metadata["EXPORT_ASPECT"] ?: aspectRatio

    val (width, height) =
        calculateExportDimensions(
            finalResolution,
            finalAspectRatio
        )

    Log.d("Export", "--- Export Request Details ---")
    Log.d("Export", "Project Name: $projectName")
    Log.d("Export", "Tracks: ${project.tracks.size}")
    project.tracks.forEachIndexed { index, track ->
        Log.d("Export", "  Track #$index Type: ${track.type}, Clips: ${track.clips.size}")
    }
    Log.d("Export", "Resolution: $finalResolution -> ${width}x${height}")
    Log.d("Export", "Aspect Ratio: $finalAspectRatio")
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
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(this, "Export failed to minimize: ${e.message}", Toast.LENGTH_LONG)
            .show()
    }
}

fun TimelineTemplateEditorActivity.copyUriToTempFile(uri: Uri): String? {
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

suspend fun TimelineTemplateEditorActivity.copyUriToPersistentFile(
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
                    this@copyUriToPersistentFile
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

fun TimelineTemplateEditorActivity.copyFileToPersistent(file: File, fileName: String): String? {
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

@RequiresApi(Build.VERSION_CODES.O)
suspend fun TimelineTemplateEditorActivity.addMediaClip(uri: Uri) {
    saveHistory()
    val scheme = uri.scheme
    val uriString = uri.toString()
    if (scheme == "emoji") {
        addEmojiClip(uriString.substringAfter("emoji://"))
        return
    }
    if (scheme == "res" || uriString.contains("sticker", ignoreCase = true)) {
        addStickerClip(uriString)
        return
    }
    if (uriString.endsWith(".gif", ignoreCase = true) || uriString.contains("giphy.com") || uriString.contains("/giphy/") || uriString.contains("gif", ignoreCase = true)) {
        addGifClip(uriString)
        return
    }

    when (com.example.videoeditorapp.utils.AssetUtils.getUriMediaType(this, uri)) {
        "video" -> addVideoClip(uri)
        "audio" -> addAudioClip(uri)
        "image" -> addImageClip(uri)
    }
}

suspend fun TimelineTemplateEditorActivity.addEmojiClip(emoji: String) {
    withContext(kotlinx.coroutines.Dispatchers.Main) {
        val decodedEmoji = Uri.decode(emoji)
        val duration = 3000L
        val track = findSmartTrack(TrackType.OVERLAY, currentTimeMs, duration)
        val clip = TimelineClip(
            id = UUID.randomUUID().toString(),
            filePath = "emoji://$decodedEmoji",
            startTimeMs = currentTimeMs,
            durationMs = duration,
            sourceDurationMs = duration,
            type = ClipType.EMOJI
        ).apply {
            textSettings["text"] = decodedEmoji
            textSettings["emoji"] = decodedEmoji
            textSettings["color"] = "#FFFFFF"
            textSettings["size"] = "48"
        }
        track.clips.add(clip)
        binding.editorPreview.timelineView.animateClip(clip.id)
        
        ImportMediaRepository.addHistory(this@addEmojiClip,
            ImportedMediaItem(
                uri = "emoji://$decodedEmoji",
                type = "emoji",
                name = decodedEmoji,
                size = 0L
            )
        )
        
        rebuildPlayerFromTimeline(currentTimeMs)
        binding.editorPreview.timelineView.invalidate()
    }
}

suspend fun TimelineTemplateEditorActivity.addStickerClip(stickerUri: String) {
    withContext(kotlinx.coroutines.Dispatchers.Main) {
        val duration = 3000L
        val track = findSmartTrack(TrackType.OVERLAY, currentTimeMs, duration)
        val clip = TimelineClip(
            id = UUID.randomUUID().toString(),
            filePath = stickerUri,
            startTimeMs = currentTimeMs,
            durationMs = duration,
            sourceDurationMs = duration,
            type = ClipType.STICKER
        )
        track.clips.add(clip)
        binding.editorPreview.timelineView.animateClip(clip.id)
        
        val name = if (stickerUri.startsWith("res://")) "Sticker" else stickerUri.substringAfterLast("/")
        ImportMediaRepository.addHistory(this@addStickerClip,
            ImportedMediaItem(
                uri = stickerUri,
                type = "sticker",
                name = name,
                size = 0L
            )
        )
        
        rebuildPlayerFromTimeline(currentTimeMs)
        binding.editorPreview.timelineView.invalidate()
    }
}

suspend fun TimelineTemplateEditorActivity.addGifClip(gifUrl: String) {
    withContext(kotlinx.coroutines.Dispatchers.Main) {
        val duration = 3000L
        val track = findSmartTrack(TrackType.OVERLAY, currentTimeMs, duration)
        val clip = TimelineClip(
            id = UUID.randomUUID().toString(),
            filePath = gifUrl,
            startTimeMs = currentTimeMs,
            durationMs = duration,
            sourceDurationMs = duration,
            type = ClipType.GIF
        )
        track.clips.add(clip)
        binding.editorPreview.timelineView.animateClip(clip.id)
        
        ImportMediaRepository.addHistory(this@addGifClip,
            ImportedMediaItem(
                uri = gifUrl,
                type = "gif",
                name = gifUrl.substringAfterLast("/"),
                size = 0L
            )
        )
        
        rebuildPlayerFromTimeline(currentTimeMs)
        binding.editorPreview.timelineView.invalidate()
    }
}

@RequiresApi(Build.VERSION_CODES.O)
suspend fun TimelineTemplateEditorActivity.addVideoClip(uri: Uri) {
    val mmr = MediaMetadataRetriever()
    try {
        val duration =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                mmr.setDataSource(this@addVideoClip, uri)
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

            val linkGroupId = java.util.UUID.randomUUID().toString()
            val clip =
                TimelineClip(
                    id = UUID.randomUUID().toString(),
                    filePath = path,
                    startTimeMs = startTime,
                    durationMs = duration,
                    sourceStartTimeMs = 0L,
                    sourceDurationMs = duration,
                    type = ClipType.VIDEO
                ).apply {
                    metadata["LINK_GROUP"] = linkGroupId
                }
            track.clips.add(clip)
            binding.editorPreview.timelineView.animateClip(clip.id)

            val file = File(path)
            ImportMediaRepository.addHistory(this@addVideoClip,
                ImportedMediaItem(
                    uri = path,
                    type = "video",
                    name = file.name,
                    size = file.length()
                )
            )

            if (trackType == TrackType.VIDEO) {
                val videoAudioTrack = findSmartTrack(TrackType.AUDIO, startTime, duration)
                val audioPart =
                    clip.copy(id = UUID.randomUUID().toString(), type = ClipType.AUDIO).apply {
                        metadata["LINK_GROUP"] = linkGroupId
                    }
                videoAudioTrack.clips.add(audioPart)
                binding.editorPreview.timelineView.animateClip(audioPart.id)
            }

            val totalClipsCount = project.tracks.sumOf { it.clips.size }
            if (totalClipsCount == 1) {
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ showProjectSettingsDialog() }, 500)
            }
        }
        rebuildPlayerFromTimeline(currentTimeMs)
        binding.editorPreview.timelineView.invalidate()
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        withContext(kotlinx.coroutines.Dispatchers.IO) { mmr.release() }
    }
}

suspend fun TimelineTemplateEditorActivity.addAudioClip(uri: Uri) {
    val mmr = MediaMetadataRetriever()
    try {
        val duration =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                mmr.setDataSource(this@addAudioClip, uri)
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
            binding.editorPreview.timelineView.animateClip(clip.id)

            val file = File(path)
            ImportMediaRepository.addHistory(this@addAudioClip,
                ImportedMediaItem(
                    uri = path,
                    type = "audio",
                    name = file.name,
                    size = file.length()
                )
            )

            rebuildPlayerFromTimeline(currentTimeMs)
            binding.editorPreview.timelineView.invalidate()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        withContext(kotlinx.coroutines.Dispatchers.IO) { mmr.release() }
    }
}

suspend fun TimelineTemplateEditorActivity.addImageClip(uri: Uri) {
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
        binding.editorPreview.timelineView.animateClip(clip.id)

        val file = File(path)
        ImportMediaRepository.addHistory(this@addImageClip,
            ImportedMediaItem(
                uri = path,
                type = "image",
                name = file.name,
                size = file.length()
            )
        )

        rebuildPlayerFromTimeline(currentTimeMs)
        binding.editorPreview.timelineView.invalidate()
    }
}

fun TimelineTemplateEditorActivity.validateTimeline(): Boolean {
    val missingFiles = mutableListOf<String>()
    project.tracks.forEach { track ->
        track.clips.forEach { clip ->
            val exists =
                if (File(clip.filePath).exists()) true
                else {
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

  AppDialog.showInfo(
    context = this,
    title = "Missing Files",
    message = message +
        "\n\nPlease re-import these files or remove them from the timeline."
)
        return false
    }

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
                if (clip.startTimeMs < prevEnd - 1) return false
            }
        }
    }
    return true
}

fun TimelineTemplateEditorActivity.getProjectMaxResolution(): String {
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

fun TimelineTemplateEditorActivity.getRecommendedSettings(): Pair<String, String> {
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
            ratio > 1.7f && ratio < 1.8f -> "YouTube"
            ratio > 0.5f && ratio < 0.6f -> "9:16"
            ratio > 0.9f && ratio < 1.1f -> "Post"
            ratio > 2.3f -> "21:9"
            else -> "Original"
        }

    return recommendedRes to recommendedAsp
}

fun TimelineTemplateEditorActivity.isCodecSupported(mime: String): Boolean {
    return try {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        list.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    } catch (e: Exception) {
        false
    }
}

fun TimelineTemplateEditorActivity.showProjectSettingsDialog() {
    val dialogBinding =
        com.example.videoeditorapp.databinding.DialogProjectSettingsBinding.inflate(layoutInflater)

    val bottomSheet =
        BottomSheetDialog(
            this,
            R.style.TransparentBottomSheetDialog
        )
    bottomSheet.setContentView(dialogBinding.root)
bottomSheet.window?.setBackgroundDrawable(
    android.graphics.drawable.ColorDrawable(
        android.graphics.Color.TRANSPARENT
    )
)

bottomSheet.setOnShowListener {

    val sheet =
        bottomSheet.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )

    sheet?.apply {
        setBackgroundResource(android.R.color.transparent)
        background = null
        setPadding(0, 0, 0, 0)
    }
}
    val (recommendedRes, recommendedAspect) = getRecommendedSettings()

    dialogBinding.tvRecommendedSettings?.text =
        "Recommended: $recommendedRes • $recommendedAspect"

    val exportRes = project.metadata["EXPORT_RES"] ?: recommendedRes

    when {
        project.aspectWidth == 9 && project.aspectHeight == 16 -> {
            dialogBinding.toggleAspectRatio.check(R.id.btnRatio916)
        }
        project.aspectWidth == 16 && project.aspectHeight == 9 -> {
            dialogBinding.toggleAspectRatio.check(R.id.btnRatio169)
        }
        project.aspectWidth == 1 && project.aspectHeight == 1 -> {
            dialogBinding.toggleAspectRatio.check(R.id.btnRatio11)
        }
        recommendedAspect == "9:16" -> {
            dialogBinding.toggleAspectRatio.check(R.id.btnRatio916)
        }
        recommendedAspect == "YouTube" -> {
            dialogBinding.toggleAspectRatio.check(R.id.btnRatio169)
        }
        recommendedAspect == "Post" -> {
            dialogBinding.toggleAspectRatio.check(R.id.btnRatio11)
        }
        else -> {
            dialogBinding.toggleAspectRatio.check(R.id.btnRatioOriginal)
        }
    }

    when (exportRes) {
        "720", "720p" -> dialogBinding.toggleResolution.check(R.id.btnRes720)
        "2160", "4K" -> dialogBinding.toggleResolution.check(R.id.btnRes4K)
        "ORIGINAL", "Original" -> dialogBinding.toggleResolution.check(R.id.btnResOriginal)
        else -> dialogBinding.toggleResolution.check(R.id.btnRes1080)
    }



    fun applySettingsFromDialog() {
        saveHistory()

        when (dialogBinding.toggleAspectRatio.checkedButtonId) {
            R.id.btnRatio916 -> {
                project.aspectWidth = 9
                project.aspectHeight = 16
                project.metadata["EXPORT_ASPECT"] = "9:16"
            }
            R.id.btnRatio169 -> {
                project.aspectWidth = 16
                project.aspectHeight = 9
                project.metadata["EXPORT_ASPECT"] = "16:9"
            }
            R.id.btnRatio11 -> {
                project.aspectWidth = 1
                project.aspectHeight = 1
                project.metadata["EXPORT_ASPECT"] = "1:1"
            }
            else -> {
                project.aspectWidth = 0
                project.aspectHeight = 0
                project.metadata["EXPORT_ASPECT"] = "Original"
            }
        }

        when (dialogBinding.toggleResolution.checkedButtonId) {
            R.id.btnRes720 -> project.metadata["EXPORT_RES"] = "720p"
            R.id.btnRes1080 -> project.metadata["EXPORT_RES"] = "1080p"
            R.id.btnRes4K -> project.metadata["EXPORT_RES"] = "4K"
            R.id.btnResOriginal -> project.metadata["EXPORT_RES"] = "Original"
        }

        saveProject()
        refreshTimelineFull()
    }

    dialogBinding.btnSaveProjectSettings.setOnClickListener {
        applySettingsFromDialog()
        bottomSheet.dismiss()
        Toast.makeText(this, "Project Saved", Toast.LENGTH_SHORT).show()
    }

    dialogBinding.btnApplySettings.setOnClickListener {
        applySettingsFromDialog()
        bottomSheet.dismiss()
        val resolution = project.metadata["EXPORT_RES"] ?: "1080p"
        val aspect = project.metadata["EXPORT_ASPECT"] ?: "Original"
        startExportService(resolution, aspect)
    }

    bottomSheet.show()
}

fun TimelineTemplateEditorActivity.calculateExportDimensions(resolution: String, aspectRatio: String): Pair<Int, Int> {
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
            val width = baseWidth
            val height = (width * preset.aspectHeight / preset.aspectWidth)
            width to height
        }
        else -> {
            val height = baseHeight
            val width = (height * preset.aspectWidth / preset.aspectHeight)
            width to height
        }
    }.let { (w, h) ->
        (w / 2 * 2) to (h / 2 * 2)
    }
}

fun TimelineTemplateEditorActivity.getVideoDimensions(path: String): Pair<Int, Int> {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        var width =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 1920
        var height =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 1080
        val rotation =
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
        retriever.release()
        if (rotation == 90 || rotation == 270) {
            val temp = width
            width = height
            height = temp
        }
        width to height
    } catch (e: Exception) {
        1920 to 1080
    }
}

fun TimelineTemplateEditorActivity.getMediaDuration(path: String): Long {
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

fun TimelineTemplateEditorActivity.openExportScreen(path: String, name: String) {
    startActivity(
        Intent(this, ExportActivity::class.java)
            .putExtra(ExportService.EXTRA_OUTPUT_PATH, path)
            .putExtra(ExportService.EXTRA_PROJECT_NAME, name)
    )
}
