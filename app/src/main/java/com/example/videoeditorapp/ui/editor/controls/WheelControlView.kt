package com.example.videoeditorapp.ui.editor.controls

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

class WheelControlView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        View(context, attrs, defStyleAttr) {

    private var minValue = 0f
    private var maxValue = 1f
    private var currentValue = 0.5f

    private val linePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4DFFFFFF")
                strokeWidth = 2f
            }

    private val accentPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00D2D3")
                strokeWidth = 4f
            }

    private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 24f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.MONOSPACE
            }

    private var onValueChanged: ((Float) -> Unit)? = null

    private val spacing = 40f
    private var scrollXOffset = 0f

    private val gestureDetector =
            GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onScroll(
                                e1: MotionEvent?,
                                e2: MotionEvent,
                                distanceX: Float,
                                distanceY: Float
                        ): Boolean {
                            scrollXOffset += distanceX
                            invalidate()
                            updateValueFromScroll()
                            return true
                        }

                        override fun onFling(
                                e1: MotionEvent?,
                                e2: MotionEvent,
                                velocityX: Float,
                                velocityY: Float
                        ): Boolean {
                            // Implement smooth fling if needed
                            return true
                        }
                    }
            )

    fun setRange(min: Float, max: Float, current: Float) {
        this.minValue = min
        this.maxValue = max
        this.currentValue = current

        // Sync scrollXOffset to current value
        val totalTicks = 100
        val totalPixels = totalTicks * spacing
        val totalRange = maxValue - minValue
        val progress = if (totalRange != 0f) (currentValue - minValue) / totalRange else 0.5f

        // Map 0..1 progress to scroll offset range [-totalPixels/2, totalPixels/2]
        scrollXOffset = (progress * totalPixels) - (totalPixels / 2f)

        invalidate()
    }

    fun setOnValueChangedListener(listener: (Float) -> Unit) {
        this.onValueChanged = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun updateValueFromScroll() {
        val center = width / 2f
        // scrollXOffset 0 means minValue is at center?
        // Let's refine:
        val totalTicks = 100 // Example
        val totalPixels = totalTicks * spacing

        scrollXOffset = scrollXOffset.coerceIn(-totalPixels / 2, totalPixels / 2)

        val progress = (scrollXOffset + totalPixels / 2) / totalPixels
        currentValue = minValue + progress * (maxValue - minValue)
        onValueChanged?.invoke(currentValue)
    }

    override fun onDraw(canvas: Canvas) {
        val center = width / 2f
        val h = height.toFloat()

        // Draw background ticks
        val totalTicks = 100
        val halfTicks = totalTicks / 2

        for (i in -halfTicks..halfTicks) {
            val x = center + (i * spacing) - scrollXOffset
            if (x < 0 || x > width) continue

            val isMajor = i % 5 == 0
            val tickH = if (isMajor) h * 0.6f else h * 0.3f

            canvas.drawLine(x, (h - tickH) / 2, x, (h + tickH) / 2, linePaint)

            if (isMajor) {
                // Potential value labels here
            }
        }

        // Draw Center Indicator
        canvas.drawLine(center, 0f, center, h, accentPaint)
    }
}
