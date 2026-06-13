package com.example.videoeditorapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.example.videoeditorapp.adapter.IntroAdapter
import com.example.videoeditorapp.databinding.ActivityIntroBinding
import com.example.videoeditorapp.model.IntroSlide
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import com.google.android.material.tabs.TabLayoutMediator

class IntroActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIntroBinding
    private val slides =
            listOf(
                    IntroSlide(
                            "Studio V4 Masterpiece",
                            "Experience the next generation of mobile editing. Multi-track timeline, keyframe animations, and pro-grade precision.",
                            imageResId = R.drawable.ic_intro_create
                    ),
                    IntroSlide(
                            "Creative Hub",
                            "Unlimited access to premium assets. Download transitions, filters, stickers, and sound effects instantly.",
                            imageResId =
                                    R.drawable
                                            .ic_cloud_download // Replaced missing lottie logic for
                            // simplicity/stability
                            ),
                    IntroSlide(
                            "Pro Tools Suite",
                            "Split, trim, speed control, chrome key, and dynamic overlays. Everything you need in your pocket.",
                            imageResId = R.drawable.ic_content_cut
                    ),
                    IntroSlide(
                            "4K Cinema Export",
                            "Export in stunning 4K 60fps. Zero quality loss. Perfect for YouTube, Instagram, and TikTok.",
                            imageResId = R.drawable.ic_intro_share
                    ),
                    IntroSlide(
                            "Ready to Create?",
                            "Join millions of creators. Dive into your first project now.",
                            R.drawable.app_logo
                    )
            )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupEdgeToEdge()

        val adapter = IntroAdapter(slides)
        binding.introViewPager.adapter = adapter

        TabLayoutMediator(binding.tabIndicator, binding.introViewPager) { _, _ -> }.attach()

        binding.introViewPager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        if (position == slides.size - 1) {
                            binding.btnNext.text = "Let's Go!"
                        } else {
                            binding.btnNext.text = "Next"
                        }
                        animatePage(position)
                    }
                }
        )

        binding.btnNext.setOnClickListener {
            if (binding.introViewPager.currentItem < slides.size - 1) {
                binding.introViewPager.currentItem += 1
            } else {
                completeIntro()
            }
        }

        binding.btnSkip.setOnClickListener { completeIntro() }
    }

    private fun animatePage(position: Int) {
        val viewPager = binding.introViewPager
        val currentView =
                (viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                        ?.findViewHolderForAdapterPosition(position)
                        ?.itemView
                        ?: return

        val title = currentView.findViewById<android.view.View>(R.id.tvTitle)
        val desc = currentView.findViewById<android.view.View>(R.id.tvDescription)
        val icon = currentView.findViewById<android.view.View>(R.id.imgSlide)

        title.alpha = 0f
        title.translationY = 40f
        desc.alpha = 0f
        desc.translationY = 30f
        icon.alpha = 0f
        icon.scaleX = 0.8f
        icon.scaleY = 0.8f

        title.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(100).start()
        desc.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(200).start()
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600).start()
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(null, binding.bottomControls)
    }

    private fun completeIntro() {
        val prefs = getSharedPreferences("VideoEditorPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("FIRST_RUN", false).apply()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
