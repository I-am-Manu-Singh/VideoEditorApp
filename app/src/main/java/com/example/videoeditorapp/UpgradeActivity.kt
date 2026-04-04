package com.example.videoeditorapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.videoeditorapp.databinding.ActivityUpgradeBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge

class UpgradeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpgradeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpgradeBinding.inflate(layoutInflater)
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
        binding.btnBackContainer.setOnClickListener { finish() }

        binding.btnUpgradePro.setOnClickListener { simulatePurchase("Studio Pro Master License") }

        binding.btnUpgradeStudio.setOnClickListener { simulatePurchase("Studio V4 Ultimate Suite") }

        binding.tvRestorePurchase.setOnClickListener {
            Toast.makeText(this, "Checking license registry...", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper())
                    .postDelayed(
                            {
                                Toast.makeText(
                                                this,
                                                "License Restored Successfully",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            },
                            1500
                    )
        }
    }

    private fun simulatePurchase(licenseName: String) {
        Toast.makeText(this, "Contacting Payment Gateway...", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper())
                .postDelayed(
                        {
                            val prefs =
                                    getSharedPreferences(
                                            "VideoEditorPrefs",
                                            android.content.Context.MODE_PRIVATE
                                    )
                            prefs.edit().putBoolean("IS_PRO", true).apply()

                            Toast.makeText(
                                            this,
                                            "Payment Successful: $licenseName Activated",
                                            Toast.LENGTH_LONG
                                    )
                                    .show()

                            // Visual feedback
                            binding.btnUpgradeProAction.setOnClickListener {
                                simulatePurchase("Studio Pro Master License")
                                binding.btnUpgradeProAction.text = "PRO ACTIVATED"
                            }
                            binding.btnUpgradePro.isEnabled = false
                            binding.btnUpgradeStudio.isEnabled = false
                        },
                        2000
                )
    }
    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }
}
