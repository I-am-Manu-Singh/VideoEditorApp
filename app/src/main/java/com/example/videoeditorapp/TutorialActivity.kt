package com.example.videoeditorapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.videoeditorapp.databinding.ActivityTutorialBinding
import com.example.videoeditorapp.data.TutorialRepository
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge

class TutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply theme-aware background
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
        if (typedValue.resourceId != 0) {
            binding.root.setBackgroundColor(ContextCompat.getColor(this, typedValue.resourceId))
        } else {
            binding.root.setBackgroundColor(typedValue.data)
        }

        setupEdgeToEdge()
        setupArticleList()
        binding.btnBackContainer?.setOnClickListener { finish() }
        
        binding.btnGotIt?.setOnClickListener { finish() }

        binding.btnViewAllArticles?.setOnClickListener {
            android.widget.Toast.makeText(
                            this,
                            "Opening Studio Academy...",
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()
        }
    }

    private fun setupArticleList() {
        val articles =
            TutorialRepository.getArticles()

        binding.rvMasterArticles?.layoutManager =
                androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvMasterArticles?.adapter =
                com.example.videoeditorapp.model.TutorialAdapter(articles) { item ->
                    val intent =
                            android.content.Intent(this, TutorialDetailActivity::class.java).apply {
                                putExtra("TUTORIAL_TITLE", item.title)
                                putExtra("TUTORIAL_TAG", item.tag)
                            }
                    startActivity(intent)
                }
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }
}
