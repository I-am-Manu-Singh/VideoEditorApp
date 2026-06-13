package com.example.videoeditorapp.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MergingMediaSource
import com.example.videoeditorapp.model.timeline.*
import com.example.videoeditorapp.utils.*
import java.io.File
import java.io.FileOutputStream

@UnstableApi
class EditorPlayerController(
    private val context: Context,
    private val projectProvider: () -> TimelineProject,
    private val onPlaybackStateChanged: (Boolean) -> Unit,
    private val onTimeCodeUpdated: (Long) -> Unit,
    private val onMediaTransition: (String) -> Unit
) {
    var exoPlayer: ExoPlayer? = null
        private set

    private var activeTimelineMap: List<Pair<Long, Long>> = emptyList()
    private val playheadHandler = Handler(Looper.getMainLooper())
    private var playheadRunnable: Runnable? = null

    fun initialize() {
        if (exoPlayer != null) return
        
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    onPlaybackStateChanged(isPlaying)
                    if (isPlaying) startPlayheadUpdate()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val clipId = mediaItem?.mediaId ?: return
                    if (clipId.startsWith("GAP_") || clipId.startsWith("TAIL_")) return
                    onMediaTransition(clipId)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        onPlaybackStateChanged(false)
                    }
                }
            })
        }
    }

    fun release() {
        stopPlayheadUpdate()
        exoPlayer?.release()
        exoPlayer = null
    }

    fun playPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun stop() {
        exoPlayer?.pause()
        exoPlayer?.seekTo(0)
    }

    fun seekTo(timeMs: Long) {
        val player = exoPlayer ?: return
        if (activeTimelineMap.isEmpty()) {
            player.seekTo(timeMs)
            return
        }

        // Search for the index in our concatenated sequence
        for (i in activeTimelineMap.indices) {
            val (start, duration) = activeTimelineMap[i]
            val end = start + duration
            if (timeMs in start until end) {
                player.seekTo(i, timeMs - start)
                return
            }
        }
        
        // Fallback for end of timeline
        if (activeTimelineMap.isNotEmpty()) {
            val last = activeTimelineMap.last()
            if (timeMs >= last.first + last.second) {
                player.seekTo(activeTimelineMap.size - 1, last.second)
            }
        }
    }

    fun rebuildPlayerFromTimeline(seekTimeMs: Long) {
        try {
            val player = exoPlayer ?: return
            val project = projectProvider()
            val wasPlaying = player.isPlaying
            
            val maxDuration = project.getDurationMs()
            if (maxDuration <= 0) {
                player.clearMediaItems()
                return
            }

            // Group tracks for Merging
            val videoTracks = project.tracks.filter { it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY }
            val audioTracks = project.tracks.filter { it.type == TrackType.AUDIO || it.type == TrackType.VIDEO_AUDIO }

            // BUILD SOURCES
            val sourcesToMerge = mutableListOf<androidx.media3.exoplayer.source.MediaSource>()

            // 1. Primary Video Content (Determine playback schedule)
            // We use the first video track as the 'master' for sequence mapping
            val masterTrack = videoTracks.find { it.type == TrackType.VIDEO } ?: videoTracks.firstOrNull()
            
            videoTracks.forEach { track ->
                if (track.clips.isNotEmpty()) {
                    sourcesToMerge.add(buildTrackMediaSource(track.clips.sortedBy { it.startTimeMs }, false, maxDuration))
                }
            }

            audioTracks.forEach { track ->
                val filtered = track.clips.filter { File(it.filePath).exists() || it.filePath.startsWith("http") }
                if (filtered.isNotEmpty()) {
                    sourcesToMerge.add(buildTrackMediaSource(filtered.sortedBy { it.startTimeMs }, true, maxDuration))
                }
            }

            if (sourcesToMerge.isEmpty()) {
                // Return a single black source of maxDuration if somehow we have metadata but no clips
                sourcesToMerge.add(buildEmptyMediaSource(maxDuration))
            }

            val finalSource = if (sourcesToMerge.size > 1) {
                MergingMediaSource(*sourcesToMerge.toTypedArray())
            } else {
                sourcesToMerge[0]
            }

            player.setMediaSource(finalSource)
            player.prepare()
            
            // Map the timeline points based on the Mapped Source structure
            rebuildTimelineMap(maxDuration, masterTrack?.clips?.sortedBy { it.startTimeMs }.orEmpty())
            
            seekTo(seekTimeMs)
            if (wasPlaying) player.play()
        } catch (e: Exception) {
            Log.e("EditorPlayer", "Rebuild failed", e)
        }
    }

    private fun buildTrackMediaSource(clips: List<TimelineClip>, isAudio: Boolean, totalDurationMs: Long): androidx.media3.exoplayer.source.MediaSource {
        val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
        val builder = ConcatenatingMediaSource2.Builder()
        var cursor = 0L

        clips.forEach { clip ->
            // GAP HANDLING
            if (clip.startTimeMs > cursor) {
                val gapDur = clip.startTimeMs - cursor
                builder.add(buildGapMediaSource(gapDur, cursor, isAudio, factory))
                cursor += gapDur
            }

            // CONTENT HANDLING
            val mediaItemBuilder = MediaItem.Builder()
                .setMediaId(clip.id)
                .setUri(Uri.fromFile(File(clip.filePath)))
            
            if (isAudio || clip.type == ClipType.VIDEO) {
                mediaItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceStartTimeMs)
                        .setEndPositionMs(clip.sourceStartTimeMs + clip.durationMs)
                        .build()
                )
            } else if (clip.type == ClipType.IMAGE || clip.type == ClipType.STICKER) {
                // 🍏 Fix: Explicitly set Image duration for ExoPlayer
                mediaItemBuilder.setImageDurationMs(clip.durationMs)
            }

            builder.add(factory.createMediaSource(mediaItemBuilder.build()))
            cursor += clip.durationMs
        }

        // TAIL GAP
        if (totalDurationMs > cursor) {
            val tailGap = totalDurationMs - cursor
            builder.add(buildGapMediaSource(tailGap, cursor, isAudio, factory, isTail = true))
        }

        return builder.build()
    }

    private fun buildGapMediaSource(duration: Long, offset: Long, isAudio: Boolean, factory: androidx.media3.exoplayer.source.MediaSource.Factory, isTail: Boolean = false): androidx.media3.exoplayer.source.MediaSource {
        val id = if (isTail) "TAIL_$offset" else "GAP_$offset"
        val item = MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.fromFile(if (isAudio) getSilentAudioFile() else getBlackPlaceholderFile()))
        
        if (isAudio) {
            item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(duration).build())
        } else {
            item.setImageDurationMs(duration)
        }
        return factory.createMediaSource(item.build())
    }

    private fun buildEmptyMediaSource(duration: Long): androidx.media3.exoplayer.source.MediaSource {
        val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
        return buildGapMediaSource(duration, 0, false, factory)
    }

    /**
     * Rebuilds the relationship between Player MediaItem Indices and Pipeline Timeline Time.
     * This ensures that scrubbing 10s into the project seeks to exactly 10s regardless of how many gaps/clips precede it.
     */
    private fun rebuildTimelineMap(projectDur: Long, primaryClips: List<TimelineClip>) {
        val map = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        
        primaryClips.forEach { clip ->
            if (clip.startTimeMs > cursor) {
                val gap = clip.startTimeMs - cursor
                map.add(cursor to gap) // The Gap MediaItem
                cursor += gap
            }
            map.add(cursor to clip.durationMs) // The Clip MediaItem
            cursor += clip.durationMs
        }
        
        if (projectDur > cursor) {
            map.add(cursor to (projectDur - cursor)) // The Tail MediaItem
        }
        
        activeTimelineMap = map
    }

    private fun startPlayheadUpdate() {
        if (playheadRunnable != null) return
        playheadRunnable = object : Runnable {
            override fun run() {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        val timeMs = playerPositionToTimelineTime()
                        onTimeCodeUpdated(timeMs)
                        playheadHandler.postDelayed(this, 50) // High frequency for smooth UI
                    } else {
                        playheadRunnable = null
                    }
                }
            }
        }
        playheadHandler.post(playheadRunnable!!)
    }

    private fun stopPlayheadUpdate() {
        playheadRunnable?.let { playheadHandler.removeCallbacks(it) }
        playheadRunnable = null
    }

    private fun playerPositionToTimelineTime(): Long {
        val player = exoPlayer ?: return 0L
        if (activeTimelineMap.isEmpty()) return player.currentPosition
        
        val itemIndex = player.currentMediaItemIndex
        if (itemIndex !in activeTimelineMap.indices) return 0L
        
        return activeTimelineMap[itemIndex].first + player.currentPosition
    }

    private fun getBlackPlaceholderFile(): File {
        val file = File(context.cacheDir, "black_frame.png")
        if (file.exists()) return file
        // High quality black frame (1080p equivalent to avoid scaling artifacts)
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun getSilentAudioFile(): File {
        val file = File(context.cacheDir, "silent_reference.wav")
        if (file.exists()) return file
        FileOutputStream(file).use { out ->
            val header = java.nio.ByteBuffer.allocate(44).apply {
                order(java.nio.ByteOrder.LITTLE_ENDIAN)
                put("RIFF".toByteArray()); putInt(36 + 44100*2); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(44100); putInt(88200); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(44100*2)
            }
            out.write(header.array())
            out.write(ByteArray(44100*2)) // 1 second of silence
        }
        return file
    }
}
