package com.example.videoeditorapp.ui.editor

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.videoeditorapp.model.timeline.ClipType
import com.example.videoeditorapp.model.timeline.TimelineClip
import com.example.videoeditorapp.model.timeline.TimelineProject
import com.example.videoeditorapp.model.timeline.TimelineTrack
import com.example.videoeditorapp.model.timeline.TrackType
import com.example.videoeditorapp.utils.HistoryManager
import com.example.videoeditorapp.utils.ProjectManager
import java.io.File
import java.util.UUID

class EditorProjectController(
    private val context: Context,
    private var project: TimelineProject,
    private val onStateChanged: (TimelineProject) -> Unit
) {
    private val historyManager = HistoryManager<TimelineProject>(maxHistorySize = 30)
    private val autoSaveHandler = Handler(Looper.getMainLooper())
    companion object {
        private const val EXTRACTED_AUDIO_TRACK_ID = "EXTRACTED_AUDIO_TRACK"
    }
    init {
        onStateChanged(project)
        startAutoSave()
    }

    fun getProject() = project

    fun setProject(newProject: TimelineProject) {
        project = newProject
        onStateChanged(project)
    }

    fun saveHistory() {
        historyManager.saveState(project.deepCopy())
    }

    fun undo() {
        val previousState = historyManager.undo(project.deepCopy())
        if (previousState != null) {
            project = previousState
            onStateChanged(project)
            saveToDisk()
        }
    }

    fun redo() {
        val nextState = historyManager.redo(project.deepCopy())
        if (nextState != null) {
            project = nextState
            onStateChanged(project)
            saveToDisk()
        }
    }

    fun canUndo() = historyManager.canUndo()
    fun canRedo() = historyManager.canRedo()

    fun saveToDisk() {
        ProjectManager.saveProject(context, project)
    }

    fun captureThumbnail() {
        try {
            val firstClip = project.tracks.flatMap { it.clips }.find {
                it.type == ClipType.VIDEO || it.type == ClipType.IMAGE
            }
            if (firstClip != null) {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                    try {
                        val mmr = android.media.MediaMetadataRetriever()
                        mmr.setDataSource(firstClip.filePath)
                        val bitmap = if (firstClip.type == ClipType.VIDEO) {
                            mmr.getFrameAtTime(firstClip.sourceStartTimeMs * 1000)
                        } else {
                            android.graphics.BitmapFactory.decodeFile(firstClip.filePath)
                        }

                        if (bitmap != null) {
                            val thumbFile = File(context.filesDir, "project_thumb_${project.id}.jpg")
                            java.io.FileOutputStream(thumbFile).use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                            }
                            project.thumbnailPath = thumbFile.absolutePath
                            saveToDisk()
                        }
                        mmr.release()
                    } catch (e: Exception) {
                        Log.e("EditorProject", "Thumbnail capture failed", e)
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val intervalStr = prefs.getString("auto_save", "1m") ?: "1m"

            if (intervalStr == "off") {
                autoSaveHandler.removeCallbacks(this)
                return
            }

            val intervalMs = when (intervalStr) {
                "30s" -> 30_000L
                "1m" -> 60_000L
                "2m" -> 120_000L
                else -> 60_000L
            }

            saveToDisk()
            autoSaveHandler.postDelayed(this, intervalMs)
        }
    }

    private fun startAutoSave() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        autoSaveHandler.postDelayed(autoSaveRunnable, 60_000L)
    }


    /**
     * Toggles between Separated Audio and Unified Video.
     * If the clip is already unlinked, it attempts to find and merge the audio back.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun toggleAudioSeparation(clip: TimelineClip) {

        // Only allow separation on clips from the main VIDEO track
        val parentTrack = project.tracks.firstOrNull {
            it.clips.contains(clip)
        } ?: return

        if (parentTrack.type != TrackType.VIDEO) {
            return
        }

        if (clip.type != ClipType.VIDEO) {
            return
        }

        if (clip.isUnlinked) {

            // =========================
            // RELINK AUDIO
            // =========================

            val groupId = clip.metadata["LINK_GROUP"]

            if (!groupId.isNullOrBlank()) {

                val linkedAudio =
                    project.tracks
                        .flatMap { it.clips }
                        .firstOrNull {
                            it.type == ClipType.AUDIO &&
                                    it.isUnlinked &&
                                    it.metadata["LINK_GROUP"] == groupId &&
                                    it.metadata["SOURCE_VIDEO_ID"] == clip.id
                        }

                linkedAudio?.metadata?.remove("LINK_GROUP")
                linkedAudio?.metadata?.remove("SOURCE_VIDEO_ID")

                linkedAudio?.let {
                    removeClip(it)
                }
            }

            clip.metadata.remove("LINK_GROUP")
            clip.metadata.remove("SOURCE_VIDEO_ID")

            clip.isUnlinked = false
            clip.audioVolume = 1f

        } else {

            // =========================
            // SEPARATE AUDIO
            // =========================

            val existingGroupId = clip.metadata["LINK_GROUP"]

            if (!existingGroupId.isNullOrBlank()) {

                val existingAudio =
                    project.tracks
                        .flatMap { it.clips }
                        .any {
                            it.type == ClipType.AUDIO &&
                                    it.metadata["LINK_GROUP"] == existingGroupId &&
                                    it.metadata["SOURCE_VIDEO_ID"] == clip.id
                        }

                if (existingAudio) {
                    return
                }
            }

            val linkGroupId = UUID.randomUUID().toString()
            val sourceVideoId = clip.id

            val audioClip = clip.copy(
                id = UUID.randomUUID().toString(),
                type = ClipType.AUDIO,
                audioVolume = 1f
            ).apply {

                startTimeMs = clip.startTimeMs
                durationMs = clip.durationMs

                isUnlinked = true

                metadata["LINK_GROUP"] = linkGroupId
                metadata["SOURCE_VIDEO_ID"] = sourceVideoId
            }

            clip.metadata["LINK_GROUP"] = linkGroupId
            clip.metadata["SOURCE_VIDEO_ID"] = sourceVideoId

            clip.audioVolume = 0f
            clip.isUnlinked = true

            val audioTrack =
                project.tracks.firstOrNull {
                    it.type == TrackType.VIDEO_AUDIO
                } ?: TimelineTrack(
                    id = EXTRACTED_AUDIO_TRACK_ID,
                    type = TrackType.VIDEO_AUDIO
                ).also {
                    project.tracks.add(it)
                }

            audioTrack.clips.add(audioClip)
            audioTrack.clips.sortBy { it.startTimeMs }
        }

        onStateChanged(project)
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun removeClip(clip: TimelineClip) {
        project.tracks.forEach {
            it.clips.removeAll { c -> c.id == clip.id }
        }

        project.tracks.removeAll { track ->

            track.id == EXTRACTED_AUDIO_TRACK_ID &&
                    track.clips.isEmpty()
        }

        onStateChanged(project)
    }

    fun release() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
    }
}
