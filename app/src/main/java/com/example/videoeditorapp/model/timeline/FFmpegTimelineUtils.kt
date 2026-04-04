package com.example.videoeditorapp.model.timeline

import android.media.MediaMetadataRetriever
import java.io.File

object FFmpegTimelineUtils {

        /**
         * Generate a comprehensive FFmpeg command that exports a timeline project with support for
         * VIDEO, AUDIO, and OVERLAY tracks.
         *
         * @param project The timeline project to export
         * @param outputPath Output file path
         * @param targetW Target video width
         * @param targetH Target video height
         * @param blackVideoPath Path to black video file for gaps
         * @return FFmpeg command string
         */
        fun generateTimelineExportArguments(
                context: android.content.Context,
                project: TimelineProject,
                outputPath: String,
                targetW: Int,
                targetH: Int
        ): Array<String> {
                val totalDurationMs = project.getDurationMs().coerceAtLeast(100L)
                val totalDurationSec =
                        String.format(java.util.Locale.US, "%g", totalDurationMs / 1000.0)

                val filterParts = mutableListOf<String>()
                val inputs = mutableListOf<Pair<String, Boolean>>()
                val inputPathToIndex = mutableMapOf<Pair<String, Boolean>, Int>()
                val videoUsageCount = mutableMapOf<Int, Int>()
                val audioUsageCount = mutableMapOf<Int, Int>()

                fun getOrAddInput(path: String, shouldLoop: Boolean): Int {
                        return inputPathToIndex.getOrPut(path to shouldLoop) {
                                val idx = inputs.size
                                inputs.add(path to shouldLoop)
                                idx
                        }
                }

                fun registerUsage(path: String, type: String, shouldLoop: Boolean) {
                        val idx = getOrAddInput(path, shouldLoop)
                        if (type.contains("V"))
                                videoUsageCount[idx] = (videoUsageCount[idx] ?: 0) + 1
                        if (type.contains("A"))
                                audioUsageCount[idx] = (audioUsageCount[idx] ?: 0) + 1
                }

                // 1. PRE-SCAN: Identify all inputs and count usages
                val videoTracks = project.tracks.filter { it.type == TrackType.VIDEO }
                val videoAudioTracks = project.tracks.filter { it.type == TrackType.VIDEO_AUDIO }
                val overlayTracks = project.tracks.filter { it.type == TrackType.OVERLAY }
                val audioTracks = project.tracks.filter { it.type == TrackType.AUDIO }

                // Scan Video Tracks
                (videoTracks + videoAudioTracks).forEach { track ->
                        track.clips.forEach { clip ->
                                resolvePath(context, clip.filePath)?.let { path ->
                                        val isImg = isImageFile(path)
                                        registerUsage(path, "V", isImg)
                                        if (hasAudioStream(path)) registerUsage(path, "A", false)
                                }
                        }
                }

                // Scan Overlay Tracks
                overlayTracks.forEach { track ->
                        track.clips.forEach { clip ->
                                if (clip.type != ClipType.TEXT) {
                                        resolvePath(context, clip.filePath)?.let { path ->
                                                val isImg =
                                                        isImageFile(path) ||
                                                                clip.type == ClipType.IMAGE ||
                                                                clip.type == ClipType.STICKER
                                                registerUsage(path, "V", isImg)
                                        }
                                }
                        }
                }

                // Scan Audio Tracks
                audioTracks.forEach { track ->
                        track.clips.forEach { clip ->
                                resolvePath(context, clip.filePath)?.let { path ->
                                        if (hasAudioStream(path)) registerUsage(path, "A", false)
                                }
                        }
                }

                // Identify Watermark
                var watermarkInputIndex = -1
                if (project.watermarkPath != null) {
                        resolvePath(context, project.watermarkPath!!)?.let { path ->
                                watermarkInputIndex = getOrAddInput(path, false)
                                videoUsageCount[watermarkInputIndex] = 1
                        }
                }

                // 2. GENERATE SPLIT FILTERS
                videoUsageCount.forEach { (idx, count) ->
                        if (count > 1) {
                                val labels = (1..count).joinToString("") { "[v_in_${idx}_$it]" }
                                filterParts.add("[$idx:v]split=$count$labels")
                        }
                }
                audioUsageCount.forEach { (idx, count) ->
                        if (count > 1) {
                                val labels = (1..count).joinToString("") { "[a_in_${idx}_$it]" }
                                filterParts.add("[$idx:a]asplit=$count$labels")
                        }
                }

                val lastVideoUsage = mutableMapOf<Int, Int>()
                val lastAudioUsage = mutableMapOf<Int, Int>()

                fun getVLabel(idx: Int): String {
                        val total = videoUsageCount[idx] ?: 1
                        return if (total > 1) {
                                val next = (lastVideoUsage[idx] ?: 0) + 1
                                lastVideoUsage[idx] = next
                                "[v_in_${idx}_$next]"
                        } else "[$idx:v]"
                }

                fun getALabel(idx: Int): String {
                        val total = audioUsageCount[idx] ?: 1
                        return if (total > 1) {
                                val next = (lastAudioUsage[idx] ?: 0) + 1
                                lastAudioUsage[idx] = next
                                "[a_in_${idx}_$next]"
                        } else "[$idx:a]"
                }

                // 3. GENERATE FILTERS
                filterParts.add(
                        "color=black:s=${targetW}x${targetH}:d=$totalDurationSec:r=30[vbase]"
                )

                val audioInputs = mutableListOf<String>()
                var videoLabel = "vbase"

                // ---------- STEP 1: VIDEO TRACKS ----------
                (videoTracks + videoAudioTracks).forEach { videoTrack ->
                        videoTrack.clips.sortedBy { it.startTimeMs }.forEach step1ClipLoop@{
                                videoClip ->
                                android.util.Log.d(
                                        "FFmpegTimelineUtils",
                                        "Processing Video Clip: ${videoClip.id} (path=${videoClip.filePath})"
                                )
                                val resolvedPath = resolvePath(context, videoClip.filePath)
                                if (resolvedPath == null) {
                                        android.util.Log.e(
                                                "FFmpegTimelineUtils",
                                                "File not found or unresolvable: ${videoClip.filePath}"
                                        )
                                        return@step1ClipLoop
                                }
                                val file = File(resolvedPath)
                                val isImg = isImageFile(file.absolutePath)
                                val curIdx = getOrAddInput(file.absolutePath, isImg)

                                val startSec =
                                        String.format(
                                                java.util.Locale.US,
                                                "%g",
                                                videoClip.startTimeMs / 1000.0
                                        )
                                val sourceStartSec =
                                        String.format(
                                                java.util.Locale.US,
                                                "%g",
                                                videoClip.sourceStartTimeMs / 1000.0
                                        )
                                val endSec =
                                        String.format(
                                                java.util.Locale.US,
                                                "%g",
                                                (videoClip.startTimeMs + videoClip.durationMs) /
                                                        1000.0
                                        )

                                // 1. Base Transformation: Orientation, Trimming, Scaling, Padding,
                                // FPS normalization
                                val orientation = getOrientation(file.absolutePath)
                                var transposeFilter =
                                        when (orientation) {
                                                90 -> "transpose=1,"
                                                180 -> "transpose=2,transpose=2,"
                                                270 -> "transpose=2,"
                                                else -> ""
                                        }

                                var videoFilters =
                                        "${transposeFilter}fps=30,scale=$targetW:$targetH:force_original_aspect_ratio=decrease,pad=$targetW:$targetH:(ow-iw)/2:(oh-ih)/2,setsar=1"

                                // 2. Apply Custom Effects
                                videoClip.effects.forEach { videoEffect ->
                                        when (videoEffect.type.uppercase()) {
                                                "BRIGHTNESS" -> {
                                                        val b =
                                                                (videoEffect.intensity - 1.0f)
                                                                        .coerceIn(-1.0f, 1.0f)
                                                        videoFilters += ",eq=brightness=$b"
                                                }
                                                "CONTRAST" -> {
                                                        videoFilters +=
                                                                ",eq=contrast=${videoEffect.intensity}"
                                                }
                                                "SATURATION" -> {
                                                        videoFilters +=
                                                                ",eq=saturation=${videoEffect.intensity}"
                                                }
                                                "HUE" -> {
                                                        val h = videoEffect.intensity * 360
                                                        videoFilters += ",hue=h=$h"
                                                }
                                                "TEMPERATURE" -> {
                                                        // Simplified color temperature adjustment
                                                        val r =
                                                                1.0 +
                                                                        (videoEffect.intensity -
                                                                                1.0) * 0.2
                                                        val b =
                                                                1.0 -
                                                                        (videoEffect.intensity -
                                                                                1.0) * 0.2
                                                        videoFilters += ",colorlevels=rim=$r:bim=$b"
                                                }
                                                "GRAYSCALE" -> {
                                                        videoFilters += ",hue=s=0"
                                                }
                                                "NOIR" -> {
                                                        videoFilters +=
                                                                ",hue=s=0,eq=contrast=1.5:brightness=-0.1"
                                                }
                                                "SEPIA" -> {
                                                        videoFilters +=
                                                                ",colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"
                                                }
                                                "VINTAGE" -> {
                                                        videoFilters +=
                                                                ",curves=preset=vintage,vignette=angle=PI/4"
                                                }
                                                "COOL" -> {
                                                        videoFilters +=
                                                                ",colortemperature=temperature=4000"
                                                }
                                                "WARM" -> {
                                                        videoFilters +=
                                                                ",colortemperature=temperature=9000"
                                                }
                                                "VIGNETTE" -> {
                                                        val angle =
                                                                (videoEffect.intensity *
                                                                                (Math.PI / 2))
                                                                        .coerceAtLeast(0.1)
                                                        videoFilters += ",vignette=angle=$angle"
                                                }
                                                "GRAIN" -> {
                                                        val noise =
                                                                (videoEffect.intensity * 50).toInt()
                                                        videoFilters +=
                                                                ",noise=alls=$noise:allf=t+u"
                                                }
                                                "BLUR" -> {
                                                        val sigma =
                                                                (videoEffect.intensity * 10)
                                                                        .coerceAtLeast(1f)
                                                        videoFilters += ",boxblur=$sigma:1"
                                                }
                                                "SHARPEN" -> {
                                                        val amt = videoEffect.intensity * 2.0
                                                        videoFilters += ",unsharp=5:5:$amt:5:5:0.0"
                                                }
                                                "GLITCH" -> {
                                                        // Simplified glitch: bit of noise and color
                                                        // shift
                                                        videoFilters +=
                                                                ",noise=alls=20:allf=t+u,hue=h=20:s=1.2"
                                                }
                                                "SHAKE" -> {
                                                        // Simplified shake: random crop/zoom
                                                        videoFilters +=
                                                                ",zoompan=z='min(zoom+0.001,1.1)':d=1:x='iw/2-(iw/zoom/2)+bitand(t*100,10)':y='ih/2-(ih/zoom/2)+bitand(t*100,7)'"
                                                }
                                                "OLD_MOVIE" -> {
                                                        videoFilters +=
                                                                ",noise=alls=30:allf=t+u,vignette=angle=PI/4,colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"
                                                }
                                        }
                                }

                                // 2.5 Apply Video Fades
                                val vfFadeIn =
                                        videoClip.metadata["VIDEO_FADE_IN"]?.toLongOrNull() ?: 0L
                                val vfFadeOut =
                                        videoClip.metadata["VIDEO_FADE_OUT"]?.toLongOrNull() ?: 0L
                                if (vfFadeIn > 0) {
                                        val durS =
                                                String.format(
                                                        java.util.Locale.US,
                                                        "%g",
                                                        vfFadeIn / 1000.0
                                                )
                                        videoFilters += ",fade=t=in:st=0:d=$durS"
                                }
                                if (vfFadeOut > 0) {
                                        val durS =
                                                String.format(
                                                        java.util.Locale.US,
                                                        "%g",
                                                        vfFadeOut / 1000.0
                                                )
                                        val startS =
                                                String.format(
                                                        java.util.Locale.US,
                                                        "%g",
                                                        (videoClip.durationMs - vfFadeOut) / 1000.0
                                                )
                                        videoFilters += ",fade=t=out:st=$startS:d=$durS"
                                }

                                // 2.6 Reverse & Crop
                                if (videoClip.metadata["REVERSED"] == "true") {
                                        videoFilters += ",reverse"
                                }
                                val cropPreset = videoClip.metadata["CROP_PRESET"] ?: "ORIGINAL"
                                when (cropPreset) {
                                        "1:1" -> videoFilters += ",crop=min(iw,ih):min(iw,ih)"
                                        "16:9" -> videoFilters += ",crop=iw:iw*9/16:0:(ih-oh)/2"
                                        "9:16" -> videoFilters += ",crop=ih*9/16:ih:(iw-ow)/2:0"
                                        "4:5" -> videoFilters += ",crop=iw:iw*1.25:0:(ih-oh)/2"
                                }

                                // 3. Set PTS for timeline placement
                                if (videoClip.startTimeMs > 0) {
                                        videoFilters += ",setpts=PTS-STARTPTS+${startSec}/TB"
                                } else {
                                        videoFilters += ",setpts=PTS-STARTPTS"
                                }

                                // 4. Handle Text Overlays (Moved to common processing)
                                val vIn = getVLabel(curIdx)
                                val vOut = "v_${curIdx}_${videoClip.id.take(4)}"
                                filterParts.add("$vIn$videoFilters[$vOut]")

                                val nextLabel = "v_m_${curIdx}_${videoClip.id.take(4)}"
                                filterParts.add(
                                        "[$videoLabel][$vOut]overlay=enable='between(t,$startSec,$endSec)':x=0:y=0[$nextLabel]"
                                )
                                videoLabel = nextLabel

                                if (hasAudioStream(file.absolutePath)) {
                                        val trimEndSec =
                                                String.format(
                                                        java.util.Locale.US,
                                                        "%g",
                                                        (videoClip.sourceStartTimeMs +
                                                                videoClip.durationMs) / 1000.0
                                                )
                                        val vol =
                                                String.format(
                                                        java.util.Locale.US,
                                                        "%g",
                                                        videoClip.audioVolume
                                                )
                                        var audioFilters =
                                                "atrim=$sourceStartSec:$trimEndSec,asetpts=PTS-STARTPTS,volume=$vol"

                                        if (videoClip.metadata["REVERSED"] == "true") {
                                                audioFilters += ",areverse"
                                        }

                                        // Apply Audio Fades
                                        val afFadeIn =
                                                videoClip.metadata["AUDIO_FADE_IN"]?.toLongOrNull()
                                                        ?: 0L
                                        val afFadeOut =
                                                videoClip.metadata["AUDIO_FADE_OUT"]?.toLongOrNull()
                                                        ?: 0L
                                        if (afFadeIn > 0) {
                                                val durS =
                                                        String.format(
                                                                java.util.Locale.US,
                                                                "%g",
                                                                afFadeIn / 1000.0
                                                        )
                                                audioFilters += ",afade=t=in:st=0:d=$durS"
                                        }
                                        if (afFadeOut > 0) {
                                                val durS =
                                                        String.format(
                                                                java.util.Locale.US,
                                                                "%g",
                                                                afFadeOut / 1000.0
                                                        )
                                                val startS =
                                                        String.format(
                                                                java.util.Locale.US,
                                                                "%g",
                                                                (videoClip.durationMs - afFadeOut) /
                                                                        1000.0
                                                        )
                                                audioFilters += ",afade=t=out:st=$startS:d=$durS"
                                        }

                                        // Apply Audio Effects (Echo, Reverb, Pitch)
                                        if (videoClip.audioSettings["ECHO"] == "true") {
                                                audioFilters += ",aecho=0.8:0.88:60:0.4"
                                        }
                                        if (videoClip.audioSettings["REVERB"] == "true") {
                                                audioFilters += ",aecho=0.8:0.9:1000:0.3"
                                        }

                                        val delayPart =
                                                if (videoClip.startTimeMs > 0)
                                                        ",adelay=${videoClip.startTimeMs}:all=1"
                                                else ""

                                        val aIn = getALabel(curIdx)
                                        val aOut = "a_${curIdx}_${videoClip.id.take(4)}"
                                        filterParts.add("$aIn$audioFilters$delayPart[$aOut]")
                                        audioInputs.add("[$aOut]")
                                }
                        }
                }

                // ---------- STEP 2: OVERLAY TRACKS ----------
                overlayTracks.forEach { overlayTrack ->
                        overlayTrack.clips.sortedBy { it.startTimeMs }.forEach step2OverlayLoop@{
                                overlayClip ->
                                // ... processing ...
                                val resolvedPath = resolvePath(context, overlayClip.filePath)
                                val curIdx =
                                        if (resolvedPath != null &&
                                                        overlayClip.type != ClipType.TEXT
                                        ) {
                                                val isImg =
                                                        isImageFile(resolvedPath) ||
                                                                overlayClip.type ==
                                                                        ClipType.IMAGE ||
                                                                overlayClip.type == ClipType.STICKER
                                                getOrAddInput(resolvedPath, isImg)
                                        } else -1

                                val label = "ovr_${overlayClip.id.take(4)}"

                                val startSec =
                                        String.format(
                                                java.util.Locale.US,
                                                "%g",
                                                overlayClip.startTimeMs / 1000.0
                                        )
                                val endSec =
                                        String.format(
                                                java.util.Locale.US,
                                                "%g",
                                                (overlayClip.startTimeMs + overlayClip.durationMs) /
                                                        1000.0
                                        )
                                val x = (overlayClip.overlayX * targetW).toInt()
                                val y = (overlayClip.overlayY * targetH).toInt()
                                val sw =
                                        (200 * overlayClip.overlayScale * (targetW / 1080.0))
                                                .toInt()
                                                .coerceAtLeast(10)
                                val sh =
                                        (200 * overlayClip.overlayScale * (targetH / 1920.0))
                                                .toInt()
                                                .coerceAtLeast(10)

                                val isImage =
                                        overlayClip.filePath.lowercase().let {
                                                it.endsWith(".png") ||
                                                        it.endsWith(".jpg") ||
                                                        it.endsWith(".jpeg") ||
                                                        it.endsWith(".webp") ||
                                                        it.endsWith(".gif")
                                        }

                                if ((isImage ||
                                                overlayClip.type == ClipType.IMAGE ||
                                                overlayClip.type == ClipType.STICKER ||
                                                overlayClip.type == ClipType.EMOJI) && curIdx != -1
                                ) {
                                        val vIn = getVLabel(curIdx)
                                        filterParts.add(
                                                "${vIn}loop=loop=-1:size=1:start=0,scale=$sw:$sh,format=rgba[$label]"
                                        )
                                } else if ((overlayClip.type == ClipType.VIDEO ||
                                                overlayClip.type == ClipType.GIF) && curIdx != -1
                                ) {
                                        // Treat GIFs as videos for potential animation support
                                        val vIn = getVLabel(curIdx)
                                        val ptsPart =
                                                if (overlayClip.startTimeMs > 0)
                                                        ",setpts=PTS-STARTPTS+${startSec}/TB"
                                                else ",setpts=PTS-STARTPTS"
                                        filterParts.add(
                                                "${vIn}fps=30,scale=$sw:$sh,format=rgba$ptsPart[$label]"
                                        )
                                } else if (overlayClip.type == ClipType.TEXT) {
                                        // Improved Text Overlay with Animation support
                                        val animFade =
                                                (overlayClip.textSettings["ANIM_FADE"]
                                                        ?.toLongOrNull()
                                                        ?: 0L) / 1000.0
                                        val animSlide =
                                                overlayClip.textSettings["ANIM_SLIDE"] ?: "NONE"

                                        val text =
                                                overlayClip.textSettings["text"]
                                                        ?: overlayClip.textSettings["TEXT"] ?: ""
                                        val color =
                                                overlayClip.textSettings["color"]
                                                        ?: overlayClip.textSettings["COLOR"]
                                                                ?: "white"
                                        val size =
                                                overlayClip.textSettings["size"]
                                                        ?: overlayClip.textSettings["SIZE"] ?: "48"
                                        val fontPath = "/system/fonts/Roboto-Regular.ttf"
                                        val align = overlayClip.textSettings["ALIGN"] ?: "CENTER"

                                        var xPos =
                                                when (align.uppercase()) {
                                                        "LEFT" -> "20"
                                                        "RIGHT" -> "w-tw-20"
                                                        else -> "(w-tw)/2"
                                                }
                                        var xExpr = xPos
                                        var yExpr = "(h-th)/2"

                                        if (animSlide == "LEFT_TO_RIGHT") {
                                                xExpr =
                                                        "if(between(t,$startSec,$endSec), (t-$startSec)*w/2 - tw, $xPos)"
                                        } else if (animSlide == "BOTTOM_UP") {
                                                yExpr =
                                                        "if(between(t,$startSec,$endSec), h - (t-$startSec)*h/2, (h-th)/2)"
                                        }

                                        val alphaExpr =
                                                if (animFade > 0) {
                                                        ":alpha='if(lt(t,$startSec+$animFade),(t-$startSec)/$animFade,if(gt(t,$endSec-$animFade),($endSec-t)/$animFade,1))'"
                                                } else ""

                                        filterParts.add(
                                                "color=c=black@0:s=${targetW}x${targetH}:d=$totalDurationSec,drawtext=fontfile=$fontPath:text='${text.replace("'", "\\'")}':fontcolor=$color:fontsize=$size:x='$xExpr':y='$yExpr'$alphaExpr,fps=30,format=rgba[$label]"
                                        )
                                } else {
                                        return@step2OverlayLoop // Skip if no file and not text
                                }

                                val nextLabel = "v_o_${curIdx}_${overlayClip.id.take(4)}"
                                // Overlay with rotation and opacity support
                                val opacity = overlayClip.overlayOpacity.coerceIn(0f, 1f)

                                // Rotated overlay logic: Apply rotate filter to overlay source
                                // before overlaying
                                var overlayFilters = "format=rgba"

                                // Apply Effects to Overlay
                                overlayClip.effects.forEach { overlayEffect ->
                                        when (overlayEffect.type.uppercase()) {
                                                "BRIGHTNESS" -> {
                                                        val b =
                                                                (overlayEffect.intensity - 1.0f)
                                                                        .coerceIn(-1.0f, 1.0f)
                                                        overlayFilters += ",eq=brightness=$b"
                                                }
                                                "CONTRAST" -> {
                                                        overlayFilters +=
                                                                ",eq=contrast=${overlayEffect.intensity}"
                                                }
                                                "SATURATION" -> {
                                                        overlayFilters +=
                                                                ",eq=saturation=${overlayEffect.intensity}"
                                                }
                                                "HUE" -> {
                                                        val h = overlayEffect.intensity * 360
                                                        overlayFilters += ",hue=h=$h"
                                                }
                                                "TEMPERATURE" -> {
                                                        val r =
                                                                1.0 +
                                                                        (overlayEffect.intensity -
                                                                                1.0) * 0.2
                                                        val b =
                                                                1.0 -
                                                                        (overlayEffect.intensity -
                                                                                1.0) * 0.2
                                                        overlayFilters +=
                                                                ",colorlevels=rim=$r:bim=$b"
                                                }
                                                "GRAYSCALE" -> {
                                                        overlayFilters += ",hue=s=0"
                                                }
                                                "BLUR" -> {
                                                        val sigma =
                                                                (overlayEffect.intensity * 10)
                                                                        .coerceAtLeast(1f)
                                                        overlayFilters += ",boxblur=$sigma:1"
                                                }
                                                "SHARPEN" -> {
                                                        overlayFilters += ",unsharp=5:5:1.0:5:5:0.0"
                                                }
                                                "SEPIA" ->
                                                        overlayFilters +=
                                                                ",colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"
                                                "WARM" ->
                                                        overlayFilters +=
                                                                ",colorbalance=rs=.1:gs=-.1:bs=-.2"
                                                "COOL" ->
                                                        overlayFilters +=
                                                                ",colorbalance=rs=-.1:gs=-.1:bs=.2"
                                                "TEAL_ORANGE" ->
                                                        overlayFilters +=
                                                                ",colorbalance=rs=-.2:gs=.1:bs=.3:rm=.2:gm=-.1:bm=-.2:rh=.2:gh=.1:bh=-.2"
                                        }
                                }

                                if (overlayClip.overlayRotation != 0f) {
                                        overlayFilters +=
                                                ",rotate=${overlayClip.overlayRotation}*PI/180:ow=max(iw,ih):oh=max(iw,ih):c=none"
                                }

                                filterParts.add("[$label]$overlayFilters[${label}_rot]")

                                filterParts.add(
                                        "[$videoLabel][${label}_rot]overlay=x='$x-w/2':y='$y-h/2':alpha=$opacity:enable='between(t,$startSec,$endSec)'[$nextLabel]"
                                )
                                android.util.Log.v(
                                        "FFmpegTimelineUtils",
                                        "Overlay Chain: [$videoLabel] -> [$nextLabel] with filters: $overlayFilters"
                                )
                                videoLabel = nextLabel
                        }
                }

                // ---------- STEP 3: AUDIO TRACKS ----------
                audioTracks.forEach { audioTrack ->
                        audioTrack.clips.sortedBy { it.startTimeMs }.forEach step3AudioLoop@{
                                audioClip ->
                                android.util.Log.d(
                                        "FFmpegTimelineUtils",
                                        "Processing Audio Clip: ${audioClip.id} (path=${audioClip.filePath})"
                                )
                                val resolvedPath = resolvePath(context, audioClip.filePath)
                                if (resolvedPath == null) return@step3AudioLoop
                                val curIdx = getOrAddInput(resolvedPath, false)
                                if (!hasAudioStream(resolvedPath)) return@step3AudioLoop

                                val sourceStartSec =
                                        String.format(
                                                java.util.Locale.US,
                                                "%g",
                                                audioClip.sourceStartTimeMs / 1000.0
                                        )
                                val trimEndSec =
                                        String.format(
                                                java.util.Locale.US,
                                                "%g",
                                                (audioClip.sourceStartTimeMs +
                                                        audioClip.durationMs) / 1000.0
                                        )
                                val vol =
                                        String.format(
                                                java.util.Locale.US,
                                                "%g",
                                                audioClip.audioVolume
                                        )
                                val delayPart =
                                        if (audioClip.startTimeMs > 0)
                                                ",adelay=${audioClip.startTimeMs}:all=1"
                                        else ""

                                val aIn = getALabel(curIdx)
                                val aOut = "a_${curIdx}_${audioClip.id.take(4)}"
                                filterParts.add(
                                        "$aIn" +
                                                "atrim=$sourceStartSec:$trimEndSec,asetpts=PTS-STARTPTS,volume=$vol" +
                                                "${if (audioClip.metadata["REVERSED"] == "true") ",areverse" else ""}" +
                                                "${if (audioClip.metadata["PITCH"] != null) ",rubberband=pitch=${audioClip.metadata["PITCH"]}" else ""}" +
                                                "$delayPart[$aOut]"
                                )
                                audioInputs.add("[$aOut]")
                        }
                }

                val audioMixLabel =
                        if (audioInputs.isNotEmpty()) {
                                filterParts.add(
                                        "${audioInputs.joinToString("")}amix=inputs=${audioInputs.size}:duration=longest:dropout_transition=2[aout]"
                                )
                                "aout"
                        } else {
                                filterParts.add(
                                        "anullsrc=channel_layout=stereo:sample_rate=44100[aout]"
                                )
                                "aout"
                        }

                val args = mutableListOf<String>()
                args.add("-y")

                // Watermark index and path handled in pre-scan

                android.util.Log.d("FFmpegTimelineUtils", "Generated Inputs: ${inputs.size}")
                inputs.forEachIndexed { index, (path, loop) ->
                        android.util.Log.v(
                                "FFmpegTimelineUtils",
                                "Input #$index: $path (loop=$loop)"
                        )
                }

                inputs.forEach { (path, shouldLoop) ->
                        if (shouldLoop) {
                                args.add("-loop")
                                args.add("1")
                        }
                        args.add("-i")
                        args.add(path)
                }

                // Append watermark overlay to filter chain if exists
                if (watermarkInputIndex != -1) {
                        val finalWLabel = "v_final_watermark"
                        // Scale watermark to 15% of width, place in bottom right
                        val waterW = (targetW * 0.15).toInt().coerceAtLeast(64)
                        val vIn = getVLabel(watermarkInputIndex)
                        filterParts.add("${vIn}scale=$waterW:-1[vwm]")
                        filterParts.add("[$videoLabel][vwm]overlay=W-w-20:H-h-20[$finalWLabel]")
                        videoLabel = finalWLabel
                }

                val filterComplex = filterParts.joinToString(";")
                android.util.Log.d(
                        "FFmpegTimelineUtils",
                        "Filter Complex Length: ${filterComplex.length}"
                )

                args.add("-filter_complex")
                args.add(filterComplex)
                args.add("-map")
                args.add("[$videoLabel]")
                args.add("-map")
                args.add("[$audioMixLabel]")
                args.add("-c:v")
                val codec = project.metadata["CODEC"] ?: "libx264"
                args.add(codec)
                if (codec == "libx264") {
                        args.add("-preset")
                        args.add("ultrafast")
                        args.add("-pix_fmt")
                        args.add("yuv420p")
                }

                // Dynamic bitrate based on quality
                val bitrate =
                        when (project.renderQuality) {
                                "Low" -> "1.5M"
                                "Medium" -> "5M"
                                "High" -> "12M"
                                else -> "5M"
                        }
                args.add("-b:v")
                args.add(bitrate)

                args.add("-c:a")
                args.add("aac")
                args.add("-b:a")
                val audioBitrate =
                        when (project.metadata["AUDIO_QUALITY"]) {
                                "128K" -> "128k"
                                "192K" -> "192k"
                                "320K" -> "320k"
                                else -> "192k"
                        }
                args.add(audioBitrate)
                args.add("-t")
                args.add(totalDurationSec)
                args.add("-movflags")
                args.add("+faststart")
                args.add("-pix_fmt")
                args.add("yuv420p")
                args.add(outputPath)

                val finalArgs = args.toTypedArray()
                android.util.Log.i(
                        "FFmpegTimelineUtils",
                        "Final Arguments Count: ${finalArgs.size}"
                )
                android.util.Log.i(
                        "FFmpegTimelineUtils",
                        "Command: ffmpeg ${finalArgs.joinToString(" ")}"
                )

                return finalArgs
        }

        private fun resolvePath(context: android.content.Context, path: String): String? {
                val resolved =
                        com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(
                                context,
                                path
                        )
                if (resolved == null) {
                        android.util.Log.e("FFmpegTimelineUtils", "Failed to resolve path: $path")
                } else {
                        val file = File(resolved)
                        if (!file.exists()) {
                                android.util.Log.e(
                                        "FFmpegTimelineUtils",
                                        "Resolved path does not exist on disk: $resolved"
                                )
                        } else {
                                android.util.Log.d(
                                        "FFmpegTimelineUtils",
                                        "Resolved: $path -> $resolved (${file.length()} bytes)"
                                )
                        }
                }
                return resolved
        }

        @JvmStatic
        fun hasAudioStream(path: String): Boolean {
                return try {
                        val mmr = MediaMetadataRetriever()
                        mmr.setDataSource(path)
                        val hasAudio =
                                mmr.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
                                ) == "yes"
                        mmr.release()
                        hasAudio
                } catch (e: Exception) {
                        false
                }
        }

        @Deprecated(
                "Use generateTimelineExportArguments instead",
                ReplaceWith("generateTimelineExportArguments")
        )
        fun generateTimelineExportCommand(
                context: android.content.Context,
                project: TimelineProject,
                outputPath: String,
                targetW: Int,
                targetH: Int
        ): String {
                return generateTimelineExportArguments(
                                context,
                                project,
                                outputPath,
                                targetW,
                                targetH
                        )
                        .joinToString(" ")
        }

        @Deprecated(
                "Use generateTimelineExportArguments instead",
                ReplaceWith("generateTimelineExportArguments")
        )
        fun generateConcatCommand(
                context: android.content.Context,
                project: TimelineProject,
                outputPath: String,
                targetW: Int,
                targetH: Int
        ): String {
                return generateTimelineExportArguments(
                                context,
                                project,
                                outputPath,
                                targetW,
                                targetH
                        )
                        .joinToString(" ")
        }

        private fun getOrientation(path: String): Int {
                return try {
                        val mmr = MediaMetadataRetriever()
                        mmr.setDataSource(path)
                        val rotation =
                                mmr.extractMetadata(
                                                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                                        )
                                        ?.toInt()
                                        ?: 0
                        mmr.release()
                        rotation
                } catch (e: Exception) {
                        0
                }
        }

        private fun isImageFile(path: String): Boolean {
                val low = path.lowercase()
                return low.endsWith(".png") ||
                        low.endsWith(".jpg") ||
                        low.endsWith(".jpeg") ||
                        low.endsWith(".webp") ||
                        low.endsWith(".gif")
        }
}
