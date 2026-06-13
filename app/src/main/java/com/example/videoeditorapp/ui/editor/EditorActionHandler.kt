package com.example.videoeditorapp.ui.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.videoeditorapp.model.timeline.*
import com.example.videoeditorapp.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class AdditionMode {
    APPEND,
    OVERLAY
}

class EditorActionHandler(
    private val context: Context,
    private val projectProvider: () -> TimelineProject,
    private val onProjectUpdated: () -> Unit
) {
    fun deleteClip(clip: TimelineClip) {
        for (track in project.tracks) {
            if (track.clips.remove(clip)) {
                onProjectUpdated()
                return
            }
        }
    }
    private val project get() = projectProvider()

    suspend fun addMediaClip(uri: Uri, additionMode: AdditionMode, currentTimeMs: Long) {
        val scheme = uri.scheme
        if (scheme == "emoji") {
            addEmojiClip(uri.toString().substringAfter("emoji://"), currentTimeMs)
            return
        }
        if (scheme == "res") {
            addStickerClip(uri.toString(), currentTimeMs)
            return
        }
        val pathString = uri.toString()
        if (pathString.endsWith(".gif", ignoreCase = true) || pathString.contains("giphy.com") || pathString.contains("/giphy/")) {
            addGifClip(pathString, currentTimeMs)
            return
        }

        val type = com.example.videoeditorapp.utils.AssetUtils.getUriMediaType(context, uri)

        when (type) {
            "video" -> addVideoClip(uri, additionMode, currentTimeMs)
            "audio" -> addAudioClip(uri, additionMode, currentTimeMs)
            "image" -> addImageClip(uri, additionMode, currentTimeMs)
            else -> addVideoClip(uri, additionMode, currentTimeMs)
        }
    }

    suspend fun addEmojiClip(emoji: String, currentTimeMs: Long) {
        withContext(Dispatchers.Main) {
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
            onProjectUpdated()
        }
    }

    suspend fun addStickerClip(stickerUri: String, currentTimeMs: Long) {
        withContext(Dispatchers.Main) {
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
            onProjectUpdated()
        }
    }

    suspend fun addGifClip(gifUrl: String, currentTimeMs: Long) {
        withContext(Dispatchers.Main) {
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
            onProjectUpdated()
        }
    }

    private suspend fun addVideoClip(uri: Uri, additionMode: AdditionMode, currentTimeMs: Long) {
        val mmr = MediaMetadataRetriever()
        try {
            val duration = withContext(Dispatchers.IO) {
                try {
                    mmr.setDataSource(context, uri)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                } catch (e: Exception) { null }
            } ?: return

            val path = withContext(Dispatchers.IO) {
                copyUriToPersistentFile(context, uri, "v_${System.currentTimeMillis()}.mp4", project.id)
            } ?: return

            withContext(Dispatchers.Main) {
                val trackType = if (additionMode == AdditionMode.APPEND) TrackType.VIDEO else TrackType.OVERLAY
                val track = if (trackType == TrackType.VIDEO) {
                    project.tracks.find { it.type == TrackType.VIDEO }
                        ?: TimelineTrack(id = UUID.randomUUID().toString(), type = TrackType.VIDEO).also { project.tracks.add(it) }
                } else {
                    findSmartTrack(TrackType.OVERLAY, currentTimeMs, duration)
                }

                val startTime = if (additionMode == AdditionMode.APPEND) {
                    track.clips.lastOrNull()?.endTimeMs ?: 0L
                } else {
                    currentTimeMs
                }

                val linkGroupId = UUID.randomUUID().toString()
                val clip = TimelineClip(
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

                // 📜 RECORD HISTORY
                val file = File(path)
                com.example.videoeditorapp.utils.ImportMediaRepository.addHistory(context, 
                    com.example.videoeditorapp.utils.ImportedMediaItem(
                        uri = path,
                        type = "video",
                        name = file.name,
                        size = file.length()
                    )
                )

                if (trackType == TrackType.VIDEO) {
                    val audioTrack = findSmartTrack(TrackType.VIDEO_AUDIO, startTime, duration)
                    val audioPart = clip.copy(id = UUID.randomUUID().toString(), type = ClipType.AUDIO).apply {
                        metadata["LINK_GROUP"] = linkGroupId
                    }
                    audioTrack.clips.add(audioPart)
                }
                onProjectUpdated()
            }
        } finally {
            withContext(Dispatchers.IO) { try { mmr.release() } catch(e: Exception) {} }
        }
    }

    private suspend fun addAudioClip(uri: Uri, additionMode: AdditionMode, currentTimeMs: Long) {
        val mmr = MediaMetadataRetriever()
        try {
            val duration = withContext(Dispatchers.IO) {
                try {
                    mmr.setDataSource(context, uri)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                } catch (e: Exception) { null }
            } ?: return

            val path = withContext(Dispatchers.IO) {
                copyUriToPersistentFile(context, uri, "a_${System.currentTimeMillis()}.mp3", project.id)
            } ?: return

            withContext(Dispatchers.Main) {
                val start = if (additionMode == AdditionMode.APPEND) {
                    project.tracks.find { it.type == TrackType.VIDEO }?.clips?.lastOrNull()?.endTimeMs ?: 0L
                } else {
                    currentTimeMs
                }

                val track = findSmartTrack(TrackType.AUDIO, start, duration)
                val clip = TimelineClip(
                    id = UUID.randomUUID().toString(),
                    filePath = path,
                    startTimeMs = start,
                    durationMs = duration,
                    sourceStartTimeMs = 0L,
                    sourceDurationMs = duration,
                    type = ClipType.AUDIO
                )
                track.clips.add(clip)
                
                // 📜 RECORD HISTORY
                val file = File(path)
                com.example.videoeditorapp.utils.ImportMediaRepository.addHistory(context, 
                    com.example.videoeditorapp.utils.ImportedMediaItem(
                        uri = path,
                        type = "audio",
                        name = file.name,
                        size = file.length()
                    )
                )
                
                onProjectUpdated()
            }
        } finally {
            withContext(Dispatchers.IO) { try { mmr.release() } catch(e: Exception) {} }
        }
    }

    suspend fun addImageClip(uri: Uri, additionMode: AdditionMode, currentTimeMs: Long) {
        val path = withContext(Dispatchers.IO) {
            copyUriToPersistentFile(context, uri, "i_${System.currentTimeMillis()}.png", project.id)
        } ?: return
        
        withContext(Dispatchers.Main) {
            val duration = 5000L
            val start = if (additionMode == AdditionMode.APPEND) {
                project.tracks.find { it.type == TrackType.VIDEO }?.clips?.lastOrNull()?.endTimeMs ?: 0L
            } else {
                currentTimeMs
            }

            val trackType = if (additionMode == AdditionMode.APPEND) TrackType.VIDEO else TrackType.OVERLAY
            val track = if (trackType == TrackType.VIDEO) {
                project.tracks.find { it.type == TrackType.VIDEO }
                    ?: TimelineTrack(id = UUID.randomUUID().toString(), type = TrackType.VIDEO).also { project.tracks.add(it) }
            } else {
                findSmartTrack(TrackType.OVERLAY, start, duration)
            }

            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                filePath = path,
                startTimeMs = start,
                durationMs = duration,
                sourceStartTimeMs = 0L,
                sourceDurationMs = duration,
                type = ClipType.IMAGE
            )
            track.clips.add(clip)
            
            // 📜 RECORD HISTORY
            val file = File(path)
            com.example.videoeditorapp.utils.ImportMediaRepository.addHistory(context, 
                com.example.videoeditorapp.utils.ImportedMediaItem(
                    uri = path,
                    type = "image",
                    name = file.name,
                    size = file.length()
                )
            )
            
            onProjectUpdated()
        }
    }

    suspend fun addTextClip(text: String, currentTimeMs: Long) {
        withContext(Dispatchers.Main) {
            val duration = 3000L
            val track = findSmartTrack(TrackType.OVERLAY, currentTimeMs, duration)
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                filePath = "text://",
                startTimeMs = currentTimeMs,
                durationMs = duration,
                sourceDurationMs = duration,
                type = ClipType.TEXT
            ).apply {
                textSettings["text"] = text
                textSettings["color"] = "#FFFFFF"
                textSettings["size"] = "40"
            }
            track.clips.add(clip)
            onProjectUpdated()
        }
    }

    private fun findSmartTrack(type: TrackType, startTime: Long, duration: Long): TimelineTrack {
        val candidates = project.tracks.filter { it.type == type }
        for (track in candidates) {
            val overlaps = track.clips.any { clip ->
                (startTime < clip.endTimeMs) && (startTime + duration > clip.startTimeMs)
            }
            if (!overlaps) return track
        }
        val newTrack = TimelineTrack(id = UUID.randomUUID().toString(), type = type)
        project.tracks.add(newTrack)
        return newTrack
    }

    fun splitClipAtPlayhead(targetClip: TimelineClip, playheadMs: Long, splitAllLinked: Boolean = true) {
        val clipsToSplit = if (splitAllLinked) {
            findLinkedClips(targetClip)
        } else {
            listOf(targetClip)
        }

        clipsToSplit.forEach { clip ->
            val track = project.tracks.find { it.clips.contains(clip) } ?: return@forEach
            val relativeOffset = playheadMs - clip.startTimeMs
            if (relativeOffset <= 1 || relativeOffset >= clip.durationMs - 1) return@forEach

            val newClip = clip.copy(
                id = UUID.randomUUID().toString(),
                startTimeMs = playheadMs,
                durationMs = clip.durationMs - relativeOffset,
                sourceStartTimeMs = (clip.sourceStartTimeMs + (relativeOffset / clip.playbackSpeed)).toLong(),
                sourceDurationMs = (clip.sourceDurationMs - (relativeOffset / clip.playbackSpeed)).toLong()
            )
            clip.durationMs = relativeOffset
            track.clips.add(newClip)
            track.clips.sortBy { it.startTimeMs }
        }
        onProjectUpdated()
    }

    fun rippleTrack(track: TimelineTrack, startTime: Long, duration: Long) {
        track.clips.filter { it.startTimeMs >= startTime }.forEach {
            it.startTimeMs += duration
        }
    }

    suspend fun pasteClipWithRipple(copied: TimelineClip, startTime: Long) {
        withContext(Dispatchers.Main) {
            val track = project.tracks.find { it.clips.any { c -> c.id == copied.id } || it.type == TrackType.VIDEO } 
                ?: project.tracks.first()
            
            // Ripple ALL tracks to maintain sync (or just current track)
            // Professionals usually ripple all primary tracks
            project.tracks.forEach { rippleTrack(it, startTime, copied.durationMs) }

            val newClip = copied.deepCopy().copy(
                id = UUID.randomUUID().toString(),
                startTimeMs = startTime
            )
            track.clips.add(newClip)
            track.clips.sortBy { it.startTimeMs }
            onProjectUpdated()
        }
    }

    private fun findLinkedClips(target: TimelineClip): List<TimelineClip> {
        val linkedId = target.metadata["LINKED_VIDEO_ID"]
        val isAudio = target.type == ClipType.AUDIO || target.type == ClipType.VOICEOVER
        
        val linked = mutableListOf(target)
        project.tracks.flatMap { it.clips }.forEach { clip ->
            if (clip.id == linkedId || clip.metadata["LINKED_VIDEO_ID"] == target.id) {
                if (!linked.contains(clip)) linked.add(clip)
            }
        }
        return linked
    }

    private suspend fun copyUriToPersistentFile(
        context: Context,
        uri: Uri,
        preferredName: String? = null,
        subDir: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri)) ?: "tmp"
            val fileName = preferredName ?: "imported_${System.currentTimeMillis()}.$extension"
            val baseDir = com.example.videoeditorapp.utils.StorageManager.getImportedMediaDir(context)
            val parentDir = if (subDir != null) File(baseDir, subDir) else baseDir
            val destFile = File(parentDir, fileName)
            destFile.parentFile?.mkdirs()

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
