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

    private val overlayAlphaPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

            // Apply Opacity to drawing operations
            val alphaVal = (clip.overlayOpacity * 255).toInt().coerceIn(0, 255)
            overlayAlphaPaint.alpha = alphaVal

            when (clip.type) {
                ClipType.IMAGE, ClipType.STICKER, ClipType.GIF -> {
                    val bitmap = getBitmap(clip.filePath)
                    if (bitmap != null) {
                        canvas.drawBitmap(bitmap, null, rect, overlayAlphaPaint)
                    } else {
                        // Placeholder for missing assets
                        canvas.drawRect(rect, handleStrokePaint)
                    }
                }
                ClipType.TEXT -> {
                    drawOverlayText(canvas, clip, rect)
                }
                ClipType.EMOJI -> {
                    drawEmojiOverlay(canvas, clip, rect)
                }
                ClipType.VIDEO -> {
                    val bitmap = getVideoFrame(clip.filePath)
                    if (bitmap != null) {
                        canvas.drawBitmap(bitmap, null, rect, overlayAlphaPaint)
                    } else {
                        // Draw a placeholder for Video PIP
                        boxPaint.style = Paint.Style.FILL
                        boxPaint.alpha = 100
                        canvas.drawRect(rect, boxPaint)
                        boxPaint.style = Paint.Style.STROKE
                        boxPaint.alpha = 255
                        canvas.drawText("VIDEO PIP", rect.centerX(), rect.centerY(), handleStrokePaint)
                    }
                }
                else -> {}
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
        val text = clip.textSettings["text"] ?: clip.textSettings["TEXT"] ?: "TAP TO EDIT"
        val colorHex = clip.textSettings["color"] ?: "#FFFFFF"
        val sizeVal = clip.textSettings["size"]?.toFloatOrNull() ?: 48f
        val isBold = clip.textSettings["bold"] == "true"
        val isItalic = clip.textSettings["italic"] == "true"

        textPaint.textSize = sizeVal * clip.overlayScale * 2f // Adjusted for view density
        textPaint.isFakeBoldText = isBold
        textPaint.textSkewX = if (isItalic) -0.25f else 0f
        
        // 💎 PRO: Draw Shadow & Stroke in Preview to match Export
        textPaint.setShadowLayer(8f, 4f, 4f, Color.BLACK)
        
        // Final position centering
        val fontMetrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2

        // Draw Stroke (Matching FFmpeg Filter)
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 10f
        textPaint.color = Color.BLACK
        textPaint.alpha = (clip.overlayOpacity * 255).toInt().coerceIn(0, 255)
        canvas.drawText(text, rect.centerX(), baseline, textPaint)

        // Draw Fill
        textPaint.style = Paint.Style.FILL
        textPaint.color = Color.parseColor(colorHex)
        textPaint.alpha = (clip.overlayOpacity * 255).toInt().coerceIn(0, 255)
        canvas.drawText(text, rect.centerX(), baseline, textPaint)
        
        textPaint.clearShadowLayer() // Clean up for next draw
    }

    private fun drawEmojiOverlay(canvas: Canvas, clip: TimelineClip, rect: RectF) {
        val emoji = clip.textSettings["emoji"] ?: "🎥"
        textPaint.textSize = rect.height() * 0.8f
        textPaint.style = Paint.Style.FILL
        textPaint.color = Color.BLACK
        textPaint.alpha = (clip.overlayOpacity * 255).toInt().coerceIn(0, 255)
        val fontMetrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2
        canvas.drawText(emoji, rect.centerX(), baseline, textPaint)
    }

    private fun getBitmap(path: String): Bitmap? {
        val cached = bitmapCache.get(path)
        if (cached != null) return cached

        return try {
            val resolvedPath = com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(context, path) ?: path
            val file = File(resolvedPath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(resolvedPath)
                if (bitmap != null) {
                    bitmapCache.put(path, bitmap)
                }
                bitmap
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getVideoFrame(path: String): Bitmap? {
        val cached = bitmapCache.get(path)
        if (cached != null) return cached

        return try {
            val resolvedPath = com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(context, path) ?: path
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(resolvedPath)
            val bitmap = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            if (bitmap != null) {
                bitmapCache.put(path, bitmap)
            }
            bitmap
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

    var onClipSelectedListener: ((TimelineClip?) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // First, check if there's a targetClip and we hit one of its handles or its body
                var target = targetClip
                if (target != null) {
                    activeMode = detectMode(x, y, target)
                    if (activeMode != Mode.NONE) {
                        lastX = x
                        lastY = y
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        return true
                    }
                }

                // If not hit target, search other active overlays (in reverse Z-order, i.e. top-most first)
                for (clip in activeOverlays.asReversed()) {
                    val rect = getClipRect(clip)
                    if (rect.contains(x, y)) {
                        targetClip = clip
                        onClipSelectedListener?.invoke(clip)
                        activeMode = Mode.MOVE
                        lastX = x
                        lastY = y
                        invalidate()
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        return true
                    }
                }

                // If we tapped outside everything, clear selection
                if (targetClip != null) {
                    targetClip = null
                    onClipSelectedListener?.invoke(null)
                    invalidate()
                }
                activeMode = Mode.NONE
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val clip = targetClip ?: return false
                val dx = x - lastX
                val dy = y - lastY
                processMovement(dx, dy, x, y, clip)
                
                // Add subtle haptic ticks during major property changes
                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                   performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }

                lastX = x
                lastY = y
                onUpdate?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
