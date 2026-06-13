package com.example.videoeditorapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivityExportSuccessBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge

class ExportSuccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportSuccessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportSuccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEditorEdgeToEdge(null, null)
        
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        binding.shareTikTok.tvLabel.text = "TIKTOK"
        binding.shareInstagram.tvLabel.text = "INSTAGRAM"
        binding.shareWhatsApp.tvLabel.text = "WHATSAPP"
        binding.shareYouTube.tvLabel.text = "YOUTUBE"
        
        // Mocking metadata
        val size = intent.getStringExtra("FILE_SIZE") ?: "34.2 MB"
        val res = intent.getStringExtra("RESOLUTION") ?: "1080p"
        binding.tvVideoInfo.text = "$res • $size"
    }

    private fun setupListeners() {
        binding.btnDone.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }
    }
}
