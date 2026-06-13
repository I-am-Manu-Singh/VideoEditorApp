package com.example.videoeditorapp

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivityProfileBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
  import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private val prefs by lazy { getSharedPreferences("merchant_profile", Context.MODE_PRIVATE) }


override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding = ActivityProfileBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupEditorEdgeToEdge(null, null)

    ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->

        val topInset =
            insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
            ).top

        view.setPadding(
            view.paddingLeft,
            topInset,
            view.paddingRight,
            view.paddingBottom
        )

        insets
    }

    setupToolbar()
    loadProfile()
    setupListeners()
}

    private fun setupToolbar() {
        binding.btnBackContainer.setOnClickListener { finish() }
    }

    private fun loadProfile() {
        val shopName = prefs.getString("shop_name", "UNSET_SHOP_ID")
        binding.etShopName.setText(shopName)
        binding.tvShopNameDisplay.text = shopName
        
        binding.etCategory.setText(prefs.getString("category", ""))
        binding.etInstagram.setText(prefs.getString("instagram", ""))
        binding.etTikTok.setText(prefs.getString("tiktok", ""))
    }

    private fun setupListeners() {
        binding.btnSaveProfile.setOnClickListener {
            val name = binding.etShopName.text.toString()
            prefs.edit().apply {
                putString("shop_name", name)
                putString("category", binding.etCategory.text.toString())
                putString("instagram", binding.etInstagram.text.toString())
                putString("tiktok", binding.etTikTok.text.toString())
                apply()
            }
            binding.tvShopNameDisplay.text = name
            Toast.makeText(this, "Brand Identity Synchronized!", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnChangeLogo.setOnClickListener {
            // Logo picker logic would go here
            Toast.makeText(this, "Logo Picker Opening...", Toast.LENGTH_SHORT).show()
        }
    }
}
