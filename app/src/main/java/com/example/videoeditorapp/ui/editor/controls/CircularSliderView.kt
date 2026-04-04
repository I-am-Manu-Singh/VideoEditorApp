package com.example.videoeditorapp.ui.editor.controls

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class CircularSliderView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        View(context, attrs, defStyleAttr) {

    private var progress = 0.5f // 0 to 1
    private var minValue = 0f
    private var maxValue = 1f

    private val trackPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1AFFFFFF")
                style = Paint.Style.STROKE
                strokeWidth = 12f
                strokeCap = Paint.Cap.ROUND
            }

    private val progressPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00D2D3")
                style = Paint.Style.STROKE
                strokeWidth = 12f
                strokeCap = Paint.Cap.ROUND
            }

    private val thumbPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(10f, 0f, 0f, Color.parseColor("#80000000"))
            }

    private var onValueChanged: ((Float) -> Unit)? = null

    fun setRange(min: Float, max: Float, current: Float) {
        this.minValue = min
        this.maxValue = max
        this.progress = (current - min) / (max - min)
        invalidate()
    }

    fun setOnValueChangedListener(listener: (Float) -> Unit) {
        this.onValueChanged = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val centerX = width / 2f
        val centerY = height / 2f

        val x = event.x - centerX
        val y = event.y - centerY

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var angle = atan2(y.toDouble(), x.toDouble()).toFloat()
                angle = (angle + PI.toFloat() * 2.5f) % (PI.toFloat() * 2f)

                // Map angle to progress (example 0 to 360)
                // We'll use a gap at the bottom
                val startAngle = 0.2f * PI.toFloat()
                val endAngle = 1.8f * PI.toFloat()

                progress = ((angle - startAngle) / (endAngle - startAngle)).coerceIn(0f, 1f)
                val value = minValue + progress * (maxValue - minValue)
                onValueChanged?.invoke(value)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(centerX, centerY) - 20f

        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        // Draw track
        canvas.drawArc(rect, 135f, 270f, false, trackPaint)

        // Draw progress
        canvas.drawArc(rect, 135f, progress * 270f, false, progressPaint)

        // Draw thumb
        val thumbAngle = (135f + progress * 270f) * PI.toFloat() / 180f
        val thumbX = centerX + radius * cos(thumbAngle)
        val thumbY = centerY + radius * sin(thumbAngle)
        canvas.drawCircle(thumbX, thumbY, 16f, thumbPaint)
    }
}
