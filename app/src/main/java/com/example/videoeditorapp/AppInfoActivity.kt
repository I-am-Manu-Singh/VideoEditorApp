package com.example.videoeditorapp

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivityAppInfoBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import androidx.core.net.toUri

class AppInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppInfoBinding

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply theme-aware background
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
        if (typedValue.resourceId != 0) {
            binding.root.setBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(this, typedValue.resourceId)
            )
        } else {
            binding.root.setBackgroundColor(typedValue.data)
        }

        setupEdgeToEdge()

        binding.btnBackContainer.setOnClickListener {
            finish()
        }

        // Set dynamic version name
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "v${pInfo.versionName} (Pro)"
        } catch (e: Exception) {
            binding.tvVersion.text = "v1.0.0 (Pro)"
        }

        binding.btnRate.setOnClickListener {
            try {
                startActivity(
                        android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                            "market://details?id=$packageName".toUri()
                        )
                )
            } catch (e: Exception) {
                startActivity(
                        android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                            "https://play.google.com/store/apps/details?id=$packageName".toUri()
                        )
                )
            }
        }

        binding.btnShare.setOnClickListener {
            val sendIntent =
                    android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(
                                android.content.Intent.EXTRA_TEXT,
                                "Check out Studio V4 - The best free 4K video editor! Download now: https://play.google.com/store/apps/details?id=$packageName"
                        )
                        type = "text/plain"
                    }
            val shareIntent = android.content.Intent.createChooser(sendIntent, "Share too...")
            startActivity(shareIntent)
        }

        binding.btnLicenses.setOnClickListener {
            android.widget.Toast.makeText(
                            this,
                            "Open Source Licenses coming soon",
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()
        }
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }
}
