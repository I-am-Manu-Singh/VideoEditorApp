package com.example.videoeditorapp.ui.timeline

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
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
    // --- Ruler interaction state for behavior C ---
    private var rulerDownTime = 0L
    private var rulerSwipeTriggered = false
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as
                            android.os.VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
    }
    private var activeSnapPointMs: Long? = null
    private var lastSnapPointMs: Long? = null
    private var dragHapticFired = false
    private val TAP_SLOP_PX = 18f
    private val DRAG_MIN_DIST_PX = 12f
    private var lastSnapMs = -1L
    private var pixelsPerSecond = 100f
    private val trackHeight = 120f
    private val trackSpacing = 1.5f
    private val rulerHeight = 60f
    private val handleWidth = 14f
    private val snapThresholdPx = 60f
    private val HEADER_WIDTH = 100f // 🍏 Fixed width for track icons
    private val TRIM_HANDLE_HIT = 22f
    // ---------- ENGINES ----------
    private val scroller = android.widget.OverScroller(context)
    private val scaleDetector =
            android.view.ScaleGestureDetector(
                    context,
                    object : android.view.ScaleGestureDetector.OnScaleGestureListener {
                        override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                            val oldPps = pixelsPerSecond
                            pixelsPerSecond *= detector.scaleFactor
                            pixelsPerSecond =
                                    pixelsPerSecond.coerceIn(20f, 1000f) // 0.2x to 10x zoom

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

    private val autoScrollRunnable =
            object : Runnable {
                override fun run() {
                    if (isScrubbingRuler || isDragging) {
                        handleAutoScrollInternal(lastTouchX)
                        postOnAnimation(this)
                    }
                }
            }

    private val longPressGestureDetector =
            android.view.GestureDetector(
                    context,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: android.view.MotionEvent) {
                            val found = findClipAt(e.x, e.y)
                            found?.let { clip ->
                                // Trigger long press ONLY for Video clips to toggle link/unlink
                                if (clip.type == ClipType.VIDEO) {
                                    onClipLongPressed?.invoke(clip)
                                }
                            }
                        }
                    }
            )

    // ---------- DATA ----------
    private var project: TimelineProject? = null
    private var activeTool = TimelineTool.SELECT
    fun getActiveTool(): TimelineTool = activeTool
    var onToolChanged: ((TimelineTool) -> Unit)? = null

    // ---------- STATE ----------
    private var currentTimeMs = 0L

    private val TRIM_DEADZONE_PX = 10f
    private var selectedClip: TimelineClip? = null
    fun getSelectedClip(): TimelineClip? = selectedClip

    private var isDragging = false
    private var isScrollingTimeline = false
    private var isScrubbingRuler = false
    private var dragStartX = 0f
    private var downX = 0f
    private var downY = 0f
    private var rulerStartX = 0f
    private var rulerStartScrollX = 0f
    private var scrollStartX = 0f
    private var scrollStartY = 0f
    private var scrollXOffset = 0f
    private var scrollYOffset = 0f // Internal Y offset for tracks area
    private var maxScrollY = 0f
    private var lastHapticScrollSec = -1
    private var lastHapticScrubTenth = -1
    private var lastTouchX = 0f // 🍏 Track for auto-scroll loop
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
    // ---------- CALLBACK ----------
    var onScrollListener: ((Long) -> Unit)? = null
    var onInteractionStart: (() -> Unit)? = null
    var onInteractionEnd: (() -> Unit)? = null
    var onClipLongPressed: ((TimelineClip) -> Unit)? = null
    private val clipAtTouch: TimelineClip?
        get() = findClipAt(downX, downY)

    // For ghost drag logic
    private var ghostDragStartTimeMs: Long? = null
    private val clipHeaderBgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E1E1E") }

    // ---------- PAINTS ----------
    private val rulerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 25f
                textAlign = Paint.Align.CENTER
            }
    private val minorTickPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 1f
                alpha = 120
            }
    private val highlightTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 30f
                color = Color.MAGENTA
                textAlign = Paint.Align.CENTER
            }
    private val trackBgPaint = Paint()
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f }
    private val playheadPaint = Paint().apply { strokeWidth = 4f }
    private val handlePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                setShadowLayer(8f, 0f, 4f, Color.argb(100, 0, 0, 0))
            }
    private val handleGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
    private val handleBorderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor("#33FFFFFF") // Subdued border
            }

    private val indicatorPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 24f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.MONOSPACE
                color = Color.parseColor("#00D2D3")
            }

    private val indicatorBgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A00D2D3") // Very faint cyan bg for indicators
            }

    private val fillerTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 34f
                color = Color.parseColor("#80FFFFFF")
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            }

    private val indicatorWidth = 50f
    private val autoScrollEdgePx = 80f // distance from edge to trigger
    private val autoScrollSpeed = 20f // px per frame

    private val clipWaveforms = mutableMapOf<String, List<Float>>()
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    private val thumbnailRetriever = android.media.MediaMetadataRetriever()
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val waveformPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00D2D3") // Theme Cyan
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
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#151515") }
    private val clipStrokePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = Color.parseColor("#00D2D3")
                setShadowLayer(15f, 0f, 0f, Color.parseColor("#CC00D2D3"))
            }
    private val trimLinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00D2D3")
                strokeWidth = 4f
            }
    private val headerBgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#151515")
                style = Paint.Style.FILL
            }
    private val subtleBorderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22FFFFFF")
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
    private val iconBgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A000000") }
    private val reusableTrackPath = Path()
    private val reusableBarRect = RectF()

    init {
        resolveThemeColors()
        isHapticFeedbackEnabled = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
    private fun resolveThemeColors() {
        val tv = TypedValue()

        trackBgPaint.color =
                if (context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true))
                        tv.data
                else Color.parseColor("#121212")

        // 🍏 V4 Neon Glow for Playhead
        playheadPaint.color = Color.parseColor("#FFD700") // Neon Gold
        playheadPaint.strokeWidth = 4f

        val textColor =
                if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true))
                        tv.data
                else Color.WHITE

        rulerPaint.color = textColor
        minorTickPaint.color = Color.parseColor("#00D2D3") // Cyan for sub-second markers
        textPaint.color = textColor

        thumbnailOverlayPaint.color = Color.BLACK
        thumbnailOverlayPaint.alpha = 40
    }

    // ---------- PUBLIC API ----------
    fun setProject(project: TimelineProject) {
        this.project = project
        loadWaveformsForProject()
        invalidate()
    }

    /** Programmatically selects a clip and updates the UI. */
    fun selectClip(clip: TimelineClip?) {
        selectedClip = clip
        onClipSelected?.invoke(clip)
        invalidate()
    }

    fun animateClip(clipId: String) {
        animatedClipId = clipId
        animationStartTime = SystemClock.uptimeMillis()
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
        invalidate()
    }

    fun seekTo(timeMs: Long) {
        currentTimeMs = timeMs
        ensurePlayheadVisible()
        invalidate()
    }

    private fun getTrackHeight(type: TrackType): Float =
            when (type) {
                TrackType.VIDEO_AUDIO -> 100f
                TrackType.OVERLAY -> trackHeight * 0.4f
                else -> trackHeight
            }

    private fun getVisibleTracks(): List<com.example.videoeditorapp.model.timeline.TimelineTrack> {
        val proj = project ?: return emptyList()
        val allTracks = proj.tracks
        return allTracks.filter { it.clips.isNotEmpty() }.ifEmpty {
            listOfNotNull(allTracks.find { it.type == TrackType.VIDEO } ?: allTracks.firstOrNull())
        }
    }

    // ---------- EDITING ACTIONS ----------

    /**
     * Splits a specific clip at the given global timeline time. Returns the newly created second
     * half of the clip.
     */
    fun splitClip(clip: TimelineClip, splitTimeMs: Long): TimelineClip? {
        val proj = project ?: return null
        val track = proj.tracks.find { it.clips.contains(clip) } ?: return null

        // Verify cursor is within clip (and not at edges)
        if (splitTimeMs <= clip.startTimeMs + 100 ||
                        splitTimeMs >= clip.startTimeMs + clip.durationMs - 100
        ) {
            return null
        }

        // Calculate split dynamics
        val relativeSplit = splitTimeMs - clip.startTimeMs
        val originalDur = clip.durationMs
        val firstPartDur = relativeSplit
        val secondPartDur = originalDur - relativeSplit

        // Update first part (Original Clip)
        clip.durationMs = firstPartDur

        // Create second part
        val newClip =
                clip.copy(
                        id = UUID.randomUUID().toString(),
                        startTimeMs = splitTimeMs,
                        durationMs = secondPartDur,
                        sourceStartTimeMs = clip.sourceStartTimeMs + firstPartDur
                )

        track.clips.add(newClip)
        track.clips.sortBy { it.startTimeMs }

        onTimelineChanged?.invoke(currentTimeMs)
        invalidate()
        return newClip
    }

    fun splitCallback() {
        val clip = selectedClip ?: return
        val newPart = splitClip(clip, currentTimeMs)
        if (newPart != null) {
            selectedClip = newPart
            invalidate()
        }
    }

    // ---------- RULER ----------
    private fun drawRuler(canvas: Canvas) {
        // 🍏 V4 Adaptive Step Calculation: Ensuring clean UI at all zoom levels
        val (step, sub) =
                when {
                    pixelsPerSecond > 600 ->
                            1 to 50 // Every 1s major, 0.02s minor (Extreme precision)
                    pixelsPerSecond > 300 -> 1 to 20 // Every 1s major, 0.05s minor
                    pixelsPerSecond > 100 -> 1 to 10 // Every 1s major, 0.1s minor
                    pixelsPerSecond > 50 -> 2 to 4 // Every 2s major, 0.5s minor
                    pixelsPerSecond > 20 -> 5 to 5 // Every 5s major, 1s minor
                    else -> 10 to 5 // Every 10s major, 2s minor
                }

        val startStep = floor(scrollXOffset / (pixelsPerSecond * step)).toInt()
        val endStep =
                ((scrollXOffset + width - HEADER_WIDTH) / (pixelsPerSecond * step)).toInt() + 1

        for (s in startStep..endStep) {
            val sec = s * step
            val baseX = HEADER_WIDTH + (sec * pixelsPerSecond - scrollXOffset)

            if (baseX < HEADER_WIDTH - 20) continue

            val isCurrentSec = (currentTimeMs / 1000).toInt() == sec

            // 1. Draw the major tick
            rulerPaint.color = if (isCurrentSec) Color.parseColor("#00D2D3") else Color.WHITE
            canvas.drawLine(baseX, 0f, baseX, rulerHeight * 0.7f, rulerPaint)

            // 2. Select paint (Highlight color if it's the current second)
            val paint =
                    if (isCurrentSec)
                            highlightTextPaint.apply { color = Color.parseColor("#00D2D3") }
                    else rulerPaint

            // 3. DRAW TEXT BELOW THE TICKS
            canvas.drawText(
                    com.example.videoeditorapp.utils.ViewUtils.formatTime(sec * 1000L),
                    baseX + 6,
                    rulerHeight - 4f,
                    paint
            )

            // 4. Draw Minor Ticks (Adaptive density)
            if (sub > 1) {
                val subInterval = (pixelsPerSecond * step) / sub
                val minorTickH = rulerHeight * 0.3f
                for (i in 1 until sub) {
                    val x = baseX + i * subInterval
                    if (x >= 0 && x <= width) {
                        // Cyan accent for marks playhead is "touching" - within 50ms
                        val markTimeMs = (sec * 1000L) + (i * step * 1000L / sub)
                        val isNearPlayhead = abs(markTimeMs - currentTimeMs) < 50

                        minorTickPaint.color =
                                if (isNearPlayhead) Color.parseColor("#00D2D3")
                                else Color.parseColor("#4DFFFFFF")
                        canvas.drawLine(x, 0f, x, minorTickH, minorTickPaint)
                    }
                }
            }
        }
    }
    private var lastHapticMs = 0L
    private var lastHapticTimeMs = 0L

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun tickHaptic(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastHapticTimeMs < 50) return
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(5)
            }
            lastHapticTimeMs = now
        }
    }

    private fun snapHaptic() {
        if (vibrator.hasVibrator()) {
            val now = SystemClock.uptimeMillis()
            if (now - lastHapticTimeMs < 100) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(15)
            }
            lastHapticTimeMs = now
        }
    }

    private val emptyTrackTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 32f
                color = Color.GRAY
                textAlign = Paint.Align.CENTER
            }
    private fun getClipColor(clip: TimelineClip, trackType: TrackType): Int {
        val seed = clip.filePath.hashCode()
        val random = java.util.Random(seed.toLong())

        // Base Hue based on track type
        val baseHue =
                when (trackType) {
                    TrackType.VIDEO -> 200f
                    TrackType.VIDEO_AUDIO -> 180f
                    TrackType.AUDIO -> 100f
                    TrackType.OVERLAY -> 30f
                }

        val hsv = FloatArray(3)
        hsv[0] = (baseHue + (random.nextFloat() * 20f - 10f)).coerceIn(0f, 360f)
        hsv[1] = 0.5f + random.nextFloat() * 0.3f // Saturation
        hsv[2] = 0.6f + random.nextFloat() * 0.3f // Brightness

        // Adjust shade for specific instance (clip.id)
        val instanceRandom = java.util.Random(clip.id.hashCode().toLong())
        val shadeShift = 0.9f + instanceRandom.nextFloat() * 0.2f
        hsv[2] = (hsv[2] * shadeShift).coerceIn(0.4f, 1.0f)

        // 🔥 MIRRORED AUDIO SHADING: Make it slightly darker/muted
        if (trackType == TrackType.VIDEO_AUDIO) {
            hsv[1] = (hsv[1] * 0.8f).coerceIn(0f, 1.0f) // less saturated
            hsv[2] = (hsv[2] * 0.7f).coerceIn(0f, 1.0f) // darker
        }

        return Color.HSVToColor(hsv)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val visibleTracks = getVisibleTracks()
        var totalHeight = 0f
        visibleTracks.forEachIndexed { idx, track ->
            val h = getTrackHeight(track.type)
            val isLinkedWithNext = isTrackLinkedWithNext(idx, visibleTracks)
            totalHeight += h + (if (isLinkedWithNext) 0f else trackSpacing)
        }

        val finalHeight = (totalHeight + rulerHeight + 200f).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }

    private fun isTrackLinkedWithNext(
            idx: Int,
            tracks: List<com.example.videoeditorapp.model.timeline.TimelineTrack>
    ): Boolean {
        if (idx >= tracks.size - 1) return false
        val current = tracks[idx]
        val next = tracks[idx + 1]

        // 1. Check Hardcoded Types (Legacy grouping)
        if (current.type == TrackType.VIDEO && next.type == TrackType.VIDEO_AUDIO) return true

        // 2. Check Dynamic Links
        return current.clips.any { cc ->
            val linked = findLinkedClips(cc)
            next.clips.any { nc -> linked.any { it.id == nc.id } }
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 🍏 V4: Filter visible tracks (Hide empty ones to clean up UI)
        val visibleTracks = getVisibleTracks()

        // 1. Calculate and update Scroll Bounds
        var totalHeight = 0f
        visibleTracks.forEachIndexed { idx, track ->
            val isLinkedWithNext = isTrackLinkedWithNext(idx, visibleTracks)
            val h = getTrackHeight(track.type)
            totalHeight += h + (if (isLinkedWithNext) 0f else trackSpacing)
        }

        // Ensure the view is tall enough for the NestedScrollView
        val measuredHeight = (totalHeight + rulerHeight + 200f).toInt()
        if (layoutParams.height != measuredHeight &&
                        layoutParams.height == android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            // This is a bit hacky but ensures NestedScrollView sees the height
            // We'll skip this for now and just use local drawing if possible
        }

        maxScrollY = (totalHeight + rulerHeight + 100f - height).coerceAtLeast(0f)

        // 2. LAYER 1: TRACKS & CLIPS (Clipped AFTER HEADER)
        canvas.save()
        canvas.clipRect(HEADER_WIDTH, rulerHeight, width.toFloat(), height.toFloat())
        var currentY = rulerHeight + trackSpacing - scrollYOffset
        val drawnSelectedRects = mutableListOf<RectF>()

        visibleTracks.forEachIndexed { indexOfVisible, track ->
            val isLinkedWithNext = isTrackLinkedWithNext(indexOfVisible, visibleTracks)
            val isLinkedWithPrev =
                    indexOfVisible > 0 && isTrackLinkedWithNext(indexOfVisible - 1, visibleTracks)

            val effectiveTrackHeight = getTrackHeight(track.type)

            // ───── Track background ─────
            val trackTopMargin = if (isLinkedWithPrev) 0f else 8f
            val trackBottomMargin = if (isLinkedWithNext) 0f else 8f

            val rowRect =
                    RectF(
                            HEADER_WIDTH,
                            currentY + trackTopMargin,
                            width.toFloat(),
                            currentY + effectiveTrackHeight - trackBottomMargin
                    )

            // Draw improved background with solid color
            reusableTrackPath.reset()
            reusableTrackPath.addRect(rowRect, Path.Direction.CW)

            // shadow
            canvas.drawPath(reusableTrackPath, trackShadowPaint)
            // bg
            canvas.drawPath(reusableTrackPath, improvedBgPaint)
            // Redundant playhead drawing removed here (drawn once at top layer now)
            // ───── DRAW CLIPS ─────
            track.clips.forEach { clip ->
                val startX =
                        HEADER_WIDTH + (clip.startTimeMs / 1000f) * pixelsPerSecond - scrollXOffset
                val clipWidth = (clip.durationMs / 1000f) * pixelsPerSecond

                if (startX + clipWidth <= HEADER_WIDTH || startX >= width) return@forEach
                val left = startX
                val right = startX + clipWidth

                val clipRect =
                        RectF(left, currentY + 4f, right, currentY + effectiveTrackHeight - 4f)

                // 🔹 Breathing Effect for newly added clip
                var scale = 1.0f
                if (clip.id == animatedClipId) {
                    val elapsed = SystemClock.uptimeMillis() - animationStartTime
                    if (elapsed < breatheDuration) {
                        val progress = elapsed / breatheDuration.toFloat()
                        // Smoother ease-in-out sine for premium feel
                        scale = 1.0f + 0.08f * kotlin.math.sin(progress * Math.PI).toFloat()
                        invalidate()
                    } else {
                        animatedClipId = null
                    }
                }

                if (scale != 1.0f) {
                    canvas.save()
                    canvas.scale(scale, scale, clipRect.centerX(), clipRect.centerY())
                }

                // Draw Background
                clipPaint.color = getClipColor(clip, track.type)
                if (selectedClip?.id == clip.id) {
                    clipPaint.style = Paint.Style.FILL
                    clipPaint.setShadowLayer(14f, 0f, 0f, Color.WHITE)
                } else {
                    clipPaint.setShadowLayer(0f, 0f, 0f, 0)
                }
                canvas.drawRoundRect(clipRect, 8f, 8f, clipShadowPaint) // Shadow first
                canvas.drawRoundRect(clipRect, 8f, 8f, clipPaint)

                // Draw Thumbnail (Video/Image/Sticker/Emoji/Audio)
                if (clip.type == ClipType.VIDEO ||
                                clip.type == ClipType.IMAGE ||
                                clip.type == ClipType.STICKER ||
                                clip.type == ClipType.EMOJI ||
                                clip.type == ClipType.GIF ||
                                clip.type == ClipType.AUDIO
                ) {
                    drawClipThumbnail(canvas, clip, clipRect)
                }

                // Draw Waveform for Audio/Video-Audio tracks
                if (track.type == TrackType.AUDIO || track.type == TrackType.VIDEO_AUDIO) {
                    drawWaveform(canvas, clip, clipRect)
                }

                // Unified clip naming for ALL clip types
                val rawName =
                        when (clip.type) {
                            ClipType.TEXT -> clip.textSettings["TEXT"]
                                            ?: clip.textSettings["text"] ?: "Text"
                            else -> java.io.File(clip.filePath).name.substringBeforeLast('.')
                        }
                val textX =
                        if (clip.type != ClipType.TEXT) clipRect.left + clipRect.height() + 10f
                        else clipRect.left + 16f

                // Truncate smartly for all types
                val availableWidth = clipRect.width() - (textX - clipRect.left) - 16f
                var displayName = rawName

                if (availableWidth > 40f) {
                    val maxChars =
                            (availableWidth / textPaint.measureText("A")).toInt().coerceAtLeast(4)
                    if (rawName.length > maxChars) {
                        val keep = maxChars / 2
                        displayName = rawName.take(keep) + "…" + rawName.takeLast(keep)
                    }
                }

                canvas.save()

                // Clip strictly to the clip container
                canvas.clipRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom)

                // Prevent drawing text beyond right edge
                val safeX = textX.coerceAtMost(clipRect.right - 12f)

                canvas.drawText(
                        displayName,
                        safeX,
                        clipRect.centerY() + (textPaint.textSize / 3f), // Better vertical centering
                        textPaint
                )

                canvas.restore()

                // 🍏 Hub Asset Indicator (Verified Badge)
                if (clip.metadata["FROM_HUB"] == "true") {
                    drawClipBadge(canvas, clipRect)
                }
                // Add to selected rects if this clip is selected or linked to selection
                val isSelectedOrLinked =
                        selectedClip?.let { sel ->
                            if (sel.id == clip.id) true
                            else {
                                // Check if it's linked
                                val linked = findLinkedClips(sel)
                                linked.any { it.id == clip.id }
                            }
                        }
                                ?: false

                if (isSelectedOrLinked) {
                    drawnSelectedRects.add(RectF(clipRect))
                }

                if (scale != 1.0f) canvas.restore()
            }

            currentY += effectiveTrackHeight + (if (isLinkedWithNext) 0f else trackSpacing)
        }

        canvas.restore() // End of scrollable track area

        // ───── DRAW FILLER FOR EMPTY PROJECT ─────
        if (visibleTracks.isEmpty()) {
            canvas.drawText(
                    "Start your masterpiece",
                    width / 2f,
                    height / 2f + rulerHeight,
                    fillerTextPaint
            )
        }

        // ────────────────────────────────────────────────────────
        // LAYER 2: PLAYHEAD LINE (Behind headers but over clips)
        // ────────────────────────────────────────────────────────
        val playheadX = HEADER_WIDTH + (currentTimeMs / 1000f) * pixelsPerSecond - scrollXOffset
        canvas.save()
        canvas.clipRect(HEADER_WIDTH, rulerHeight, width.toFloat(), height.toFloat())
        canvas.drawLine(playheadX, rulerHeight, playheadX, height.toFloat(), playheadPaint)
        canvas.restore()

        // ────────────────────────────────────────────────────────
        // LAYER 2.5: UNIFIED SELECTION & HANDLES (Over playhead)
        // ────────────────────────────────────────────────────────
        canvas.save()
        canvas.clipRect(HEADER_WIDTH, 0f, width.toFloat(), height.toFloat())
        if (drawnSelectedRects.isNotEmpty()) {
            val groupBounds = RectF(drawnSelectedRects[0])
            drawnSelectedRects.forEach { groupBounds.union(it) }

            // 1. Draw single high-contrast selection block
            canvas.drawRoundRect(groupBounds, 12f, 12f, clipStrokePaint)

            // 2. Draw single set of trim handles for the whole group
            if (activeTool == TimelineTool.TRIM) {
                val handleW = 32f // Pill width
                val handleH = groupBounds.height() * 0.7f // Larger height of track for pill
                val handleCorner = 14f
                val accentColor = Color.parseColor("#00D2D3") // Industry standard Cyan

                // Left Handle (Pill)
                val leftHandlePill =
                        RectF(
                                groupBounds.left - handleW / 2,
                                groupBounds.centerY() - handleH / 2,
                                groupBounds.left + handleW / 2,
                                groupBounds.centerY() + handleH / 2
                        )
                handlePaint.color = accentColor
                canvas.drawRoundRect(leftHandlePill, handleCorner, handleCorner, handlePaint)

                // Right Handle (Pill)
                val rightHandlePill =
                        RectF(
                                groupBounds.right - handleW / 2,
                                groupBounds.centerY() - handleH / 2,
                                groupBounds.right + handleW / 2,
                                groupBounds.centerY() + handleH / 2
                        )
                handlePaint.color = accentColor
                canvas.drawRoundRect(rightHandlePill, handleCorner, handleCorner, handlePaint)

                // Single Vertical Grip Line for each handle
                handleGripPaint.color = Color.WHITE
                handleGripPaint.strokeWidth = 3f
                handleGripPaint.strokeCap = Paint.Cap.ROUND

                // Left grip
                canvas.drawLine(
                        leftHandlePill.centerX(),
                        leftHandlePill.top + 8f,
                        leftHandlePill.centerX(),
                        leftHandlePill.bottom - 8f,
                        handleGripPaint
                )
                // Right grip
                canvas.drawLine(
                        rightHandlePill.centerX(),
                        rightHandlePill.top + 8f,
                        rightHandlePill.centerX(),
                        rightHandlePill.bottom - 8f,
                        handleGripPaint
                )

                // High contrast edge lines for selection
                trimLinePaint.color = accentColor
                trimLinePaint.strokeWidth = 4f
                canvas.drawLine(
                        groupBounds.left,
                        groupBounds.top,
                        groupBounds.left,
                        groupBounds.bottom,
                        trimLinePaint
                )
                canvas.drawLine(
                        groupBounds.right,
                        groupBounds.top,
                        groupBounds.right,
                        groupBounds.bottom,
                        trimLinePaint
                )
            }
        }
        canvas.restore()

        // ────────────────────────────────────────────────────────
        // LAYER 3: FIXED TRACK HEADERS (Left side pinned)
        // Draw in two passes to ensure backgrounds don't overlap icons in merged groups
        // ────────────────────────────────────────────────────────
        val headerStartY = rulerHeight + trackSpacing - scrollYOffset
        var passY = headerStartY

        // Pass 1: Backgrounds and Borders
        visibleTracks.forEachIndexed { idx, track ->
            val h = getTrackHeight(track.type)
            val isLinked = isTrackLinkedWithNext(idx, visibleTracks)
            val headerRect = RectF(0f, passY, HEADER_WIDTH, passY + h)

            canvas.drawRect(headerRect, headerBgPaint)
            canvas.drawLine(
                    headerRect.right - 1f,
                    headerRect.top,
                    headerRect.right - 1f,
                    headerRect.bottom,
                    subtleBorderPaint
            )

            passY += h + (if (isLinked) 0f else trackSpacing)
        }

        // Pass 2: Icons and Combined Indicators
        passY = headerStartY
        visibleTracks.forEachIndexed { idx, track ->
            val h = getTrackHeight(track.type)
            val isLinkedNext = isTrackLinkedWithNext(idx, visibleTracks)
            val isLinkedPrev = idx > 0 && isTrackLinkedWithNext(idx - 1, visibleTracks)

            if (!isLinkedPrev) {
                // Determine group height
                var groupHeight = h
                var nextIdx = idx
                while (isTrackLinkedWithNext(nextIdx, visibleTracks)) {
                    val nextTrack = visibleTracks[nextIdx + 1]
                    groupHeight += getTrackHeight(nextTrack.type)
                    nextIdx++
                }

                val iconRes =
                        when {
                            nextIdx > idx -> R.drawable.ic_video_audio_combined
                            track.type == TrackType.VIDEO -> R.drawable.ic_video_label
                            track.type == TrackType.VIDEO_AUDIO ->
                                    R.drawable.ic_video_audio_combined
                            track.type == TrackType.AUDIO -> R.drawable.ic_music_note
                            track.type == TrackType.OVERLAY -> {
                                val firstClip = track.clips.firstOrNull()
                                when (firstClip?.type) {
                                    ClipType.TEXT -> R.drawable.ic_text
                                    ClipType.IMAGE -> R.drawable.ic_camera_alt
                                    ClipType.STICKER -> R.drawable.ic_sticker
                                    ClipType.EMOJI -> R.drawable.ic_emoji
                                    else -> R.drawable.ic_layers
                                }
                            }
                            else -> R.drawable.ic_layers
                        }

                val iconDrawRect = RectF(0f, passY, HEADER_WIDTH, passY + groupHeight)

                // Premium Glow
                iconBgPaint.setShadowLayer(15f, 0f, 0f, Color.parseColor("#6000D2D3"))
                canvas.drawCircle(iconDrawRect.centerX(), iconDrawRect.centerY(), 34f, iconBgPaint)
                iconBgPaint.clearShadowLayer()

                drawTrackIcon(canvas, iconRes, iconDrawRect)
            }

            if (track.isMuted) {
                canvas.drawCircle(
                        HEADER_WIDTH - 15f,
                        passY + 15f,
                        6f,
                        Paint().apply { color = Color.RED }
                )
            }
            passY += h + (if (isLinkedNext) 0f else trackSpacing)
        }

        canvas.drawRect(0f, 0f, width.toFloat(), rulerHeight, trackBgPaint)
        drawRuler(canvas)
    }

    private fun drawClipThumbnail(canvas: Canvas, clip: TimelineClip, rect: RectF) {
        val bitmap = getThumbnail(clip)
        if (bitmap != null) {
            canvas.save()
            val thumbWidth = rect.height() * (bitmap.width.toFloat() / bitmap.height)
            val thumbRect =
                    RectF(
                            rect.left,
                            rect.top,
                            (rect.left + thumbWidth).coerceAtMost(rect.right),
                            rect.bottom
                    )
            canvas.clipRect(thumbRect.left, thumbRect.top, thumbRect.right, thumbRect.bottom)

            val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, srcRect, thumbRect, null)

            // Add a subtle overlay to separate thumb from text
            canvas.drawRect(thumbRect, thumbnailOverlayPaint)
            canvas.restore()
        } else if (clip.type == ClipType.AUDIO) {
            // Draw a musical note icon as fallback for audio with a circular background
            val iconSize = rect.height() * 0.5f
            val iconRes = R.drawable.ic_music_note
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes)

            val centerX = rect.left + rect.height() / 2f
            val centerY = rect.top + rect.height() / 2f

            canvas.drawCircle(centerX, centerY, rect.height() * 0.35f, iconBgPaint)

            drawable?.let {
                val l = (centerX - iconSize / 2).toInt()
                val t = (centerY - iconSize / 2).toInt()
                it.setBounds(l, t, (l + iconSize).toInt(), (t + iconSize).toInt())
                it.setTint(Color.parseColor("#00D2D3"))
                it.draw(canvas)
            }
        }
    }

    private fun drawClipBadge(canvas: Canvas, clipRect: RectF) {
        val badgeSize = 20f
        val badgeX = clipRect.right - badgeSize - 8f
        val badgeY = clipRect.top + 8f

        val badgeDrawable =
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_verified)

        badgeDrawable?.let {
            it.setBounds(
                    badgeX.toInt(),
                    badgeY.toInt(),
                    (badgeX + badgeSize).toInt(),
                    (badgeY + badgeSize).toInt()
            )
            it.setTint(Color.parseColor("#00D2D3"))
            it.draw(canvas)
        }
    }

    private fun getThumbnail(clip: TimelineClip): Bitmap? {
        val cacheKey = "${clip.filePath}_${clip.sourceStartTimeMs}"
        thumbnailCache[cacheKey]?.let {
            return it
        }

        try {
            // For static images/stickers, use BitmapFactory directly
            if (clip.type == ClipType.IMAGE ||
                            clip.type == ClipType.STICKER ||
                            clip.type == ClipType.EMOJI
            ) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(clip.filePath, opts)

                opts.inJustDecodeBounds = false
                opts.inSampleSize = (opts.outWidth / 200).coerceAtLeast(1)

                val bitmap = BitmapFactory.decodeFile(clip.filePath, opts)
                if (bitmap != null) {
                    thumbnailCache[cacheKey] = bitmap
                    return bitmap
                }
            }

            // For audio, try to get embedded picture
            if (clip.type == ClipType.AUDIO) {
                thumbnailRetriever.setDataSource(clip.filePath)
                val art = thumbnailRetriever.embeddedPicture
                if (art != null) {
                    val bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, 200, 200, true)
                        thumbnailCache[cacheKey] = scaled
                        return scaled
                    }
                }
            }

            // For videos, use MediaMetadataRetriever
            thumbnailRetriever.setDataSource(clip.filePath)
            val timeUs = clip.sourceStartTimeMs * 1000L
            val bitmap =
                    thumbnailRetriever.getFrameAtTime(
                            timeUs,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
            if (bitmap != null) {
                // Scale down for memory efficiency
                val scaled =
                        Bitmap.createScaledBitmap(
                                bitmap,
                                200,
                                (200 * bitmap.height / bitmap.width).coerceAtLeast(1),
                                true
                        )
                thumbnailCache[cacheKey] = scaled
                return scaled
            }
        } catch (e: Exception) {
            // Log or ignore
        }
        return null
    }

    private val shadowPaint =
            Paint().apply {
                color = Color.BLACK
                alpha = 80
            }

    private fun drawWaveform(canvas: Canvas, clip: TimelineClip, rect: RectF) {
        val peaks = clipWaveforms[clip.filePath] ?: return
        if (peaks.isEmpty()) return

        canvas.save()
        canvas.clipRect(rect)

        val maxHeight = rect.height() * 0.9f
        val barWidth = 3f
        val gap = 1.5f
        val totalBarWidth = barWidth + gap

        val numBarsToDraw = (rect.width() / totalBarWidth).toInt()

        // Use theme cyan with dynamic alpha based on peak value for a more "alive" look
        waveformPaint.style = Paint.Style.FILL
        val baseColor = Color.parseColor("#00D2D3")

        for (i in 0 until numBarsToDraw) {
            val peakIndex =
                    (i.toFloat() / numBarsToDraw * peaks.size).toInt().coerceIn(0, peaks.size - 1)
            val peak = peaks[peakIndex]

            val x = rect.left + i * totalBarWidth
            val h = (peak * maxHeight).coerceAtLeast(4f)

            // Bars grow from bottom to top (Premiere Pro style)
            val bottom = rect.bottom
            val top = bottom - h

            reusableBarRect.set(x, top, x + barWidth, bottom)

            // Dynamic alpha for a "glowing" waveform effect
            waveformPaint.color = baseColor
            waveformPaint.alpha = (100 + (peak * 155)).toInt().coerceIn(0, 255)

            canvas.drawRoundRect(reusableBarRect, 2f, 2f, waveformPaint)
        }

        canvas.restore()
    }

    override fun onSaveInstanceState(): android.os.Parcelable? {
        val superState = super.onSaveInstanceState()
        val bundle = android.os.Bundle()
        bundle.putParcelable("superState", superState)
        bundle.putFloat("scrollXOffset", scrollXOffset)
        bundle.putFloat("pixelsPerSecond", pixelsPerSecond)
        return bundle
    }

    override fun onRestoreInstanceState(state: android.os.Parcelable?) {
        if (state is android.os.Bundle) {
            scrollXOffset = state.getFloat("scrollXOffset", 0f)
            // pixelsPerSecond = state.getFloat("pixelsPerSecond", 100f) // If mutable

            val superState =
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        state.getParcelable("superState", android.view.AbsSavedState::class.java)
                    } else {
                        @Suppress("DEPRECATION") state.getParcelable("superState")
                    }
            super.onRestoreInstanceState(superState)
        } else {
            super.onRestoreInstanceState(state)
        }
        invalidate()
    }

    fun getCurrentTimeMs(): Long = currentTimeMs
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    fun skipToNextClip() {
        val proj = project ?: return
        val allPoints = mutableListOf<Long>()

        proj.tracks.forEach { track ->
            track.clips.forEach { clip ->
                allPoints.add(clip.startTimeMs)
                allPoints.add(clip.startTimeMs + clip.durationMs)
            }
        }
        allPoints.add(proj.getDurationMs()) // Explicitly add end of timeline

        val nextPoint = allPoints.filter { it > currentTimeMs + 50 }.minOrNull()

        nextPoint?.let {
            seekTo(it)
            onScrollListener?.invoke(it)
            tickHaptic(force = true)
        }
    }

    fun skipToPreviousClip() {
        val proj = project ?: return
        val allPoints = mutableListOf<Long>()

        proj.tracks.forEach { track ->
            track.clips.forEach { clip ->
                allPoints.add(clip.startTimeMs)
                allPoints.add(clip.startTimeMs + clip.durationMs)
            }
        }
        allPoints.add(0L) // Explicitly add start

        // Find the largest point that is smaller than current time
        val prevPoint =
                allPoints.filter { it < currentTimeMs - 50 }.maxOrNull()
                        ?: 0L // Default to start of timeline

        seekTo(prevPoint)
        onScrollListener?.invoke(prevPoint)
        tickHaptic()
    }
    private fun hapticSnap() {
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(5, 40)) // very light
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(5)
            }
        }
    }

    private fun hapticTrim() {
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(
                    VibrationEffect.createOneShot(
                            6, // softer
                            VibrationEffect.DEFAULT_AMPLITUDE
                    )
            )
        }
    }
    private fun snapWithHaptics(rawValue: Long, snapPoints: List<Long>, onSnap: () -> Unit): Long {
        val px = rawValue / 1000f * pixelsPerSecond

        for (p in snapPoints) {
            val pPx = p / 1000f * pixelsPerSecond
            if (abs(px - pPx) < snapThresholdPx) {

                // 🔥 SNAP ENTERED
                if (activeSnapPointMs != p) {
                    activeSnapPointMs = p
                    onSnap()
                }
                return p
            }
        }

        // 🔥 SNAP EXITED
        activeSnapPointMs = null
        return rawValue
    }
    @RequiresApi(Build.VERSION_CODES.O_MR1)
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
                // --- CapCut-style ruler logic ---
                rulerSwipeTriggered = false
                rulerDownTime = SystemClock.uptimeMillis()

                onInteractionStart?.invoke()
                scroller.forceFinished(true)

                downX = x
                downY = y
                rulerStartX = x
                rulerStartScrollX = scrollXOffset

                dragStartX = x
                scrollStartX = scrollXOffset
                scrollStartY = scrollYOffset

                isDragging = false
                isScrollingTimeline = false
                isScrubbingRuler = false

                val isOnRuler = y <= rulerHeight
                val playheadX =
                        HEADER_WIDTH + (currentTimeMs / 1000f) * pixelsPerSecond - scrollXOffset
                val touchingPlayhead = abs(x - playheadX) < 40f

                if (isOnRuler && touchingPlayhead) {
                    // Start scrubbing sequence
                    isScrubbingRuler = true
                    tickHaptic(true)
                    removeCallbacks(autoScrollRunnable)
                    postOnAnimation(autoScrollRunnable)
                }

                val found = if (isScrubbingRuler) null else clipAtTouch
                selectedClip = found
                onClipSelected?.invoke(found)

                found?.let { clip ->
                    clipOriginalStartMs = clip.startTimeMs
                    clipOriginalDurationMs = clip.durationMs

                    if (activeTool == TimelineTool.TRIM) {
                        val startX =
                                HEADER_WIDTH + (clip.startTimeMs / 1000f) * pixelsPerSecond -
                                        scrollXOffset
                        val endX = startX + (clip.durationMs / 1000f) * pixelsPerSecond

                        trimMode =
                                when {
                                    abs(x - startX) < 32f -> 1 // Increased hit zone
                                    abs(x - endX) < 32f -> 2
                                    else -> -1
                                }
                    } else if (activeTool == TimelineTool.SELECT) {
                        trimMode = 0
                    }

                    val linked = findLinkedClips(clip)
                    currentLinkedClips = linked
                    clipOriginalOffsets =
                            linked.associate { it.id to (it.startTimeMs - clip.startTimeMs) }
                }

                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true

                // Ruler interaction (scrubbing or scrolling)
                if (downY <= rulerHeight) {
                    val dx = x - rulerStartX
                    val holdTime = SystemClock.uptimeMillis() - rulerDownTime

                    if (isScrubbingRuler) {
                        currentTimeMs =
                                (((scrollXOffset + x - HEADER_WIDTH) / pixelsPerSecond) * 1000)
                                        .toLong()
                                        .coerceAtLeast(0)
                        onScrollListener?.invoke(currentTimeMs)
                        handleAutoScrollInternal(x) // Check for edge scrolling
                        invalidate()
                        return true
                    }

                    if (!isScrollingTimeline && !isScrubbingRuler) {
                        if (abs(dx) > TAP_SLOP_PX) {
                            isScrollingTimeline = true
                        } else if (holdTime > 200) { // Reduced hold time for scrubbing
                            isScrubbingRuler = true
                            tickHaptic(true)
                            removeCallbacks(autoScrollRunnable)
                            postOnAnimation(autoScrollRunnable)
                        }
                    }

                    if (isScrollingTimeline) {
                        val maxW = (project?.getDurationMs() ?: 0L) / 1000f * pixelsPerSecond
                        val visible = width - HEADER_WIDTH
                        val maxScrollX = (maxW - visible).coerceAtLeast(0f)

                        scrollXOffset = (rulerStartScrollX - dx).coerceIn(0f, maxScrollX)
                        invalidate()
                        return true
                    }
                    // If we are in the ruler area but neither scrubbing nor scrolling,
                    // let other gesture detectors handle it (e.g., long press for context menu)
                    return true
                }

                val dx = x - downX
                val dy = y - downY
                val absDx = abs(dx)
                val absDy = abs(dy)

                val dragThreshold =
                        if (selectedClip != null && activeTool == TimelineTool.SELECT)
                                DRAG_MIN_DIST_PX
                        else TAP_SLOP_PX
                val selectThreshold = 14f
                val trimThreshold = 25f

                if (!isDragging) {
                    when (activeTool) {
                        TimelineTool.SELECT -> if (absDx > selectThreshold) isDragging = true
                        TimelineTool.TRIM -> if (absDx > trimThreshold) isDragging = true
                        else ->
                                if (absDx > dragThreshold || absDy > dragThreshold)
                                        isDragging = true
                    }
                    if (isDragging) {
                        removeCallbacks(autoScrollRunnable)
                        postOnAnimation(autoScrollRunnable)
                    }
                }

                if (!isDragging) return true

                selectedClip?.let { clip ->
                    val linkedClips = currentLinkedClips
                    val deltaMs = ((x - dragStartX) / pixelsPerSecond * 1000).toLong()

                    when (activeTool) {
                        TimelineTool.SELECT -> {
                            val proposedStart =
                                    snap(clipOriginalStartMs + deltaMs, getSnapPoints(clip))
                            if (isValidPosition(proposedStart, linkedClips, clipOriginalOffsets)) {
                                if (ghostDragStartTimeMs != proposedStart) {
                                    hapticSnap()
                                    ghostDragStartTimeMs = proposedStart
                                }
                                linkedClips.forEach { lc ->
                                    val offset = clipOriginalOffsets[lc.id] ?: 0L
                                    lc.startTimeMs = proposedStart + offset
                                }
                            }
                            invalidate()
                        }
                        TimelineTool.TRIM -> {
                            if (trimMode == -1) return@let

                            when (trimMode) {
                                1 -> {
                                    // TRIM START
                                    val rawStart = (clipOriginalStartMs + deltaMs).coerceAtLeast(0L)
                                    val newStart = snap(rawStart, getSnapPoints(clip))
                                    val diff = newStart - clipOriginalStartMs

                                    val newDuration = clipOriginalDurationMs - diff
                                    val newSourceStart = clip.sourceStartTimeMs + diff

                                    if (newDuration >= 100 && newSourceStart >= 0) {
                                        linkedClips.forEach {
                                            it.startTimeMs =
                                                    newStart + (clipOriginalOffsets[it.id] ?: 0L)
                                            it.sourceStartTimeMs =
                                                    it.sourceStartTimeMs +
                                                            (newStart - clipOriginalStartMs)
                                            it.durationMs =
                                                    it.durationMs - (newStart - clipOriginalStartMs)
                                        }
                                        hapticTrim()
                                        invalidate()
                                    }
                                }
                                2 -> {
                                    // TRIM END
                                    val newDuration =
                                            (clipOriginalDurationMs + deltaMs).coerceAtLeast(100L)
                                    linkedClips.forEach { it.durationMs = newDuration }
                                    hapticTrim()
                                    invalidate()
                                }
                                else -> {}
                            }
                        }
                        else -> return@let
                    }
                }
                        ?: run {
                            // Fallback: Scrolling if no clip selected or empty space drag
                            val dxFallback = x - downX
                            val maxW = (project?.getDurationMs() ?: 0L) / 1000f * pixelsPerSecond
                            val visible = width - HEADER_WIDTH
                            val maxScrollX = (maxW - visible).coerceAtLeast(0f)

                            scrollXOffset = (scrollStartX - dxFallback).coerceIn(0f, maxScrollX)
                            invalidate()
                        }

                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {

                if (downY <= rulerHeight && !isScrubbingRuler && !isScrollingTimeline) {
                    currentTimeMs =
                            ((x - HEADER_WIDTH + scrollXOffset) / pixelsPerSecond * 1000).toLong()
                    onScrollListener?.invoke(currentTimeMs)
                    invalidate()
                }

                isDragging = false
                isScrubbingRuler = false
                isScrollingTimeline = false
                removeCallbacks(autoScrollRunnable)
                trimMode = 0
                ghostDragStartTimeMs = null
                onInteractionEnd?.invoke()
                notifyDataChanged()
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }
    private fun getClipBounds(clip: TimelineClip, track: List<TimelineClip>): Pair<Long, Long> {
        // Sort clips to find immediate neighbors
        val sorted = track.sortedBy { it.startTimeMs }
        val index = sorted.indexOf(clip)

        // Wall to the left (previous clip's end)
        val minStart =
                if (index > 0) {
                    sorted[index - 1].startTimeMs + sorted[index - 1].durationMs
                } else {
                    0L
                }

        // Wall to the right (next clip's start minus our own duration)
        val maxStart =
                if (index != -1 && index < sorted.size - 1) {
                    sorted[index + 1].startTimeMs - clip.durationMs
                } else {
                    Long.MAX_VALUE - clip.durationMs
                }

        return Pair(minStart, maxStart)
    }

    fun findLinkedClips(clip: TimelineClip): List<TimelineClip> {
        val proj = project ?: return listOf(clip)
        if (clip.metadata["UNLINKED"] == "true") return listOf(clip)

        val linked = mutableListOf<TimelineClip>()
        val toleranceStart = 8L
        val toleranceDuration = 12L

        proj.tracks.forEach { track ->
            track.clips.forEach { c ->
                val sameFile = c.filePath == clip.filePath
                val nearStart = abs(c.startTimeMs - clip.startTimeMs) <= toleranceStart
                val nearDuration = abs(c.durationMs - clip.durationMs) <= toleranceDuration

                if (sameFile && nearStart && nearDuration) {
                    linked.add(c)
                }
            }
        }

        return if (linked.isEmpty()) listOf(clip) else linked
    }
    private fun isValidPosition(
            primaryStartMs: Long,
            linked: List<TimelineClip>,
            offsets: Map<String, Long>
    ): Boolean {
        if (primaryStartMs < 0) return false
        val proj = project ?: return true

        linked.forEach outer@{ lc ->
            val lcOffset = offsets[lc.id] ?: 0L
            val lcNewStart = primaryStartMs + lcOffset
            if (lcNewStart < 0) return false

            val track = proj.tracks.find { it.clips.contains(lc) } ?: return@outer
            track.clips.forEach inner@{ other ->
                if (linked.any { it.id == other.id }) return@inner
                val otherEnd = other.startTimeMs + other.durationMs
                if (lcNewStart < otherEnd && (lcNewStart + lc.durationMs) > other.startTimeMs) {
                    return false
                }
            }
        }
        return true
    }

    private fun findNextAvailableGap(
            startTimeMs: Long,
            durationMs: Long,
            linked: List<TimelineClip>
    ): Long {
        var cursor = startTimeMs.coerceAtLeast(0L)
        val proj = project ?: return cursor

        // 🚀 REFINED: Prefer the original position if it's close and valid
        if (isValidPosition(
                        startTimeMs,
                        linked,
                        linked.associate { it.id to (it.startTimeMs - linked.first().startTimeMs) }
                )
        )
                return startTimeMs

        // 🚀 SEARCH IN BOTH DIRECTIONS FOR NEAREST GAP
        var forwardCursor = startTimeMs
        var backwardCursor = startTimeMs
        val projectEnd = proj.getDurationMs()
        val maxLimit = projectEnd + 30_000L
        val stepSize = 50L // Reduced step size for smoother gap finding

        for (i in 0 until 500) { // Limit search iterations
            // Check forward
            if (forwardCursor < maxLimit) {
                if (isValidPosition(
                                forwardCursor,
                                linked,
                                linked.associate {
                                    it.id to (it.startTimeMs - linked.first().startTimeMs)
                                }
                        )
                )
                        return forwardCursor
                val collision = findFirstCollision(forwardCursor, durationMs, linked)
                forwardCursor =
                        if (collision != null) collision.startTimeMs + collision.durationMs
                        else forwardCursor + stepSize
            }

            // Check backward
            if (backwardCursor > 0) {
                val nextBackward = (backwardCursor - stepSize).coerceAtLeast(0L)
                if (isValidPosition(
                                nextBackward,
                                linked,
                                linked.associate {
                                    it.id to (it.startTimeMs - linked.first().startTimeMs)
                                }
                        )
                )
                        return nextBackward
                // For simplicity, we just step back. Proper backward-collision-jump is complex.
                backwardCursor = nextBackward
            }

            if (forwardCursor >= maxLimit && backwardCursor <= 0) break
        }

        // 🚀 FALLBACK: If forward search failed to find a reasonable gap, search from 0
        cursor = 0L
        while (cursor < startTimeMs) {
            if (isValidPosition(
                            cursor,
                            linked,
                            linked.associate {
                                it.id to (it.startTimeMs - linked.first().startTimeMs)
                            }
                    )
            ) {
                return cursor
            }
            val collision = findFirstCollision(cursor, durationMs, linked)
            if (collision != null) {
                cursor = collision.startTimeMs + collision.durationMs
            } else {
                cursor += stepSize
            }
        }

        return projectEnd // Simply append if no gap fits anywhere
    }

    private fun findFirstCollision(
            startMs: Long,
            durationMs: Long,
            linked: List<TimelineClip>
    ): TimelineClip? {
        val proj = project ?: return null
        linked.forEach outerLoop@{ lc ->
            val track = proj.tracks.firstOrNull { it.clips.contains(lc) } ?: return@outerLoop
            track.clips.forEach innerLoop@{ other ->
                if (linked.any { it.id == other.id }) return@innerLoop
                val otherEnd = other.startTimeMs + other.durationMs
                if (startMs < otherEnd && (startMs + durationMs) > other.startTimeMs) {
                    return other
                }
            }
        }
        return null
    }
    fun duplicateSelectedClip() {
        val clip = selectedClip ?: return
        val proj = project ?: return
        val linked = findLinkedClips(clip)

        val duration = clip.durationMs

        // 1. Try to place at currentTimeMs (playhead)
        var targetStart = currentTimeMs

        // 2. If blocked, find nearest available gap starting from playhead
        val offsets = linked.associate { it.id to (it.startTimeMs - clip.startTimeMs) }
        if (!isValidPosition(targetStart, linked, offsets)) {
            targetStart = findNextAvailableGap(targetStart, duration, linked)
        }

        // 3. Create and add duplicates matching the new synchronized position
        linked.forEach { target ->
            val track = proj.tracks.firstOrNull { it.clips.contains(target) } ?: return@forEach
            val duplicated =
                    target.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            startTimeMs = targetStart
                    )
            track.clips.add(duplicated)
            track.clips.sortBy { it.startTimeMs }

            // If it's the duplicate of our original selection, keep it selected
            if (target == clip) {
                selectedClip = duplicated
            }
        }

        currentTimeMs = targetStart
        onScrollListener?.invoke(currentTimeMs)
        onClipSelected?.invoke(selectedClip) // Sync UI selection
        notifyDataChanged()
        invalidate()
    }
    fun notifyDataChanged() {
        val duration = getTotalDurationMs()
        onTimelineChanged?.invoke(duration) // Sends the Long value to the Activity
        requestLayout()
        invalidate()
    }
    fun getTotalDurationMs(): Long {
        // Find the end of the very last clip across all tracks
        return project?.tracks?.flatMap { it.clips }?.maxOfOrNull { it.startTimeMs + it.durationMs }
                ?: 0L
    }
    fun splitSelectedClip() {
        val clip = selectedClip ?: return
        splitLinkedClips(clip, currentTimeMs)
        notifyDataChanged()
        invalidate()
    }
    fun pasteClipAtCurrentTime(clip: TimelineClip) {
        val proj = project ?: return

        // 1. Determine target track type
        val targetTrackType =
                when (clip.type) {
                    ClipType.AUDIO -> TrackType.AUDIO
                    ClipType.VIDEO -> TrackType.VIDEO
                    else -> TrackType.OVERLAY
                }

        // 2. Find a track of that type
        var track = proj.tracks.firstOrNull { it.type == targetTrackType }
        if (track == null) {
            // Create track if missing
            track = com.example.videoeditorapp.model.timeline.TimelineTrack(type = targetTrackType)
            proj.tracks.add(track)
            proj.tracks.sortWith(
                    compareBy {
                        when (it.type) {
                            TrackType.VIDEO -> 0
                            TrackType.VIDEO_AUDIO -> 1
                            TrackType.AUDIO -> 2
                            TrackType.OVERLAY -> 3
                        }
                    }
            )
        }

        // 3. Find position
        var targetStart = currentTimeMs
        if (!isPositionValidOnTrack(targetStart, clip.durationMs, track)) {
            targetStart = findNextAvailableGapOnTrack(targetStart, clip.durationMs, track)
        }

        // 4. Create and add duplicate
        val pasted =
                clip.copy(id = java.util.UUID.randomUUID().toString(), startTimeMs = targetStart)
        track.clips.add(pasted)
        track.clips.sortBy { it.startTimeMs }

        selectedClip = pasted
        onTimelineChanged?.invoke(currentTimeMs)
        invalidate()
    }

    private fun isPositionValidOnTrack(
            startMs: Long,
            durationMs: Long,
            track: com.example.videoeditorapp.model.timeline.TimelineTrack
    ): Boolean {
        if (startMs < 0) return false
        track.clips.forEach { other ->
            val otherEnd = other.startTimeMs + other.durationMs
            if (startMs < otherEnd && (startMs + durationMs) > other.startTimeMs) {
                return false
            }
        }
        return true
    }

    private fun findTrackAtY(y: Float): com.example.videoeditorapp.model.timeline.TimelineTrack? {
        val visibleTracks = getVisibleTracks()
        var currentY = rulerHeight + trackSpacing - scrollYOffset

        visibleTracks.forEach { track ->
            val h = getTrackHeight(track.type)
            val trackRect = RectF(0f, currentY, width.toFloat(), currentY + h)
            if (trackRect.contains(0f, y)) return track
            currentY += h + trackSpacing
        }
        return null
    }

    private fun findNextAvailableGapOnTrack(
            startTimeMs: Long,
            durationMs: Long,
            track: com.example.videoeditorapp.model.timeline.TimelineTrack
    ): Long {
        var current = startTimeMs
        val sortedClips = track.clips.sortedBy { it.startTimeMs }

        for (clip in sortedClips) {
            if (clip.startTimeMs >= current + durationMs) {
                return current
            }
            if (clip.startTimeMs + clip.durationMs > current) {
                current = clip.startTimeMs + clip.durationMs
            }
        }
        return current
    }

    fun moveSelectedClipToFront() {
        val clip = selectedClip ?: return
        val proj = project ?: return
        val currentTrack = proj.tracks.find { it.clips.contains(clip) } ?: return
        if (currentTrack.type != TrackType.OVERLAY) return

        val overlayTracks = proj.tracks.filter { it.type == TrackType.OVERLAY }
        val currentIndex = overlayTracks.indexOf(currentTrack)

        // Find if any higher track is free at this time
        var targetTrack: TimelineTrack? = null
        for (i in currentIndex + 1 until overlayTracks.size) {
            val track = overlayTracks[i]
            val hasCollision =
                    track.clips.any {
                        (it.startTimeMs < clip.endTimeMs) && (it.endTimeMs > clip.startTimeMs)
                    }
            if (!hasCollision) {
                targetTrack = track
                break
            }
        }

        currentTrack.clips.remove(clip)
        if (targetTrack != null) {
            targetTrack.clips.add(clip)
            targetTrack.clips.sortBy { it.startTimeMs }
        } else {
            // Must create a new track at the very end
            val newTrack =
                    com.example.videoeditorapp.model.timeline.TimelineTrack(
                            type = TrackType.OVERLAY
                    )
            newTrack.clips.add(clip)
            proj.tracks.add(newTrack)
        }

        cleanEmptyTracks(proj)
        onTimelineChanged?.invoke(currentTimeMs)
        invalidate()
    }

    fun moveSelectedClipToBack() {
        val clip = selectedClip ?: return
        val proj = project ?: return
        val currentTrack = proj.tracks.find { it.clips.contains(clip) } ?: return
        if (currentTrack.type != TrackType.OVERLAY) return

        val overlayTracks = proj.tracks.filter { it.type == TrackType.OVERLAY }
        val currentIndex = overlayTracks.indexOf(currentTrack)

        // Find if any lower track is free at this time
        var targetTrack: TimelineTrack? = null
        for (i in currentIndex - 1 downTo 0) {
            val track = overlayTracks[i]
            val hasCollision =
                    track.clips.any {
                        (it.startTimeMs < clip.endTimeMs) && (it.endTimeMs > clip.startTimeMs)
                    }
            if (!hasCollision) {
                targetTrack = track
                break
            }
        }

        currentTrack.clips.remove(clip)
        if (targetTrack != null) {
            targetTrack.clips.add(clip)
            targetTrack.clips.sortBy { it.startTimeMs }
        } else {
            // Must create a new track at the beginning of overlay section
            val newTrack =
                    com.example.videoeditorapp.model.timeline.TimelineTrack(
                            type = TrackType.OVERLAY
                    )
            newTrack.clips.add(clip)
            val insertIndex =
                    proj.tracks.indexOfFirst { it.type == TrackType.OVERLAY }.coerceAtLeast(0)
            proj.tracks.add(insertIndex, newTrack)
        }

        cleanEmptyTracks(proj)
        onTimelineChanged?.invoke(currentTimeMs)
        invalidate()
    }

    private fun cleanEmptyTracks(proj: TimelineProject) {
        // Keep at least one track per type if possible, or just remove if truly empty
        proj.tracks.removeAll { it.clips.isEmpty() && it.type == TrackType.OVERLAY }
    }

    private fun sortTracks(proj: TimelineProject) {
        proj.tracks.sortWith(
                compareBy {
                    when (it.type) {
                        TrackType.VIDEO -> 0
                        TrackType.VIDEO_AUDIO -> 1
                        TrackType.AUDIO -> 2
                        TrackType.OVERLAY -> 3
                    }
                }
        )
    }

    fun deleteSelectedClip() {
        val clip = selectedClip ?: return
        val proj = project ?: return

        val linked = findLinkedClips(clip)

        // Remove all matching clips from all tracks
        proj.tracks.forEach { track -> track.clips.removeAll { c -> linked.any { it.id == c.id } } }

        selectedClip = null
        onClipSelected?.invoke(null)
        notifyDataChanged()
        invalidate()
    }
    // ---------- HELPERS ----------
    private fun splitLinkedClips(clip: TimelineClip, splitTimeMs: Long) {
        val offset = splitTimeMs - clip.startTimeMs
        if (offset <= 0 || offset >= clip.durationMs) return

        val linked = findLinkedClips(clip)

        linked.forEach { target ->
            val targetOffset = splitTimeMs - target.startTimeMs
            if (targetOffset <= 0 || targetOffset >= target.durationMs) return@forEach

            val secondClip =
                    target.copy(
                            id = UUID.randomUUID().toString(),
                            startTimeMs = splitTimeMs,
                            durationMs = target.durationMs - targetOffset,
                            sourceStartTimeMs = target.sourceStartTimeMs + targetOffset,
                            sourceDurationMs = target.sourceDurationMs
                    )

            target.durationMs = targetOffset

            project?.tracks?.forEach trackLoop@{ track ->
                val index = track.clips.indexOf(target)
                if (index != -1) {
                    track.clips.add(index + 1, secondClip)
                    track.clips.sortBy { c -> c.startTimeMs }
                    return@trackLoop
                }
            }
        }

        notifyDataChanged()
        invalidate()
    }

    private fun findClipAt(x: Float, y: Float): TimelineClip? {
        val visibleTracks = getVisibleTracks()
        var yPos = rulerHeight + trackSpacing - scrollYOffset

        visibleTracks.forEachIndexed { idx, track ->
            val isLinkedWithNext = isTrackLinkedWithNext(idx, visibleTracks)
            val h = getTrackHeight(track.type)

            if (y in yPos..(yPos + h)) {
                track.clips.forEach { clip ->
                    val startX =
                            HEADER_WIDTH + clip.startTimeMs / 1000f * pixelsPerSecond -
                                    scrollXOffset
                    val w = clip.durationMs / 1000f * pixelsPerSecond
                    if (x in startX..(startX + w)) return clip
                }
            }
            yPos += h + (if (isLinkedWithNext) 0f else trackSpacing)
        }
        return null
    }
    private fun ensurePlayheadVisible() {
        val playheadContentX = (currentTimeMs / 1000f) * pixelsPerSecond
        val visibleWidth = width - HEADER_WIDTH
        if (visibleWidth <= 0) return

        val leftBound = scrollXOffset
        val rightBound = scrollXOffset + visibleWidth
        val margin = visibleWidth * 0.2f

        when {
            playheadContentX < leftBound + margin -> {
                scrollXOffset = (playheadContentX - margin).coerceAtLeast(0f)
                invalidate()
            }
            playheadContentX > rightBound - margin -> {
                val maxContentWidth = (project?.getDurationMs() ?: 0L) / 1000f * pixelsPerSecond
                scrollXOffset =
                        (playheadContentX - visibleWidth + margin).coerceIn(
                                0f,
                                maxOf(0f, maxContentWidth - visibleWidth + 200f)
                        )
                invalidate()
            }
        }
    }

    private fun varyColor(base: Int): Int {
        val factor = (0.85f + Math.random() * 0.3f).toFloat()
        return Color.argb(
                Color.alpha(base),
                (Color.red(base) * factor).toInt().coerceAtMost(255),
                (Color.green(base) * factor).toInt().coerceAtMost(255),
                (Color.blue(base) * factor).toInt().coerceAtMost(255)
        )
    }

    private fun snap(value: Long, points: List<Long>): Long {
        val px = value / 1000f * pixelsPerSecond
        val effectiveThreshold = snapThresholdPx

        // 1. High Priority: Snap to Clips/Markers
        for (pMs in points) {
            val pPx = pMs / 1000f * pixelsPerSecond
            if (abs(px - pPx) < effectiveThreshold) {
                if (value != pMs && lastSnapMs != pMs) {
                    snapHaptic()
                    lastSnapMs = pMs
                }
                return pMs
            }
        }

        // 2. Medium Priority: Grid Snapping (0.1s) when zoomed in
        if (pixelsPerSecond > 200) {
            val gridMs = 100L
            val snappedMs = (value + gridMs / 2) / gridMs * gridMs
            val sPx = snappedMs / 1000f * pixelsPerSecond
            if (abs(px - sPx) < snapThresholdPx / 1.5f) {
                if (value != snappedMs && lastSnapMs != snappedMs) {
                    tickHaptic(true)
                    lastSnapMs = snappedMs
                }
                return snappedMs
            }
        }

        lastSnapMs = -1L
        return value.coerceAtLeast(0)
    }

    private fun getSnapPoints(exclude: TimelineClip?): List<Long> {
        val points = mutableListOf(0L, currentTimeMs)
        project?.tracks?.forEach { track ->
            track.clips.forEach { clip ->
                if (clip != exclude) {
                    points.add(clip.startTimeMs)
                    points.add(clip.startTimeMs + clip.durationMs)
                }
            }
        }
        return points
    }
    private fun handleAutoScrollInternal(x: Float) {
        val visibleWidth = width - HEADER_WIDTH
        if (visibleWidth <= 0f) return

        var scrolled = false
        if (x < HEADER_WIDTH + autoScrollEdgePx && x >= HEADER_WIDTH) {
            val oldX = scrollXOffset
            scrollXOffset = (scrollXOffset - autoScrollSpeed).coerceAtLeast(0f)
            if (scrollXOffset != oldX) scrolled = true
        } else if (x > width - autoScrollEdgePx) {
            val maxContentWidth = (project?.getDurationMs() ?: 0L) / 1000f * pixelsPerSecond
            val maxScrollX = (maxContentWidth - (visibleWidth - 200f)).coerceAtLeast(0f)
            val oldX = scrollXOffset
            scrollXOffset = (scrollXOffset + autoScrollSpeed).coerceAtMost(maxScrollX)
            if (scrollXOffset != oldX) scrolled = true
        }

        if (scrolled) {
            if (isScrubbingRuler) {
                // Update time when scrolling while scrubbing
                currentTimeMs =
                        (((scrollXOffset + x - HEADER_WIDTH) / pixelsPerSecond) * 1000)
                                .toLong()
                                .coerceAtLeast(0)
                onScrollListener?.invoke(currentTimeMs)
                tickHaptic() // Feedback during auto-scroll
            } else if (isDragging && selectedClip != null) {
                // Logic for dragging clip auto-scroll handled by onMove update
                // But we still invoke haptic for feedback
                tickHaptic()
            }
            invalidate()
        }
    }

    private fun stopAutoScroll() {
        removeCallbacks(autoScrollRunnable)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val maxContentWidth = (project?.getDurationMs() ?: 0L) / 1000f * pixelsPerSecond
            val visibleWidth = width - HEADER_WIDTH
            val maxScrollX = (maxContentWidth - (visibleWidth - 100f)).coerceAtLeast(0f)

            scrollXOffset = scroller.currX.toFloat().coerceIn(0f, maxScrollX)
            scrollYOffset = scroller.currY.toFloat().coerceIn(0f, maxScrollY)
            invalidate()
            postInvalidateOnAnimation()
        }
    }

    private fun drawTrackIcon(canvas: Canvas, iconRes: Int, rect: RectF) {
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes) ?: return
        val iconSize = 34f
        val left = rect.centerX() - iconSize / 2
        val top = rect.centerY() - iconSize / 2
        drawable.setBounds(
                left.toInt(),
                top.toInt(),
                (left + iconSize).toInt(),
                (top + iconSize).toInt()
        )
        // Premium Cyan accent for icons
        drawable.setTint(Color.parseColor("#00D2D3"))
        drawable.draw(canvas)
    }
}
