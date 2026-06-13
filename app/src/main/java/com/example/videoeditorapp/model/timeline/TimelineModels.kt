package com.example.videoeditorapp.model.timeline

import android.os.Parcelable
import java.util.UUID
import kotlinx.parcelize.Parcelize

@Parcelize
enum class ClipType : Parcelable {
        VIDEO,
        AUDIO,
        IMAGE,
        TEXT,
        STICKER,
        EMOJI,
        GIF,
        GAP,
        VOICEOVER
}

@Parcelize
enum class TrackType : Parcelable {
        VIDEO,
        AUDIO,
        OVERLAY,
        VIDEO_AUDIO,
        VOICEOVER
}

@Parcelize
enum class ExportPreset(val label: String, val aspectWidth: Int, val aspectHeight: Int) :
        Parcelable {
        YOUTUBE("YouTube (16:9)", 1920, 1080),
        INSTAGRAM_REEL("Instagram Reel (9:16)", 1080, 1920),
        TIKTOK("TikTok (9:16)", 1080, 1920),
        INSTAGRAM_POST("Instagram Post (4:5)", 1080, 1350),
        WHATSAPP_STORY("WhatsApp Story (9:16)", 1080, 1920),
        YOUTUBE_SHORT("YouTube Short (9:16)", 1080, 1920),
        CINEMATIC("Cinematic (21:9)", 2560, 1080),
        ORIGINAL("Original", 0, 0)
}

@Parcelize
data class Keyframe(val timeMs: Long, val value: Float, val interpolation: String = "LINEAR") :
        Parcelable

@Parcelize
enum class EffectCategory : Parcelable {
        VIDEO,
        AUDIO,
        OVERLAY,
        TRANSITION
}

@Parcelize
data class TimelineEffect(
        val id: String = UUID.randomUUID().toString(),
        val type: String,
        val category: EffectCategory = EffectCategory.VIDEO,
        var intensity: Float = 1.0f,
        val keyframes: MutableList<Keyframe> = mutableListOf(),
        val parameters: MutableMap<String, String> = mutableMapOf()
) : Parcelable {
        fun deepCopy() =
                TimelineEffect(
                        id = id,
                        type = type,
                        category = category,
                        intensity = intensity,
                        keyframes = keyframes.toMutableList(),
                        parameters = parameters.toMutableMap()
                )
}

@Parcelize
data class TimelineClip(
        val id: String = UUID.randomUUID().toString(),
        var filePath: String,
        var startTimeMs: Long,
        var durationMs: Long,
        var sourceStartTimeMs: Long = 0,
        var sourceDurationMs: Long,
        val type: ClipType,
        var overlayX: Float = 0.5f,
        var overlayY: Float = 0.5f,
        var overlayScale: Float = 1.0f,
        var overlayRotation: Float = 0f,
        var overlayOpacity: Float = 1.0f,
        var audioVolume: Float = 1.0f,
        var playbackSpeed: Float = 1.0f,
        
        // 💎 PRO: Property Keyframes for Animation
        val xKeyframes: MutableList<Keyframe> = mutableListOf(),
        val yKeyframes: MutableList<Keyframe> = mutableListOf(),
        val scaleKeyframes: MutableList<Keyframe> = mutableListOf(),
        val rotationKeyframes: MutableList<Keyframe> = mutableListOf(),
        val opacityKeyframes: MutableList<Keyframe> = mutableListOf(),

        val textSettings: MutableMap<String, String> = mutableMapOf(),
        val audioSettings: MutableMap<String, String> = mutableMapOf(),
        val metadata: MutableMap<String, String> = mutableMapOf(),
        val effects: MutableList<TimelineEffect> = mutableListOf(),
        var isUnlinked: Boolean = false
) : Parcelable {
        val endTimeMs: Long
                get() = startTimeMs + durationMs

        fun deepCopy() =
                TimelineClip(
                        id = id,
                        filePath = filePath,
                        startTimeMs = startTimeMs,
                        durationMs = durationMs,
                        sourceStartTimeMs = sourceStartTimeMs,
                        sourceDurationMs = sourceDurationMs,
                        type = type,
                        overlayX = overlayX,
                        overlayY = overlayY,
                        overlayScale = overlayScale,
                        overlayRotation = overlayRotation,
                        overlayOpacity = overlayOpacity,
                        audioVolume = audioVolume,
                        playbackSpeed = playbackSpeed,
                        xKeyframes = xKeyframes.map { it.copy() }.toMutableList(),
                        yKeyframes = yKeyframes.map { it.copy() }.toMutableList(),
                        scaleKeyframes = scaleKeyframes.map { it.copy() }.toMutableList(),
                        rotationKeyframes = rotationKeyframes.map { it.copy() }.toMutableList(),
                        opacityKeyframes = opacityKeyframes.map { it.copy() }.toMutableList(),
                        textSettings = textSettings.toMutableMap(),
                        audioSettings = audioSettings.toMutableMap(),
                        metadata = metadata.toMutableMap(),
                        effects = effects.map { it.deepCopy() }.toMutableList(),
                        isUnlinked = isUnlinked
                )
}

@Parcelize
data class TimelineTrack(
        val id: String = UUID.randomUUID().toString(),
        val type: TrackType,
        var isLocked: Boolean = false,
        var isVisible: Boolean = true,
        var isMuted: Boolean = false,
        val clips: MutableList<TimelineClip> = mutableListOf()
) : Parcelable {
        fun deepCopy() =
                TimelineTrack(
                        id = id,
                        type = type,
                        isLocked = isLocked,
                        isVisible = isVisible,
                        isMuted = isMuted,
                        clips = clips.map { it.deepCopy() }.toMutableList()
                )
}

@Parcelize
data class TimelineProject(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        var templateId: String = "TIMELINE",
        var lastModified: Long = System.currentTimeMillis(),
        val tracks: MutableList<TimelineTrack> = mutableListOf(),
        val metadata: MutableMap<String, String> = mutableMapOf(),
        var aspectWidth: Int = 16,
        var aspectHeight: Int = 9,
        var activePreset: ExportPreset = ExportPreset.ORIGINAL,
        var exportResolution: String = "1080p", // New: 480p, 720p, 1080p, 4K
        var renderQuality: String = "High", // New: Sync with app settings
        var watermarkPath: String? = null, // New: Path to custom watermark PNG
        var thumbnailPath: String? = null
) : Parcelable {
        fun getDurationMs(): Long {
                return tracks.flatMap { it.clips }.maxOfOrNull { it.startTimeMs + it.durationMs }
                        ?: 0L
        }

        fun deepCopy() =
                TimelineProject(
                        id = id,
                        name = name,
                        templateId = templateId,
                        lastModified = lastModified,
                        tracks = tracks.map { it.deepCopy() }.toMutableList(),
                        metadata = metadata.toMutableMap(),
                        aspectWidth = aspectWidth,
                        aspectHeight = aspectHeight,
                        activePreset = activePreset,
                        exportResolution = exportResolution,
                        renderQuality = renderQuality,
                        watermarkPath = watermarkPath
                )
}

object ClipClipboard {
        private var copiedClip: TimelineClip? = null

        fun copy(clip: TimelineClip) {
                copiedClip = clip.deepCopy()
        }

        fun get(): TimelineClip? {
                return copiedClip?.deepCopy()
        }

        fun hasClip(): Boolean = copiedClip != null
}
