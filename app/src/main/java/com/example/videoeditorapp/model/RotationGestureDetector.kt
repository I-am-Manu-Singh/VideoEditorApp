package com.example.videoeditorapp.model

import android.view.MotionEvent
import kotlin.math.atan2

class RotationGestureDetector(
    private val onRotate: (Float) -> Unit
) {
    private var prevAngle = 0f
    private var active = false

    fun onTouch(event: MotionEvent) {
        if (event.pointerCount < 2) {
            active = false
            return
        }

        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

        if (!active) {
            prevAngle = angle
            active = true
            return
        }

        val delta = angle - prevAngle
        prevAngle = angle
        onRotate(delta)
    }
}