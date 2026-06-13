package com.example.videoeditorapp.ui.timeline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.videoeditorapp.R
import com.example.videoeditorapp.model.timeline.*
import com.example.videoeditorapp.utils.AudioWaveformUtils
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave

enum class TimelineTool {
    SELECT,
    SPLIT,
    TRIM,
    SPEED,
    VOLUME,
}

class TimelineView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        View(context, attrs, defStyleAttr) {
    private val timelineMenuRect = RectF()

    var onTimelineMenuClick: ((Float, Float) -> Unit)? = null
    private val videoDrawable by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_video_camera)
    }

    private val audioDrawable by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_audio_label)
    }

    private val overlayDrawable by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_layers)
    }

    private val textDrawable by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_text)
    }

    private val stickerDrawable by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_sticker)
    }

    private fun drawTrackIcon(
        canvas: Canvas,
        track: TimelineTrack,
        iconX: Float,
        iconY: Float,
        iconSize: Float
    ) {

        val firstClip = track.clips.firstOrNull()

        val drawable = when {

            firstClip?.type == ClipType.TEXT ->
                textDrawable

            firstClip?.type == ClipType.STICKER ->
                stickerDrawable

            track.type == TrackType.AUDIO ->
                audioDrawable

            track.type == TrackType.OVERLAY ->
                overlayDrawable

            else ->
                videoDrawable
        }

        drawable?.let { dr ->

            dr.setBounds(
                iconX.toInt(),
                iconY.toInt(),
                (iconX + iconSize).toInt(),
                (iconY + iconSize).toInt()
            )

            dr.setTint(
                if (track.clips.any { it.id == selectedClip?.id })
                    iconTintSelected
                else
                    iconTintUnselected
            )

            dr.draw(canvas)
        }
    }
    private val reusableTooltipRect = RectF()

    private val TAP_SLOP_PX = 18f

    private var pixelsPerSecond = 60f
    private val trackHeight = 110f // Boosted from 100f
    private val trackSpacing = 4f
    private val rulerHeight = 48f
    private val snapThresholdPx = 60f
    private val HEADER_WIDTH = 70f
    private val MIN_PPS = 20f
    private val MAX_PPS = 500f
    private val reusableHeadRect = RectF()
    private val reusableClipRect = RectF()



    private companion object {


        const val COLOR_GOLD = 0xFFFFD700.toInt()

        const val COLOR_ACCENT = 0xFF00D2D3.toInt()

        const val COLOR_RED = 0xFFFF5252.toInt()

        const val COLOR_BG = 0xFF121212.toInt()

        const val COLOR_HEADER_BG = 0xFF1A1A1A.toInt()

        const val COLOR_BORDER = 0x1AFFFFFF

        const val COLOR_FILLER = 0x4DFFFFFF

        const val COLOR_ACCENT_GLOW = 0xCC00D2D3.toInt()
        const val CLIP_RADIUS = 10f
        const val CLIP_VERTICAL_PADDING = 4f
    }

    // ---------- ENGINES ----------
    private val scroller = android.widget.OverScroller(context)
    private val scaleDetector =
            android.view.ScaleGestureDetector(
                    context,
                    object : android.view.ScaleGestureDetector.OnScaleGestureListener {
                        override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                            val oldPps = pixelsPerSecond
                            pixelsPerSecond *= detector.scaleFactor
                            pixelsPerSecond = pixelsPerSecond.coerceIn(MIN_PPS, MAX_PPS)

                            // Adjust scrollXOffset to keep the focus point stable under fingers
                            val focusX = detector.focusX
                            val timelineXAtFocus = (scrollXOffset + focusX - HEADER_WIDTH) / oldPps
                            scrollXOffset =
                                    (timelineXAtFocus * pixelsPerSecond) - (focusX - HEADER_WIDTH)

                            // Clip bounds to prevent drifting too far
                            val maxContentWidth =
                                    (project?.getDurationMs() ?: 0L) / 1000f * pixelsPerSecond
                            scrollXOffset =
                                    scrollXOffset.coerceIn(
                                            0f,
                                            maxOf(0f, maxContentWidth - width + 200f)
                                    )

                            invalidate()
                            return true
                        }
                        override fun onScaleBegin(
                                detector: android.view.ScaleGestureDetector
                        ): Boolean = true
                        override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {}
                    }
            )

    private val gestureDetector =
            android.view.GestureDetector(
                    context,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onFling(
                                e1: android.view.MotionEvent?,
                                e2: android.view.MotionEvent,
                                velocityX: Float,
                                velocityY: Float
                        ): Boolean {
                            if (isScrollingTimeline) {
                                scroller.fling(
                                        scrollXOffset.toInt(),
                                        scrollYOffset.toInt(),
                                        (-velocityX).toInt(),
                                        (-velocityY).toInt(),
                                        0,
                                        Int.MAX_VALUE,
                                        0,
                                        maxScrollY.toInt()
                                )
                                postInvalidateOnAnimation()
                                return true
                            }
                            return false
                        }
                    }
            )


    private val linkedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.GREEN
    }
    private val badgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 160
        }

    private val badgeTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

    private val reusableBadgeRect = RectF()

    data class EllipsizeKey(val clipId: String, val widthPx: Int)
    data class TransitionZone(val nextClip: TimelineClip, val rect: RectF)
    private val transitionZones = mutableListOf<TransitionZone>()
    var onTransitionHandleClicked: ((TimelineClip) -> Unit)? = null
    private val ellipsizeCache = HashMap<EllipsizeKey, String>()
    private val timeFormatCache = HashMap<Long, String>()

    private fun getCachedEllipsizedTitle(clipId: String, text: String, paint: android.text.TextPaint, width: Float): String {
        if (ellipsizeCache.size > 1000) {
            ellipsizeCache.clear()
        }
        val roundedWidth = width.toInt()
        val key = EllipsizeKey(clipId, roundedWidth)
        return ellipsizeCache.getOrPut(key) {
            TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()
        }
    }

    private fun getCachedTime(timeMs: Long): String {
        if (timeFormatCache.size > 1000) {
            timeFormatCache.clear()
        }
        return timeFormatCache.getOrPut(timeMs) {
            com.example.videoeditorapp.utils.ViewUtils.formatTime(timeMs)
        }
    }
    private val unlinkedClipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = COLOR_RED
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }
    private val longPressGestureDetector =
            android.view.GestureDetector(
                    context,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: android.view.MotionEvent) {
                            val found = findClipAt(e.x, e.y)
                            val track =
                                project?.tracks
                                    ?.firstOrNull { found in it.clips }

                            if (track?.type != TrackType.VIDEO) {
                                return
                            }
                            found?.let { clip ->
                                isDraggingClip = true
                                draggingClip = clip
                                dragStartX = e.x
                                dragStartClipTime = clip.startTimeMs
                                performHapticFeedback(
                                        android.view.HapticFeedbackConstants.LONG_PRESS
                                )
                                onClipLongPressed?.invoke(clip)
                                invalidate()
                            }
                        }
                    }
            )


    // ---------- DATA ----------
    private var project: TimelineProject? = null
    private var activeTool = TimelineTool.SELECT
    fun getActiveTool(): TimelineTool = activeTool

    // ---------- STATE ----------
    private var currentTimeMs = 0L
    private var themeTextColor = Color.WHITE
    private var themeAccentColor = COLOR_ACCENT
    private var iconTintUnselected = Color.WHITE
    private var iconTintSelected = Color.CYAN
    private var isDraggingClip = false
    private var draggingClip: TimelineClip? = null
    private var dragStartClipTime = 0L
    private var selectedClip: TimelineClip? = null
    private val overlayStripePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 45
            strokeWidth = 3f
        }
    private var isDragging = false
    private var isScrollingTimeline = false
    private var isScrubbingRuler = false
    private var isMenuClick = false
    private var dragStartX = 0f
    private var downX = 0f
    private var downY = 0f
    private var scrollStartX = 0f
    private var scrollStartY = 0f
    private var scrollXOffset = 0f
    private var scrollYOffset = 0f 
    private var maxScrollY = 0f
    private val reusableRowRect = RectF()
    private val reusableThumbRect = RectF()
    private val reusableSrcRect = Rect()
    private var lastTouchX = 0f
    private var animatedClipId: String? = null
    private var animationStartTime: Long = 0
    private val breatheDuration = 1000L

    private var clipOriginalStartMs = 0L
    private var clipOriginalDurationMs = 0L
    private var currentLinkedClips: List<TimelineClip> = emptyList()
    private var clipOriginalOffsets: Map<String, Long> = emptyMap()
    private var trimMode = 0 // 0 = none, 1 = start, 2 = end
    var onTimelineChanged: ((Long) -> Unit)? = null
    var onClipSelected: ((TimelineClip?) -> Unit)? = null
    var onToolChanged: (() -> Unit)? = null
    // ---------- CALLBACK ----------
    var onScrollListener: ((Long) -> Unit)? = null
    var onInteractionStart: (() -> Unit)? = null
    var onInteractionEnd: (() -> Unit)? = null

    var onClipLongPressed: ((TimelineClip) -> Unit)? = null

    // ---------- PAINTS ----------
    private val rulerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14f
                textAlign = Paint.Align.CENTER
                color = "#80FFFFFF".toColorInt()
            }
    private val minorTickPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 0.8f
                alpha = 100
            }

    private val trackBgPaint = Paint()
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { 
        textSize = 20f // Reduced from 26f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val playheadPaint = Paint().apply { strokeWidth = 2.5f }

    private val fillerTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 34f
                color = "#4DFFFFFF".toColorInt() // More subtle
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            }


    private val clipWaveforms = mutableMapOf<String, List<Float>>()
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    private val thumbnailRetriever = android.media.MediaMetadataRetriever()
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val waveformPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_ACCENT // Theme Cyan
                style = Paint.Style.FILL
                alpha = 140 // Slightly more subtle
            }

    private val trackShadowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = 60
                maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            }
    private val clipShadowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = 80
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
            }
    private val thumbnailOverlayPaint = Paint().apply { style = Paint.Style.FILL }
    private val improvedBgPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BG
        }


    private val headerBgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_HEADER_BG
                style = Paint.Style.FILL
            }

    private val subtleBorderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_BORDER
                strokeWidth = 2f
            }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ACCENT }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
        color = Color.BLACK
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val gridPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0AFFFFFF")
                strokeWidth = 1f
            }
    private val iconBgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A000000") }
    private val reusableTrackPath = Path()
    private val reusableBarRect = RectF()

    init {
        resolveThemeColors()
        isHapticFeedbackEnabled = true
        // Important for internal scroll with clip/save
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
    private val deletePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_RED
        }

    private val keyframePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_GOLD
        }
    private fun resolveThemeColors() {
        val tv = TypedValue()

        val textColor =
            if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true))
                tv.data
            else Color.WHITE

        val accentColor = ContextCompat.getColor(context, R.color.brand_accent)
        val deepBgColor = ContextCompat.getColor(context, R.color.bg_deep_black)
        val surfaceColor = ContextCompat.getColor(context, R.color.bg_dark_surface)
        val elevatedColor = ContextCompat.getColor(context, R.color.bg_dark_elevated)
        val strokeColor = ContextCompat.getColor(context, R.color.dialog_stroke)
        val overlayColor = ContextCompat.getColor(context, R.color.white_10)

        themeTextColor = textColor
        themeAccentColor = accentColor
        iconTintSelected = accentColor
        iconTintUnselected = if (textColor == Color.WHITE) Color.WHITE else Color.DKGRAY

        improvedBgPaint.color = deepBgColor
        headerBgPaint.color = surfaceColor
        subtleBorderPaint.color = strokeColor
        trackBgPaint.color = elevatedColor
        gridPaint.color = overlayColor

        rulerPaint.color = textColor
        textPaint.color = textColor

        playheadPaint.apply {
            color = accentColor
            strokeWidth = 5f
        }
        minorTickPaint.color = accentColor
        waveformPaint.color = accentColor
        tooltipBgPaint.color = accentColor

        thumbnailOverlayPaint.apply {
            color = Color.BLACK
            alpha = 40
        }
    }

    // ---------- PUBLIC API ----------
    fun setProject(project: TimelineProject) {
        this.project = project
        ellipsizeCache.clear()
        timeFormatCache.clear()
        loadWaveformsForProject()
        invalidate()
    }


    private fun loadWaveformsForProject() {
        val proj = project ?: return
        proj.tracks.forEach { track ->
            if (track.type == TrackType.AUDIO ||
                            track.type == TrackType.VIDEO_AUDIO ||
                            track.type == TrackType.VIDEO
            ) {
                track.clips.forEach { clip ->
                    if (!clipWaveforms.containsKey(clip.filePath)) {
                        viewScope.launch {
                            val peaks = AudioWaveformUtils.getWaveform(clip.filePath, 100)
                            if (peaks.isNotEmpty()) {
                                clipWaveforms[clip.filePath] = peaks
                                invalidate()
                            }
                        }
                    }
                }
            }
        }
    }

    fun setTool(tool: TimelineTool) {
        activeTool = tool
        trimMode = 0
        onToolChanged?.invoke()
        invalidate()
    }

    fun seekTo(timeMs: Long) {
        currentTimeMs = timeMs
        ensurePlayheadVisible()
        invalidate()
    }

    fun animateClip(clipId: String) {
        animatedClipId = clipId
        animationStartTime = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun getTrackHeight(type: TrackType): Float =
            when (type) {
                TrackType.VIDEO_AUDIO -> trackHeight
                TrackType.OVERLAY -> trackHeight * 0.7f // Boosted from 0.4f
                else -> trackHeight
            }
    private fun getTrackLabel(
        track: TimelineTrack,
        trackIndex: Int,
        visibleTracks: List<TimelineTrack>
    ): String {

        return when (track.type) {

            TrackType.VIDEO ->
                "V${visibleTracks.take(trackIndex + 1).count { it.type == TrackType.VIDEO }}"

            TrackType.AUDIO ->
                "A${visibleTracks.take(trackIndex + 1).count { it.type == TrackType.AUDIO }}"

            TrackType.OVERLAY ->
                "OV${visibleTracks.take(trackIndex + 1).count { it.type == TrackType.OVERLAY }}"

            TrackType.VOICEOVER ->
                "VO${visibleTracks.take(trackIndex + 1).count { it.type == TrackType.VOICEOVER }}"

            TrackType.VIDEO_AUDIO ->
                "VA${visibleTracks.take(trackIndex + 1).count { it.type == TrackType.VIDEO_AUDIO }}"
        }
    }
    private fun getVisibleTracks(): List<com.example.videoeditorapp.model.timeline.TimelineTrack> {
        val proj = project ?: return emptyList()
        // 🍏 V5: if empty, return truly EMPTY to show filler text
        return proj.tracks
            .filter { it.clips.isNotEmpty() }
            .distinctBy { it.id }
    }

    // ---------- EDITING ACTIONS ----------

    fun splitClip(clip: TimelineClip, splitTimeMs: Long): TimelineClip? {
        val proj = project ?: return null
        val track = proj.tracks.find { it.clips.contains(clip) } ?: return null

        if (splitTimeMs <= clip.startTimeMs + 100 ||
                        splitTimeMs >= clip.startTimeMs + clip.durationMs - 100
        ) {
            return null
        }

        val relativeSplit = splitTimeMs - clip.startTimeMs
        val originalDur = clip.durationMs
        val firstPartDur = relativeSplit
        val secondPartDur = originalDur - relativeSplit

        clip.durationMs = firstPartDur
        val newClip =
                clip.copy(
                        id = UUID.randomUUID().toString(),
                        startTimeMs = splitTimeMs,
                        durationMs = secondPartDur,
                        sourceStartTimeMs = clip.sourceStartTimeMs + (firstPartDur / clip.playbackSpeed).toLong()
                )

        track.clips.add(newClip)
        track.clips.sortBy { it.startTimeMs }

        onTimelineChanged?.invoke(currentTimeMs)
        invalidate()
        return newClip
    }


    private fun drawTimelineMenuButton(canvas: Canvas) {

        val size = 44f

        timelineMenuRect.set(
            10f,
            2f,
            10f + size,
            2f + size
        )


        canvas.drawRoundRect(
            timelineMenuRect,
            10f,
            10f,
            headerBgPaint
        )

        val cx = timelineMenuRect.centerX()
        val cy = timelineMenuRect.centerY()

        canvas.drawLine(cx - 10f, cy - 8f, cx + 10f, cy - 8f, rulerPaint)
        canvas.drawLine(cx - 10f, cy, cx + 10f, cy, rulerPaint)
        canvas.drawLine(cx - 10f, cy + 8f, cx + 10f, cy + 8f, rulerPaint)
    }
    // ---------- RULER ----------
    private fun drawGridLines(canvas: Canvas, trackTop: Float, trackHeight: Float) {
        val durationMs = project?.getDurationMs() ?: 0L
        val maxSec = (durationMs / 1000f).toInt() + 10
        val step = calculateRulerStep()

        for (sec in 0..maxSec step step) {
            val x = HEADER_WIDTH + (sec * pixelsPerSecond) - scrollXOffset
            if (x < HEADER_WIDTH - 20) continue
            if (x > width + 20) break

            canvas.drawLine(x, trackTop, x, trackTop + trackHeight, gridPaint)
        }
    }

    private fun calculateRulerStep(): Int {
        return when {
            pixelsPerSecond > 100 -> 1
            pixelsPerSecond > 50 -> 2
            pixelsPerSecond > 20 -> 5
            pixelsPerSecond > 10 -> 10
            else -> 60
        }
    }

    private fun calculateSubTicks(): Int {
        return when {
            pixelsPerSecond > 300 -> 10
            pixelsPerSecond > 100 -> 5
            else -> 2
        }
    }

    private fun drawRuler(canvas: Canvas) {
        val step = calculateRulerStep()
        val sub = calculateSubTicks()

        val startStep = floor(scrollXOffset / (pixelsPerSecond * step)).toInt()
        val endStep =
                ((scrollXOffset + width - HEADER_WIDTH) / (pixelsPerSecond * step)).toInt() + 1

        for (s in startStep..endStep) {
            val sec = s * step
            val baseX = HEADER_WIDTH + (sec * pixelsPerSecond - scrollXOffset)

            if (baseX < HEADER_WIDTH - 20) continue

            val isCurrentSec = (currentTimeMs / 1000).toInt() == sec

            rulerPaint.color = if (isCurrentSec) themeAccentColor else themeTextColor
            canvas.drawLine(baseX, 0f, baseX, rulerHeight * 0.7f, rulerPaint)

            rulerPaint.color =
                if (isCurrentSec) themeAccentColor
                else themeTextColor

            val paint = rulerPaint

            canvas.drawText(
                    getCachedTime(sec * 1000L),
                    baseX,
                    rulerHeight - 6f,
                    paint
            )

            if (sub > 1) {
                val subInterval = (pixelsPerSecond * step) / sub
                for (i in 1 until sub) {
                    val x = baseX + i * subInterval
                    if (x >= HEADER_WIDTH && x <= width) {
                        canvas.drawLine(x, rulerHeight * 0.85f, x, rulerHeight, minorTickPaint)
                    }
                }
            }
        }
    }
    private fun drawSplitTooltip(
        canvas: Canvas,
        playheadX: Float
    ) {
        val text = "SPLIT"

        val width = tooltipPaint.measureText(text)

        reusableTooltipRect.set(
            playheadX - width / 2f - 12f,
            rulerHeight - 40f,
            playheadX + width / 2f + 12f,
            rulerHeight - 5f
        )

        canvas.drawRoundRect(
            reusableTooltipRect,
            8f,
            8f,
            tooltipBgPaint
        )

        canvas.drawText(
            text,
            playheadX,
            rulerHeight - 15f,
            tooltipPaint
        )
    }
    private var lastHapticTimeMs = 0L
    private fun drawPlayhead(
        canvas: Canvas,
        playheadX: Float
    ) {

        // Full height needle
        canvas.drawLine(
            playheadX,
            0f,
            playheadX,
            height.toFloat(),
            playheadPaint
        )

        // Head
        reusableHeadRect.set(
            playheadX - 8f,
            rulerHeight - 10f,
            playheadX + 8f,
            rulerHeight + 10f
        )

        canvas.drawRoundRect(
            reusableHeadRect,
            6f,
            6f,
            playheadPaint
        )

        if (activeTool == TimelineTool.SPLIT) {
            drawSplitTooltip(canvas, playheadX)
        }
    }
    private fun TimelineClip.displayTitle(): String =
        when (type) {
            ClipType.TEXT ->
                textSettings["TEXT"]
                    ?: textSettings["text"]
                    ?: "Text"

            else ->
                filePath
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { "Clip" }
        }
    private fun getClipColor(clip: TimelineClip, trackType: TrackType): Int {
        val seed = clip.filePath.hashCode()
        val random = java.util.Random(seed.toLong())

        val baseHue =
                when (trackType) {
                    TrackType.VIDEO -> 200f
                    TrackType.VIDEO_AUDIO -> 180f
                    TrackType.AUDIO -> 100f
                    TrackType.OVERLAY -> 320f
                    TrackType.VOICEOVER -> 120f
                }

        val hsv = FloatArray(3)
        hsv[0] = (baseHue + (random.nextFloat() * 20f - 10f)).coerceIn(0f, 360f)
        hsv[1] = 0.5f + random.nextFloat() * 0.3f
        hsv[2] = 0.7f + random.nextFloat() * 0.2f

        if (trackType == TrackType.VIDEO_AUDIO) {
            hsv[1] = (hsv[1] * 0.8f).coerceIn(0f, 1.0f)
            hsv[2] = (hsv[2] * 0.7f).coerceIn(0f, 1.0f)
        }

        return Color.HSVToColor(hsv)
    }

    private fun isTrackLinkedWithNext(
        idx: Int,
        tracks: List<TimelineTrack>
    ): Boolean {

        if (idx >= tracks.size - 1) return false

        val current = tracks[idx]
        val next = tracks[idx + 1]

        val currentGroups =
            current.clips
                .filter { !it.isUnlinked }
                .mapNotNull { it.metadata["LINK_GROUP"] }
                .toSet()

        if (currentGroups.isEmpty()) {
            return false
        }

        return next.clips.any {
            !it.isUnlinked &&
                    it.metadata["LINK_GROUP"] in currentGroups
        }
    }
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXOffset = scroller.currX.toFloat()
            scrollYOffset = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }
    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        transitionZones.clear()
        val selectedClipId = selectedClip?.id

        val animatedScale =
            if (animatedClipId != null) {
                val elapsed = SystemClock.uptimeMillis() - animationStartTime

                if (elapsed < breatheDuration) {
                    invalidate()
                    1f + 0.08f * kotlin.math.sin(
                        elapsed / breatheDuration.toFloat() * Math.PI
                    ).toFloat()
                } else {
                    animatedClipId = null
                    1f
                }
            } else {
                1f
            }
        val visibleTracks = getVisibleTracks()

        // 1. Calculate and update Internal Scroll Bounds
        var totalHeight = 0f
        visibleTracks.forEachIndexed { idx, track ->
            val isLinkedWithNext = isTrackLinkedWithNext(idx, visibleTracks)
            val h = getTrackHeight(track.type)
            totalHeight += h + (if (isLinkedWithNext) 0f else trackSpacing)
        }

        maxScrollY = (totalHeight + rulerHeight + 150f - height).coerceAtLeast(0f)

        // 2. LAYER 1: TRACKS & CLIPS
        canvas.save()
        canvas.clipRect(HEADER_WIDTH, rulerHeight, width.toFloat(), height.toFloat())
        
        var currentY = rulerHeight + trackSpacing - scrollYOffset


        visibleTracks.forEachIndexed { indexOfVisible, track ->
            val isLinkedWithNext = isTrackLinkedWithNext(indexOfVisible, visibleTracks)
            val isLinkedWithPrev = indexOfVisible > 0 && isTrackLinkedWithNext(indexOfVisible - 1, visibleTracks)
            val effectiveTrackHeight = getTrackHeight(track.type)

            drawGridLines(canvas, currentY, effectiveTrackHeight)

            val trackTopMargin = if (isLinkedWithPrev) 0f else 8f
            val trackBottomMargin = if (isLinkedWithNext) 0f else 8f

            reusableRowRect.set(HEADER_WIDTH, currentY + trackTopMargin, width.toFloat(), currentY + effectiveTrackHeight - trackBottomMargin)

            reusableTrackPath.reset()
            reusableTrackPath.addRect(reusableRowRect, Path.Direction.CW)

            canvas.drawPath(reusableTrackPath, trackShadowPaint)
            canvas.drawPath(reusableTrackPath, improvedBgPaint)
            val trackLabel =
                getTrackLabel(
                    track,
                    indexOfVisible,
                    visibleTracks
                )
            track.clips.forEach { clip ->

                val startX =
                    HEADER_WIDTH +
                            (clip.startTimeMs / 1000f) * pixelsPerSecond -
                            scrollXOffset

                val clipWidth =
                    (clip.durationMs / 1000f) * pixelsPerSecond

                if (startX + clipWidth <= HEADER_WIDTH || startX >= width) {
                    return@forEach
                }

                val left = startX
                val right = startX + clipWidth

                reusableClipRect.set(
                    left,
                    currentY + CLIP_VERTICAL_PADDING,
                    right,
                    currentY + effectiveTrackHeight - CLIP_VERTICAL_PADDING
                )

                val isSelected = selectedClipId == clip.id
                val isAnimated = clip.id == animatedClipId

                if (isAnimated) {
                    canvas.save()
                    canvas.scale(
                        animatedScale,
                        animatedScale,
                        reusableClipRect.centerX(),
                        reusableClipRect.centerY()
                    )
                }

                clipPaint.color = getClipColor(clip, track.type)

                if (isSelected) {
                    clipPaint.setShadowLayer(
                        14f,
                        0f,
                        0f,
                        themeAccentColor
                    )
                } else {
                    clipPaint.clearShadowLayer()
                }
                canvas.drawRoundRect(
                    reusableClipRect,
                    CLIP_RADIUS,
                    CLIP_RADIUS,
                    clipPaint
                )
                if (track.type == TrackType.OVERLAY) {
                    var xPos = reusableClipRect.left

                    while (xPos < reusableClipRect.right) {

                        canvas.drawLine(
                            xPos,
                            reusableClipRect.top,
                            xPos + 20f,
                            reusableClipRect.bottom,
                            overlayStripePaint
                        )

                        xPos += 28f
                    }
                }
                canvas.drawRoundRect(
                    reusableClipRect,
                    CLIP_RADIUS,
                    CLIP_RADIUS,
                    if (clip.isUnlinked) unlinkedClipPaint else linkedPaint
                )

                if (clip.type != ClipType.VOICEOVER) {
                    drawClipThumbnail(
                        canvas,
                        clip,
                        reusableClipRect
                    )
                }

                if (
                    track.type == TrackType.AUDIO ||
                    track.type == TrackType.VIDEO_AUDIO
                ) {
                    drawWaveform(
                        canvas,
                        clip,
                        reusableClipRect
                    )
                }
                if (reusableClipRect.width() > 60f) {

                    val badgeWidth =
                        badgeTextPaint.measureText(trackLabel) + 18f

                    reusableBadgeRect.set(
                        reusableClipRect.left + 6f,
                        reusableClipRect.top + 6f,
                        reusableClipRect.left + 6f + badgeWidth,
                        reusableClipRect.top + 28f
                    )

                    canvas.drawRoundRect(
                        reusableBadgeRect,
                        8f,
                        8f,
                        badgePaint
                    )

                    canvas.drawText(
                        trackLabel,
                        reusableBadgeRect.centerX(),
                        reusableBadgeRect.centerY() + 5f,
                        badgeTextPaint
                    )
                }
                val rawName = clip.displayTitle()

                val thumbnailSpace =
                    if (
                        clip.type != ClipType.TEXT &&
                        clip.type != ClipType.VOICEOVER &&
                        reusableClipRect.width() > reusableClipRect.height() + 40f
                    ) {
                        reusableClipRect.height() + 12f
                    } else {
                        12f
                    }

                var textX =
                    reusableClipRect.left + thumbnailSpace

                if (reusableClipRect.width() > 60f) {
                    textX = maxOf(
                        textX,
                        reusableBadgeRect.right + 8f
                    )
                }
                val availableWidth =
                    (reusableClipRect.right - textX - 8f)
                        .coerceAtLeast(0f)

                if (availableWidth > 40f) {

                    canvas.withClip(
                        reusableClipRect.left,
                        reusableClipRect.top,
                        reusableClipRect.right,
                        reusableClipRect.bottom
                    ) {

                        val displayText =
                            getCachedEllipsizedTitle(
                                clip.id,
                                rawName,
                                textPaint,
                                availableWidth
                            )
                        drawText(
                            displayText,
                            textX,
                            reusableClipRect.centerY() +
                                    textPaint.textSize / 3f,
                            textPaint
                        )

                    }
                }

                if (isAnimated) {
                    canvas.restore()
                }
            }
            
            // Draw transition handles for adjacent touching clips
            val sortedClips = track.clips.sortedBy { it.startTimeMs }
            for (i in 0 until sortedClips.size - 1) {
                val clip1 = sortedClips[i]
                val clip2 = sortedClips[i + 1]
                if (Math.abs(clip2.startTimeMs - clip1.endTimeMs) <= 10L) {
                    val junctionX = HEADER_WIDTH + (clip2.startTimeMs / 1000f) * pixelsPerSecond - scrollXOffset
                    if (junctionX > HEADER_WIDTH && junctionX < width) {
                        val centerY = currentY + effectiveTrackHeight / 2f
                        val radius = com.example.videoeditorapp.utils.ViewUtils.dpToPx(context, 14).toFloat()
                        
                        val handleRect = RectF(
                            junctionX - radius,
                            centerY - radius,
                            junctionX + radius,
                            centerY + radius
                        )
                        
                        transitionZones.add(TransitionZone(clip2, RectF(handleRect)))
                        
                        val hasTransition = clip2.metadata.containsKey("TRANSITION_TYPE")
                        
                        clipPaint.style = Paint.Style.FILL
                        clipPaint.color = Color.parseColor("#E61C1C1C")
                        canvas.drawCircle(junctionX, centerY, radius, clipPaint)
                        
                        clipPaint.style = Paint.Style.STROKE
                        clipPaint.strokeWidth = com.example.videoeditorapp.utils.ViewUtils.dpToPx(context, 1).toFloat()
                        clipPaint.color = if (hasTransition) themeAccentColor else Color.parseColor("#40FFFFFF")
                        canvas.drawCircle(junctionX, centerY, radius, clipPaint)
                        
                        clipPaint.style = Paint.Style.STROKE
                        clipPaint.strokeWidth = com.example.videoeditorapp.utils.ViewUtils.dpToPx(context, 2).toFloat()
                        if (hasTransition) {
                            clipPaint.color = themeAccentColor
                            val inner = radius * 0.4f
                            val path = Path().apply {
                                moveTo(junctionX - inner, centerY - inner)
                                lineTo(junctionX + inner, centerY - inner)
                                lineTo(junctionX - inner, centerY + inner)
                                lineTo(junctionX + inner, centerY + inner)
                                close()
                            }
                            canvas.drawPath(path, clipPaint)
                        } else {
                            clipPaint.color = Color.WHITE
                            val inner = radius * 0.35f
                            canvas.drawLine(junctionX - inner, centerY, junctionX + inner, centerY, clipPaint)
                            canvas.drawLine(junctionX, centerY - inner, junctionX, centerY + inner, clipPaint)
                        }
                    }
                }
            }

            currentY += effectiveTrackHeight + (if (isLinkedWithNext) 0f else trackSpacing)
        }
        canvas.restore()

        // FILLER
        if (visibleTracks.isEmpty()) {
            canvas.drawText("Press '+' to start your masterpiece", width / 2f, height / 2f, fillerTextPaint)
        }

        // PLAYHEAD
        // PLAYHEAD
        val playheadX =
            HEADER_WIDTH +
                    (currentTimeMs / 1000f) * pixelsPerSecond -
                    scrollXOffset

        if (
            playheadX > HEADER_WIDTH - 20f &&
            playheadX < width + 20f
        ) {
            drawPlayhead(canvas, playheadX)
        }
        // FIXED HEADER SIDEBAR (Vertical Scroll with Tracks)
        canvas.save()
        canvas.clipRect(0f, rulerHeight, HEADER_WIDTH, height.toFloat())
        canvas.drawRect(0f, rulerHeight, HEADER_WIDTH, height.toFloat(), headerBgPaint)
        canvas.drawLine(HEADER_WIDTH, rulerHeight, HEADER_WIDTH, height.toFloat(), subtleBorderPaint)

        var currentYHead = rulerHeight + trackSpacing - scrollYOffset

        visibleTracks.forEachIndexed { index, track ->

            val isLinkedWithNext =
                isTrackLinkedWithNext(index, visibleTracks)

            val isLinkedWithPrev =
                index > 0 &&
                        isTrackLinkedWithNext(index - 1, visibleTracks)

            val h = getTrackHeight(track.type)

            val trackTopMargin =
                if (isLinkedWithPrev) 0f else 8f

            val trackBottomMargin =
                if (isLinkedWithNext) 0f else 8f

            val centerY =
                currentYHead +
                        trackTopMargin +
                        ((h - trackTopMargin - trackBottomMargin) / 2f)

            val iconSize = 24f

            val iconX =
                (HEADER_WIDTH - iconSize) / 2f

            val iconY =
                centerY - iconSize / 2f

            drawTrackIcon(
                canvas = canvas,
                track = track,
                iconX = iconX,
                iconY = iconY,
                iconSize = iconSize
            )
            val trackLabel =
                getTrackLabel(
                    track,
                    index,
                    visibleTracks
                )

            canvas.drawText(
                trackLabel,
                HEADER_WIDTH / 2f,
                iconY + 42f,
                rulerPaint.apply {
                    textAlign = Paint.Align.CENTER
                    textSize = 12f
                    color = Color.WHITE
                }
            )
            currentYHead +=
                h + if (isLinkedWithNext) 0f else trackSpacing
        }
        canvas.restore()

        // FIXED RULER (Top)
        canvas.save()
        canvas.clipRect(HEADER_WIDTH, 0f, width.toFloat(), rulerHeight)
        canvas.drawRect(HEADER_WIDTH, 0f, width.toFloat(), rulerHeight, improvedBgPaint)
        drawRuler(canvas)
        canvas.restore()
        drawTimelineMenuButton(canvas)
    }

    private fun drawClipThumbnail(canvas: Canvas, clip: TimelineClip, rect: RectF) {
        val bitmap = getThumbnail(clip)
        if (bitmap != null) {
            canvas.withSave {
                val thumbWidth =
                    minOf(
                        rect.height(),
                        rect.width() * 0.35f
                    )
                reusableThumbRect.set(
                    rect.left,
                    rect.top,
                    (rect.left + thumbWidth).coerceAtMost(rect.right),
                    rect.bottom
                )
                clipRect(reusableThumbRect)
                reusableSrcRect.set(0, 0, bitmap.width, bitmap.height)
                drawBitmap(bitmap, reusableSrcRect, reusableThumbRect, null)
                drawRect(reusableThumbRect, thumbnailOverlayPaint)
            }
        }
    }

    private val loadingThumbnails = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun getThumbnail(clip: TimelineClip): Bitmap? {
        val cacheKey = "${clip.filePath}_${clip.sourceStartTimeMs}"
        thumbnailCache[cacheKey]?.let { return it }

        if (loadingThumbnails.contains(cacheKey)) {
            return null
        }

        loadingThumbnails.add(cacheKey)
        viewScope.launch(Dispatchers.IO) {
            try {
                if (clip.type == ClipType.IMAGE || clip.type == ClipType.STICKER) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bitmap = BitmapFactory.decodeFile(clip.filePath, opts)
                    if (bitmap != null) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            thumbnailCache[cacheKey] = bitmap
                            loadingThumbnails.remove(cacheKey)
                            invalidate()
                        }
                    }
                } else if (clip.type == ClipType.VIDEO) {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(clip.filePath)
                    val bitmap = retriever.getFrameAtTime(clip.sourceStartTimeMs * 1000L)
                    retriever.release()
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, 120, 120, true)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            thumbnailCache[cacheKey] = scaled
                            loadingThumbnails.remove(cacheKey)
                            invalidate()
                        }
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    loadingThumbnails.remove(cacheKey)
                }
            }
        }
        return null
    }

    private fun drawWaveform(canvas: Canvas, clip: TimelineClip, rect: RectF) {
        val peaks = clipWaveforms[clip.filePath] ?: return
        canvas.save()
        canvas.clipRect(rect)
        val maxHeight = rect.height() * 0.6f
        val barWidth = 4f
        val gap = 2f
        val numBars = (rect.width() / (barWidth + gap)).toInt()

        for (i in 0 until numBars) {
            val peak = peaks[(i.toFloat() / numBars * peaks.size).toInt().coerceIn(0, peaks.size - 1)]
            val x = rect.left + i * (barWidth + gap)
            val h = peak * maxHeight
            reusableBarRect.set(x, rect.centerY() - h/2, x + barWidth, rect.centerY() + h/2)
            waveformPaint.alpha = (120 + peak * 135).toInt()
            canvas.drawRoundRect(reusableBarRect, 2f, 2f, waveformPaint)
        }
        canvas.restore()
    }
    fun skipToNextClip() {

        val proj = project ?: return

        val points =
            proj.tracks
                .flatMap { it.clips }
                .flatMap {
                    listOf(
                        it.startTimeMs,
                        it.startTimeMs + it.durationMs
                    )
                }
                .distinct()
                .sorted()

        val nextPoint =
            points.firstOrNull {
                it > currentTimeMs + 250
            } ?: proj.getDurationMs()

        seekTo(nextPoint)
        onScrollListener?.invoke(nextPoint)
    }

    fun skipToPreviousClip() {

        val proj = project ?: return

        val points =
            proj.tracks
                .flatMap { it.clips }
                .flatMap {
                    listOf(
                        it.startTimeMs,
                        it.startTimeMs + it.durationMs
                    )
                }
                .distinct()
                .sorted()

        val previousPoint =
            points.lastOrNull {
                it < currentTimeMs - 250
            } ?: 0L

        seekTo(previousPoint)
        onScrollListener?.invoke(previousPoint)
    }
    private fun findClipAt(x: Float, y: Float): TimelineClip? {
        val scrollX =
            (x + scrollXOffset - HEADER_WIDTH)
                .coerceAtLeast(0f)
        val scrollY = y + scrollYOffset - rulerHeight
        var currentY = 0f
        getVisibleTracks().forEach { track ->
            val h = getTrackHeight(track.type)
            if (scrollY in currentY..(currentY + h)) {
                return track.clips.find { (scrollX * 1000 / pixelsPerSecond).toLong() in it.startTimeMs..(it.startTimeMs + it.durationMs) }
            }
            currentY += h + trackSpacing
        }
        return null
    }

    private fun getSnapPoints(exclude: TimelineClip? = null): List<Long> {
        val points = mutableSetOf(0L, currentTimeMs)
        project?.tracks?.forEach { tr -> tr.clips.filter { it != exclude }.forEach { 
            points.add(it.startTimeMs)
            points.add(it.startTimeMs + it.durationMs)
        }}
        return points.toList()
    }

    private fun snap(time: Long, points: List<Long>): Long {
        points.forEach { if (abs(time - it) < (snapThresholdPx * 1000 / pixelsPerSecond).toLong()) return it }
        return time
    }


    internal fun findLinkedClips(
        clip: TimelineClip
    ): List<TimelineClip> {
        if (clip.isUnlinked) return listOf(clip)

        val groupId =
            clip.metadata["LINK_GROUP"]
                ?: return listOf(clip)

        return project!!.tracks
            .flatMap { it.clips }
            .filter {
                !it.isUnlinked &&
                it.metadata["LINK_GROUP"] == groupId &&
                        (
                                it.type == ClipType.VIDEO ||
                                        it.type == ClipType.AUDIO
                                )
            }
    }
    private fun ensurePlayheadVisible() {
        val playheadX = (currentTimeMs / 1000f) * pixelsPerSecond
        if (playheadX < scrollXOffset) scrollXOffset = playheadX
        else if (playheadX > scrollXOffset + width - HEADER_WIDTH) scrollXOffset = playheadX - (width - HEADER_WIDTH)
    }

    fun deleteSelectedClip() {
        val clip = selectedClip ?: return
        val proj = project ?: return
        for (track in proj.tracks) {
            if (track.clips.contains(clip)) {
                val deletedStart = clip.startTimeMs
                val deletedDuration = clip.durationMs
                track.clips.remove(clip)
                
                // RIPPLE EDIT: Shift subsequent clips left to fill the gap
                track.clips.forEach { c ->
                    if (c.startTimeMs >= deletedStart + deletedDuration) {
                        c.startTimeMs -= deletedDuration
                    }
                }
                
                selectedClip = null
                onClipSelected?.invoke(null)
                notifyDataChanged()
                break
            }
        }
    }

    private fun resolveTrackCollisions() {
        val proj = project ?: return
        val tracksCopy = ArrayList(proj.tracks)
        for (track in tracksCopy) {
            val clipsCopy = ArrayList(track.clips).sortedBy { it.startTimeMs }
            for (clip in clipsCopy) {
                val hasOverlap = track.clips.filter { it != clip }.any { other ->
                    clip.startTimeMs < other.endTimeMs && clip.endTimeMs > other.startTimeMs
                }
                if (hasOverlap) {
                    val targetTrack = findOrCreateNonOverlappingTrack(track.type, clip)
                    track.clips.remove(clip)
                    targetTrack.clips.add(clip)
                    targetTrack.clips.sortBy { it.startTimeMs }
                }
            }
        }
        proj.tracks.removeAll { it.clips.isEmpty() }
    }

    private fun findOrCreateNonOverlappingTrack(type: TrackType, clip: TimelineClip): TimelineTrack {
        val proj = project!!
        for (t in proj.tracks) {
            if (t.type == type) {
                val overlaps = t.clips.any { other ->
                    clip.startTimeMs < other.endTimeMs && clip.endTimeMs > other.startTimeMs
                }
                if (!overlaps) return t
            }
        }
        val newTrack = TimelineTrack(id = java.util.UUID.randomUUID().toString(), type = type)
        proj.tracks.add(newTrack)
        return newTrack
    }

    fun selectClipDirectly(clip: TimelineClip?) {
        selectedClip = clip
        invalidate()
    }

    internal fun notifyDataChanged() {
        ellipsizeCache.clear()
        timeFormatCache.clear()
        resolveTrackCollisions()
        onTimelineChanged?.invoke(currentTimeMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        longPressGestureDetector.onTouchEvent(event)

        val x = event.x
        val y = event.y
        lastTouchX = x

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                onInteractionStart?.invoke()

                scroller.forceFinished(true)

                downX = x
                downY = y

                scrollStartX = scrollXOffset
                scrollStartY = scrollYOffset

                isDragging = false
                isScrollingTimeline = false
             if (timelineMenuRect.contains(x, y)) {
                 isMenuClick = true
                 onTimelineMenuClick?.invoke(
                     timelineMenuRect.left,
                     timelineMenuRect.bottom
                 )
                 return true
             }

                // Intercept transition handle clicks
                val clickedZone = transitionZones.find { it.rect.contains(x, y) }
                if (clickedZone != null) {
                    onTransitionHandleClicked?.invoke(clickedZone.nextClip)
                    return true
                }

                isScrubbingRuler = y <= rulerHeight && x >= HEADER_WIDTH
                if (!isScrubbingRuler && x >= HEADER_WIDTH) {

                    val found = findClipAt(x, y)

                    selectedClip = found
                    onClipSelected?.invoke(found)

                    found?.let { clip ->

                        clipOriginalStartMs = clip.startTimeMs
                        clipOriginalDurationMs = clip.durationMs

                        val linked = findLinkedClips(clip)

                        currentLinkedClips = linked

                        clipOriginalOffsets =
                            linked.associate {
                                it.id to (it.startTimeMs - clip.startTimeMs)
                            }

                        if (activeTool == TimelineTool.TRIM) {

                            val startX =
                                HEADER_WIDTH +
                                        (clip.startTimeMs / 1000f) * pixelsPerSecond -
                                        scrollXOffset

                            val endX =
                                startX +
                                        (clip.durationMs / 1000f) * pixelsPerSecond

                            trimMode = when {
                                abs(x - startX) < 40f -> 1
                                abs(x - endX) < 40f -> 2
                                else -> 0
                            }
                        }
                    }
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isMenuClick) return true
                val dx = x - downX
                val dy = y - downY

                if (!isDragging &&
                    (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX)
                ) {
                    isDragging = true

                    if (isScrubbingRuler) {
                        isScrollingTimeline = true
                    } else if (selectedClip == null || x <= HEADER_WIDTH) {
                        selectedClip = null
                        isScrollingTimeline = true
                    }
                }

                when {
                    isScrollingTimeline -> {

                        val maxScrollX =
                            maxOf(
                                0f,
                                ((project?.getDurationMs() ?: 0L) / 1000f) *
                                        pixelsPerSecond -
                                        (width - HEADER_WIDTH)
                            )

                        scrollXOffset =
                            (scrollStartX - dx)
                                .coerceIn(0f, maxScrollX)

                        if (!isScrubbingRuler) {
                            scrollYOffset =
                                (scrollStartY - dy)
                                    .coerceIn(0f, maxScrollY)
                        }
                    }

                    isScrubbingRuler -> {

                        currentTimeMs =
                            (((scrollXOffset + x - HEADER_WIDTH) /
                                    pixelsPerSecond) * 1000f)
                                .toLong()
                                .coerceAtLeast(0L)

                        onScrollListener?.invoke(currentTimeMs)
                    }

                    isDragging && selectedClip != null -> {

                        val clip = selectedClip!!
                        val deltaMs =
                            ((dx / pixelsPerSecond) * 1000f).toLong()

                        when (activeTool) {

                            TimelineTool.SELECT -> {
                                val minOffset = clipOriginalOffsets.values.minOrNull() ?: 0L
                                val minStartAllowed = if (minOffset < 0) -minOffset else 0L
                                val newStart =
                                    snap(
                                        clipOriginalStartMs + deltaMs,
                                        getSnapPoints(clip)
                                    ).coerceAtLeast(minStartAllowed)

                                currentLinkedClips.forEach { linkedClip ->
                                    linkedClip.startTimeMs =
                                        newStart + (clipOriginalOffsets[linkedClip.id] ?: 0L)
                                }
                            }

                            TimelineTool.TRIM -> {

                                val originalEnd =
                                    clipOriginalStartMs +
                                            clipOriginalDurationMs

                                when (trimMode) {

                                    1 -> {
                                        // LEFT HANDLE

                                        val newStart =
                                            snap(
                                                clipOriginalStartMs + deltaMs,
                                                getSnapPoints(clip)
                                            )
                                                .coerceAtLeast(0L)
                                                .coerceAtMost(originalEnd - 200L)

                                        clip.startTimeMs = newStart

                                        clip.durationMs =
                                            originalEnd - newStart

                                        clip.sourceStartTimeMs =
                                            (
                                                    clip.sourceStartTimeMs +
                                                            ((newStart -
                                                                    clipOriginalStartMs) /
                                                                    clip.playbackSpeed)
                                                    ).toLong()
                                    }

                                    2 -> {
                                        // RIGHT HANDLE

                                        val newDuration =
                                            (clipOriginalDurationMs + deltaMs)
                                                .coerceAtLeast(200L)

                                        clip.durationMs = newDuration
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isMenuClick) {
                    isMenuClick = false
                    return true
                }

                isDragging = false
                isScrollingTimeline = false
                trimMode = 0

                notifyDataChanged()
                onInteractionEnd?.invoke()

                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }
}
