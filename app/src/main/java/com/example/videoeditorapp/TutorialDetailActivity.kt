package com.example.videoeditorapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivityTutorialDetailBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge

class TutorialDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()

        val title = intent.getStringExtra("TUTORIAL_TITLE") ?: "Tutorial"
        val tag = intent.getStringExtra("TUTORIAL_TAG") ?: "GUIDE"

        binding.tvTutorialTitle.setText(title)
        binding.chipLevel.setText(tag)

        binding.tvTutorialContent.setText(getTutorialContent(title))

        binding.btnBackContainer.setOnClickListener { finish() }

        binding.btnWatchVideo.setOnClickListener {
            Toast.makeText(this, "Playing Video Guide: $title", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }

    private fun getTutorialContent(title: String): String {
        return when {
            title.contains("Trim") ->
                    "1. Select the clip on the timeline.\n2. Move the playhead to the split point.\n3. Tap the Scissors icon to split.\n4. Drag the handles to trim."
            title.contains("Music") ->
                    "1. Tap 'Add Media' > 'Audio'.\n2. Browse your library or the Asset Store.\n3. Tap the + icon to add to timeline.\n4. Adjust volume and fade in/out."
            title.contains("Chroma") ->
                    "1. Add a video with a green screen.\n2. Tap FX > Chroma Key.\n3. Use the picker to select green color.\n4. Adjust Intensity to remove background."
            else ->
                    "Learn how to master this feature with our comprehensive guide. Follow the steps below to achieve professional results in your edits."
        }
    }
}
