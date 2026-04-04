package com.example.videoeditorapp.ui.preview

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import com.example.videoeditorapp.model.timeline.ClipType
import com.example.videoeditorapp.model.timeline.TimelineClip
import java.io.File
import kotlin.math.atan2
import kotlin.math.sqrt

class OverlayManipulationView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        View(context, attrs, defStyleAttr) {

    private var targetClip: TimelineClip? = null
    private var onUpdate: (() -> Unit)? = null

    private val boxPaint =
            Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            }

    private val handlePaint =
            Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

    private val handleStrokePaint =
            Paint().apply {
                color = Color.parseColor("#6C5CE7") // brand_primary
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }

    private val textPaint =
            Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

    private val bitmapCache = LruCache<String, Bitmap>(20) // Simple cache for overlay images
    private val handleSize = 25f
    private var activeMode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    enum class Mode {
        NONE,
        MOVE,
        SCALE_TL,
        SCALE_TR,
        SCALE_BL,
        SCALE_BR,
        ROTATE
    }

    fun setTarget(clip: TimelineClip?, onUpdate: () -> Unit) {
        this.targetClip = clip
        this.onUpdate = onUpdate
        invalidate()
    }

    private var activeOverlays: List<TimelineClip> = emptyList()

    fun updatePreview(
            currentTimeMs: Long,
            tracks: List<com.example.videoeditorapp.model.timeline.TimelineTrack>
    ) {
        val visible = mutableListOf<TimelineClip>()
        tracks
                .filter { it.type == com.example.videoeditorapp.model.timeline.TrackType.OVERLAY }
                .forEach { track ->
                    track.clips.forEach { clip ->
                        if (currentTimeMs >= clip.startTimeMs &&
                                        currentTimeMs < clip.startTimeMs + clip.durationMs
                        ) {
                            visible.add(clip)
                        }
                    }
                }
        activeOverlays = visible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 1. Draw ALL active overlays (Z-order presumed by list order)
        activeOverlays.forEach { clip ->
            val rect = getClipRect(clip)
            canvas.save()
            canvas.rotate(clip.overlayRotation, rect.centerX(), rect.centerY())

            // Apply Opacity
            // (Not fully implemented in clip model yet? Assuming paint alpha if we had it, fallback
            // for now)

            when (clip.type) {
                ClipType.IMAGE -> {
                    val bitmap = getBitmap(clip.filePath)
                    if (bitmap != null) {
                        canvas.drawBitmap(bitmap, null, rect, null)
                    }
                }
                ClipType.TEXT -> {
                    drawOverlayText(canvas, clip, rect)
                }
                ClipType.VIDEO -> {
                    /* PIP placeholder if needed */
                }
                else -> {} // Sticker/Gif treated as Image usually
            }
            canvas.restore()

            // 2. Draw Handles ONLY for target clip
            if (clip.id == targetClip?.id) {
                canvas.save()
                canvas.rotate(clip.overlayRotation, rect.centerX(), rect.centerY())

                // Draw Selection Box
                canvas.drawRect(rect, boxPaint)

                // Draw Handles
                drawHandle(canvas, rect.left, rect.top) // TL
                drawHandle(canvas, rect.right, rect.top) // TR
                drawHandle(canvas, rect.left, rect.bottom) // BL
                drawHandle(canvas, rect.right, rect.bottom) // BR

                // Rotation handle
                drawHandle(canvas, rect.centerX(), rect.top - 40f)
                canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.top - 40f, boxPaint)
                canvas.restore()
            }
        }
    }

    private fun drawOverlayText(canvas: Canvas, clip: TimelineClip, rect: RectF) {
        val text = clip.textSettings["text"] ?: "TAP TO EDIT"
        val colorHex = clip.textSettings["color"] ?: "#FFFFFF"
        val sizeVal = clip.textSettings["size"]?.toFloatOrNull() ?: 24f
        val isBold = clip.textSettings["bold"] == "true"
        val isItalic = clip.textSettings["italic"] == "true"

        textPaint.color = Color.parseColor(colorHex)
        textPaint.textSize = sizeVal * clip.overlayScale * 5f // Scale based on view density
        textPaint.isFakeBoldText = isBold
        textPaint.textSkewX = if (isItalic) -0.25f else 0f

        // Center text in rect
        val fontMetrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2
        canvas.drawText(text, rect.centerX(), baseline, textPaint)
    }

    private fun getBitmap(path: String): Bitmap? {
        val cached = bitmapCache.get(path)
        if (cached != null) return cached

        return try {
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    bitmapCache.put(path, bitmap)
                }
                bitmap
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleSize, handlePaint)
        canvas.drawCircle(x, y, handleSize, handleStrokePaint)
    }

    private fun getClipRect(clip: TimelineClip): RectF {
        val w = width * 0.4f * clip.overlayScale
        val h = height * 0.4f * clip.overlayScale
        val cx = width * clip.overlayX
        val cy = height * clip.overlayY
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val clip = targetClip ?: return false
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeMode = detectMode(x, y, clip)
                lastX = x
                lastY = y
                return activeMode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                processMovement(dx, dy, x, y, clip)
                lastX = x
                lastY = y
                onUpdate?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                activeMode = Mode.NONE
            }
        }
        return true
    }

    private fun detectMode(x: Float, y: Float, clip: TimelineClip): Mode {
        val rect = getClipRect(clip)
        // Check handles first (simplified)
        if (dist(x, y, rect.left, rect.top) < handleSize * 2) return Mode.SCALE_TL
        if (dist(x, y, rect.right, rect.top) < handleSize * 2) return Mode.SCALE_TR
        if (dist(x, y, rect.left, rect.bottom) < handleSize * 2) return Mode.SCALE_BL
        if (dist(x, y, rect.right, rect.bottom) < handleSize * 2) return Mode.SCALE_BR
        if (dist(x, y, rect.centerX(), rect.top - 40f) < handleSize * 2) return Mode.ROTATE

        if (rect.contains(x, y)) return Mode.MOVE
        return Mode.NONE
    }

    private fun processMovement(dx: Float, dy: Float, x: Float, y: Float, clip: TimelineClip) {
        when (activeMode) {
            Mode.MOVE -> {
                clip.overlayX += dx / width
                clip.overlayY += dy / height
            }
            Mode.SCALE_TL, Mode.SCALE_TR, Mode.SCALE_BL, Mode.SCALE_BR -> {
                // Simplified scaling
                val rect = getClipRect(clip)
                val newDist = dist(x, y, rect.centerX(), rect.centerY())
                val oldDist = dist(lastX, lastY, rect.centerX(), rect.centerY())
                clip.overlayScale *= (newDist / oldDist).coerceIn(0.5f, 2.0f)
            }
            Mode.ROTATE -> {
                val rect = getClipRect(clip)
                val angle = atan2(y - rect.centerY(), x - rect.centerX())
                clip.overlayRotation = Math.toDegrees(angle.toDouble()).toFloat() + 90f
            }
            else -> {}
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
            sqrt(((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)).toDouble()).toFloat()
}
