package com.example.videoeditorapp.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Random

class ParticleView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        View(context, attrs, defStyleAttr) {

    private val particles = mutableListOf<Particle>()
    private val paint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }
    private val random = Random()
    private var particleColor = Color.parseColor("#00D2D3")

    inner class Particle(
            var x: Float,
            var y: Float,
            var vx: Float,
            var vy: Float,
            var radius: Float,
            var alpha: Int
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        particles.clear()
        for (i in 0 until 60) {
            particles.add(
                    Particle(
                            random.nextFloat() * w,
                            random.nextFloat() * h,
                            (random.nextFloat() - 0.5f) * 1.5f,
                            (random.nextFloat() - 0.5f) * 1.5f,
                            random.nextFloat() * 4f + 1f,
                            random.nextInt(150) + 50
                    )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (p in particles) {
            p.x += p.vx
            p.y += p.vy

            if (p.x < 0) p.x = width.toFloat()
            if (p.x > width) p.x = 0f
            if (p.y < 0) p.y = height.toFloat()
            if (p.y > height) p.y = 0f

            paint.color = particleColor
            paint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.radius, paint)
        }

        invalidate()
    }

    fun setParticleColor(color: Int) {
        particleColor = color
    }
}
