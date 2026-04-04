package com.example.videoeditorapp

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivitySplashBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val SPLASH_DURATION = 2500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupEdgeToEdge()

        // Initial states
        binding.lottieLogo.alpha = 0f
        binding.tvAppTitle.alpha = 0f
        binding.tvAppTitle.translationY = 40f

        // Logo fade-in
        val logoFade = ObjectAnimator.ofFloat(binding.lottieLogo, View.ALPHA, 0f, 1f)

        // Text slide + fade
        val textFade = ObjectAnimator.ofFloat(binding.tvAppTitle, View.ALPHA, 0f, 1f)
        val textSlide = ObjectAnimator.ofFloat(binding.tvAppTitle, View.TRANSLATION_Y, 40f, 0f)

        AnimatorSet().apply {
            playTogether(logoFade, textFade, textSlide)
            duration = 1200
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        Handler(Looper.getMainLooper()).postDelayed({ navigateNext() }, SPLASH_DURATION)
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(null, null)
    }

    private fun navigateNext() {
        val prefs = getSharedPreferences("VideoEditorPrefs", Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("FIRST_RUN", true)

        startActivity(
                Intent(
                        this,
                        if (isFirstRun) IntroActivity::class.java else MainActivity::class.java
                )
        )
        finish()
    }
}
