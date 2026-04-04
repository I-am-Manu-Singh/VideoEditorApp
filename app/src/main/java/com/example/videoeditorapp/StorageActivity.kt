package com.example.videoeditorapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditorapp.databinding.ActivityStorageBinding
import com.example.videoeditorapp.databinding.ItemStorageCategoryBinding
import com.example.videoeditorapp.utils.StorageManager
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import java.io.File

class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupEdgeToEdge()
        refreshStorageData()
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }

    private fun setupToolbar() {
        binding.btnBackContainer.setOnClickListener {
            finish() // Or onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun refreshStorageData() {
        val cacheSize = StorageManager.getCacheSize(this)
        val tempExportSize = StorageManager.getTempExportsSize(this)
        val downloadSize = StorageManager.getDownloadsSize(this)
        val mediaSize = StorageManager.getImportedMediaSize(this)
        val projectsSize = StorageManager.getProjectsSize(this)
        val totalAppUsage = cacheSize + tempExportSize + downloadSize + projectsSize + mediaSize

        // Update Total Card
        binding.tvTotalSize.text = StorageManager.formatSize(totalAppUsage)

        // Calculate device free space
        val freeSpace = File(filesDir.absolutePath).freeSpace
        binding.tvFreeSpace.text = "${StorageManager.formatSize(freeSpace)} Free on Device"

        // Setup categories
        setupCategoryItem(
                binding.itemProjects,
                "Projects",
                "Thumbnails & Metadata",
                projectsSize,
                R.drawable.ic_folder
        ) {
            startActivity(
                    android.content.Intent(this, StorageDetailActivity::class.java).apply {
                        putExtra("EXTRA_CATEGORY", "PROJECTS")
                    }
            )
        }
        // Disable clear button for projects for safety
        binding.itemProjects.btnClear.visibility = View.GONE

        setupCategoryItem(
                binding.itemExports,
                "Exports",
                "Temporary rendered files",
                tempExportSize,
                R.drawable.ic_export
        ) {
            startActivity(
                    android.content.Intent(this, StorageDetailActivity::class.java).apply {
                        putExtra("EXTRA_CATEGORY", "EXPORTS")
                    }
            )
        }
        binding.itemExports.btnClear.visibility = View.GONE

        setupCategoryItem(
                binding.itemDownloads,
                "Downloads",
                "Marketplace assets",
                downloadSize,
                R.drawable.ic_cloud_download
        ) {
            startActivity(
                    android.content.Intent(this, StorageDetailActivity::class.java).apply {
                        putExtra("EXTRA_CATEGORY", "DOWNLOADS")
                    }
            )
        }
        binding.itemDownloads.btnClear.visibility = View.GONE

        setupCategoryItem(
                binding.itemMedia,
                "Imported Media",
                "Clips used in projects",
                mediaSize,
                R.drawable.ic_speed
        ) {
            startActivity(
                    android.content.Intent(this, StorageDetailActivity::class.java).apply {
                        putExtra("EXTRA_CATEGORY", "MEDIA")
                    }
            )
        }

        // Allow clicking the row to see details
        binding.itemMedia.root.setOnClickListener {
            startActivity(
                    android.content.Intent(this, StorageDetailActivity::class.java).apply {
                        putExtra("EXTRA_CATEGORY", "MEDIA")
                    }
            )
        }
        binding.itemMedia.btnClear.visibility = View.GONE

        setupCategoryItem(
                binding.itemCache,
                "Cache",
                "Temporary app data",
                cacheSize,
                R.drawable.ic_delete
        ) {
            showClearDialog(
                    "Clear Cache?",
                    "This will delete temporary cache files to free up space. This is safe to do."
            ) {
                StorageManager.clearCache(this)
                refreshStorageData()
            }
        }

        // Update Progress Bar (Visual representation relative to some cap, e.g. 1GB or just visual
        // fill)
        // Let's make it relative to 1GB for visual effect
        val progress = ((totalAppUsage / (1024.0 * 1024.0 * 1024.0)) * 100).toInt().coerceIn(0, 100)
        binding.progressBarStorage.progress = if (totalAppUsage > 0 && progress < 5) 5 else progress
    }

    private fun setupCategoryItem(
            itemBinding:
                    ItemStorageCategoryBinding, // This requires view binding for included layout...
            // Note: ViewBinding for included layouts works if they have an ID.
            // ActivityStorageBinding will have fields itemProjects, itemExports etc. which are of
            // type ItemStorageCategoryBinding
            title: String,
            subtitle: String,
            size: Long,
            iconRes: Int,
            onClear: () -> Unit
    ) {
        itemBinding.title.text = title
        itemBinding.subtitle.text = subtitle
        itemBinding.size.text = StorageManager.formatSize(size)
        itemBinding.icon.setImageResource(iconRes)

        itemBinding.btnClear.setOnClickListener { onClear() }
        itemBinding.btnClear.isEnabled = size > 0
        itemBinding.root.alpha = if (size > 0) 1.0f else 0.6f
    }

    private fun showClearDialog(title: String, message: String, onConfirm: () -> Unit) {
        val dlg = com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(layoutInflater)

        dlg.tvTitle.text = title
        dlg.tvMessage.text = message

        dlg.btnPrimary.text = "Clear"
        dlg.btnSecondary.text = "Cancel"

        // Hide Icon or set generic
        dlg.ivDialogIcon.visibility = View.GONE

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(dlg.root).create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dlg.btnPrimary.setOnClickListener {
            onConfirm()
            dialog.dismiss()
        }

        dlg.btnSecondary.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
