package com.example.videoeditorapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivityExportLabBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge

class ExportLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportLabBinding

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.getIntExtra("PROGRESS", 0) ?: 0
            val log = intent?.getStringExtra("LOG")
            val status = intent?.getStringExtra("STATUS") ?: "ESTIMATING_TIME..."
            val isFinished = intent?.getBooleanExtra("FINISHED", false) ?: false

            updateProgress(progress, status)
            log?.let { addLog(it) }

            if (isFinished) {
                binding.tvProgressPercent.text = "100%"
                binding.btnCancelExport.text = "NEXT"
                binding.btnCancelExport.setTextColor(android.graphics.Color.parseColor("#00D2D3"))
                
                // Launch Success Screen
                val successIntent = Intent(this@ExportLabActivity, ExportSuccessActivity::class.java)
                startActivity(successIntent)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEditorEdgeToEdge(null, null)
        
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            progressReceiver,
            android.content.IntentFilter("ACTION_EXPORT_PROGRESS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        binding.btnCancelExport.setOnClickListener {
            if (binding.btnCancelExport.text.toString().contains("OPEN")) {
                // Logic to open the exported file
                finish()
            } else {
                // Logic to stop service
                finish()
            }
        }
    }

    private fun updateProgress(progress: Int, statusText: String) {
        binding.progressCircular.progress = progress
        binding.tvProgressPercent.text = "$progress%"
        binding.tvTimeRemaining.text = statusText
    }

    private fun addLog(message: String) {
        binding.tvEngineLogs.append("\n[ENGINE] $message")
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(progressReceiver)
    }
}
