package com.example.videoeditorapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.videoeditorapp.utils.AppDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.example.videoeditorapp.databinding.DialogFaqBinding
import com.example.videoeditorapp.databinding.ItemFaqBinding
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.videoeditorapp.databinding.ActivityHelpBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import androidx.core.net.toUri

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply theme-aware background
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
        if (typedValue.resourceId != 0) {
            binding.root.setBackgroundColor(ContextCompat.getColor(this, typedValue.resourceId))
        } else {
            binding.root.setBackgroundColor(typedValue.data)
        }

        setupEditorEdgeToEdge(binding.appBarLayout, null)

        binding.btnBackContainer.setOnClickListener {
            finish()
        }
        setupClicks()
    }

    private fun setupClicks() {
        binding.btnContactEmail.setOnClickListener {
            sendEmail("Support Request - Studio Pro", "Describe your request here...")
        }

        binding.btnReportIssue.setOnClickListener {
            sendEmail(
                    "Bug Report - Studio Pro v4.0.1",
                    "Steps to reproduce:\n1.\n2.\n3.\n\nExpected behavior:\n\nDevice: ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})"
            )
        }
        binding.btnFaq.setOnClickListener { showFaqDialog() }
    }

    private fun sendEmail(subject: String, body: String) {
        val intent =
                Intent(Intent.ACTION_SENDTO).apply {
                    data = "mailto:".toUri()
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("support@studiopro.com"))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
        try {
            startActivity(Intent.createChooser(intent, "Send Email"))
        } catch (e: Exception) {
            Toast.makeText(this, "No email client found", Toast.LENGTH_SHORT).show()
        }
    }
private fun showFaqDialog() {

    val faqItems =
        listOf(
            "How do I remove the watermark?" to
                "Upgrade to Studio Pro Premium to remove watermarks and unlock 4K export.",

            "Can I add my own music?" to
                "Yes! Tap the Audio tab in the editor, then select My Music to import mp3 files.",

            "Why is export slow?" to
                "Export speed depends on your device and resolution. Try exporting in 1080p instead of 4K.",

            "How do I split a clip?" to
                "Select the clip on the timeline, position the playhead, and tap the Split icon.",

            "Where are my projects saved?" to
                "Projects are saved locally on your device.",

            "App crashes on export?" to
                "Ensure enough free storage and restart the app. If the issue persists, report it."
        )

    val dialogBinding =
        DialogFaqBinding.inflate(layoutInflater)

    val dialog =
        AppDialog.showCustomView(
            this,
            dialogBinding.root
        )

    faqItems.forEach { (question, answer) ->

        val itemBinding =
            ItemFaqBinding.inflate(
                layoutInflater,
                dialogBinding.llFaqContainer,
                false
            )

        itemBinding.tvFaqQuestion.text = question
        itemBinding.tvFaqAnswer.text = answer

        dialogBinding.llFaqContainer.addView(itemBinding.root)
    }

    dialogBinding.btnFaqClose.setOnClickListener {
        dialog.dismiss()
    }
}
}
