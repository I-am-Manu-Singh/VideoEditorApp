package com.example.videoeditorapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.videoeditorapp.databinding.ActivityAssetDetailBinding
import com.example.videoeditorapp.model.timeline.AssetItem
import com.example.videoeditorapp.model.timeline.RemoteAssetManager
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import kotlinx.coroutines.launch

class AssetDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssetDetailBinding
    private var asset: AssetItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssetDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEditorEdgeToEdge(binding.appBarLayout, null)

        asset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("ASSET", AssetItem::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("ASSET")
        }
        if (asset == null) {
            finish()
            return
        }

        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        asset?.let { item ->
            binding.tvAssetTitle.text = item.title
            binding.tvAssetCategory.text = item.category.uppercase()
            binding.tvAssetType.text = item.type.name.replace("_", " ")
                .lowercase().replaceFirstChar { it.uppercase() }

            // Thumbnail setup
            item.thumbnailResId?.let { resId ->
                binding.ivAssetPreview.setImageResource(resId)
            } ?: run {
                binding.ivAssetPreview.setImageResource(R.drawable.temp_cinematic)
            }

            // PRO Tag Visibility
            binding.tvResolutionTag.visibility = if (item.isPremium) View.VISIBLE else View.GONE
            binding.tvResolutionTag.text = "PRO"

            updateButtonState()
        }
    }

    private fun updateButtonState() {
        asset?.let { item ->
            if (item.isDownloaded) {
                binding.btnDownloadUse.text = "USE ASSET"
                binding.btnDownloadUse.setBackgroundColor(resources.getColor(R.color.brand_accent, theme))
                binding.btnDownloadUse.setTextColor(resources.getColor(R.color.bg_deep_black, theme))
            } else {
                binding.btnDownloadUse.text = if (item.isPremium) "UNLOCK & DOWNLOAD" else "DOWNLOAD"
                binding.btnDownloadUse.setBackgroundColor(resources.getColor(R.color.bg_dark_surface, theme))
                binding.btnDownloadUse.setTextColor(resources.getColor(R.color.text_primary, theme))
            }
        }
    }

    private fun setupListeners() {
        // Back Button in the new Glass Container
        binding.btnBackContainer.setOnClickListener { finish() }

        binding.btnDownloadUse.setOnClickListener {
            val item = asset ?: return@setOnClickListener
            if (item.isDownloaded) {
                val resultIntent = Intent().apply {
                    putExtra("ASSET_PATH", item.localPath)
                    putExtra("ASSET_TYPE", item.type.name)
                    putExtra("ASSET_CATEGORY", item.category)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                if (item.isPremium) {
                    val prefs = getSharedPreferences("VideoEditorPrefs", android.content.Context.MODE_PRIVATE)
                    val isPro = prefs.getBoolean("IS_PRO", false)
                    if (isPro) {
                        downloadAsset(item)
                    } else {
                        startActivity(Intent(this, UpgradeActivity::class.java))
                    }
                } else {
                    downloadAsset(item)
                }
            }
        }
    }

    private fun downloadAsset(item: AssetItem) {
        lifecycleScope.launch {
            binding.btnDownloadUse.isEnabled = false
            binding.btnDownloadUse.text = "DOWNLOADING..."

            val path = RemoteAssetManager.downloadAsset(this@AssetDetailActivity, item.url)
            if (path != null) {
                item.isDownloaded = true
                item.localPath = path
                updateButtonState()
                Toast.makeText(this@AssetDetailActivity, "Download Complete!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@AssetDetailActivity, "Download Failed", Toast.LENGTH_SHORT).show()
                updateButtonState()
            }
            binding.btnDownloadUse.isEnabled = true
        }
    }
}