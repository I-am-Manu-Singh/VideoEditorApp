package com.example.videoeditorapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.databinding.ActivityStorageDetailBinding
import com.example.videoeditorapp.utils.StorageManager
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageDetailBinding
    private lateinit var adapter: StorageFileAdapter
    private var currentCategory: String = "MEDIA"
    private var allFiles: List<FileItem> = emptyList()
    private var currentTypeFilter: String = "ALL"
    private var currentSortMode: SortMode = SortMode.DATE_DESC

    enum class SortMode {
        SIZE_ASC,
        SIZE_DESC,
        DATE_DESC,
        DATE_ASC
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentCategory = intent.getStringExtra("EXTRA_CATEGORY") ?: "MEDIA"

        setupToolbar()
        setupEdgeToEdge()
        setupRecyclerView()
        setupSearch()
        setupFilters()
        setupSelectionActions()
        loadFiles()
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }

    private fun setupToolbar() {
        binding.btnBackContainer.setOnClickListener { finish() }
        binding.tvToolbarTitle.text =
                when (currentCategory) {
                    "PROJECTS" -> "Project Data"
                    "EXPORTS" -> "Saved Exports"
                    "DOWNLOADS" -> "Asset Downloads"
                    else -> "Imported Media"
                }
    }

    private fun setupRecyclerView() {
        adapter =
                StorageFileAdapter(
                        onDelete = { fileItem -> showDeleteDialog(listOf(fileItem)) },
                        onSelectionChanged = { count -> updateSelectionUI(count) }
                )
        binding.rvMediaFiles.layoutManager = LinearLayoutManager(this)
        binding.rvMediaFiles.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) {}
                    override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {
                        filterFiles(s?.toString() ?: "")
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )
    }

    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedChangeListener { group, checkedId ->
            currentTypeFilter =
                    when (checkedId) {
                        R.id.chipVideos -> "VIDEO"
                        R.id.chipAudios -> "AUDIO"
                        R.id.chipImages -> "IMAGE"
                        R.id.chipGifs -> "GIF"
                        R.id.chipStickers -> "STICKER"
                        else -> "ALL"
                    }
            applyFiltersAndSort()
        }

        binding.btnSort.setOnClickListener { showSortMenu() }
    }

    private fun showSortMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(this, binding.btnSort)
        popup.menu.add("Size: Low to High").setOnMenuItemClickListener {
            currentSortMode = SortMode.SIZE_ASC
            applyFiltersAndSort()
            true
        }
        popup.menu.add("Size: High to Low").setOnMenuItemClickListener {
            currentSortMode = SortMode.SIZE_DESC
            applyFiltersAndSort()
            true
        }
        popup.menu.add("Newest First").setOnMenuItemClickListener {
            currentSortMode = SortMode.DATE_DESC
            applyFiltersAndSort()
            true
        }
        popup.menu.add("Oldest First").setOnMenuItemClickListener {
            currentSortMode = SortMode.DATE_ASC
            applyFiltersAndSort()
            true
        }
        popup.show()
    }

    private fun setupSelectionActions() {
        binding.btnCancelSelection.setOnClickListener { adapter.clearSelection() }
        binding.btnDeleteSelected.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                showDeleteDialog(selected)
            }
        }
    }

    private fun updateSelectionUI(count: Int) {
        if (count > 0) {
            binding.selectionToolbar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = "$count ITEMS SELECTED"
            binding.searchCard.animate().alpha(0f).setDuration(200).withEndAction {
                binding.searchCard.visibility = View.GONE
            }
        } else {
            binding.selectionToolbar.visibility = View.GONE
            binding.searchCard.visibility = View.VISIBLE
            binding.searchCard.animate().alpha(1f).setDuration(200)
        }
    }

    private fun loadFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val baseDir =
                    when (currentCategory) {
                        "PROJECTS" -> StorageManager.getProjectsDir(this@StorageDetailActivity)
                        "EXPORTS" -> StorageManager.getTempExportsDir(this@StorageDetailActivity)
                        "DOWNLOADS" -> StorageManager.getAssetsDir(this@StorageDetailActivity)
                        else -> StorageManager.getImportedMediaDir(this@StorageDetailActivity)
                    }

            val items = mutableListOf<FileItem>()

            if (currentCategory == "MEDIA") {
                // Find subdirectories for projects
                val projectDirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()
                projectDirs.forEach { dir ->
                    val projectName = getProjectName(dir.name)
                    dir.listFiles()?.forEach { file ->
                        items.add(FileItem(file, currentCategory, projectName))
                    }
                }
                // Also add flat files if any
                baseDir.listFiles { f -> f.isFile }?.forEach { file ->
                    items.add(FileItem(file, currentCategory, "General / Unassigned"))
                }
            } else {
                baseDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        items.add(FileItem(file, currentCategory))
                    }
                }
            }

            allFiles = items.sortedByDescending { it.file.lastModified() }

            withContext(Dispatchers.Main) {
                filterFiles(binding.searchEditText.text?.toString() ?: "")
            }
        }
    }

    private fun getProjectName(id: String): String {
        // Simple mock or lookup if ProjectManager can provide it
        // For now, use the ID or a truncated version
        return "Project: ${id.take(8).uppercase()}"
    }

    private fun filterFiles(query: String) {
        applyFiltersAndSort(query)
    }

    private fun applyFiltersAndSort(query: String = binding.searchEditText.text?.toString() ?: "") {
        var filtered =
                allFiles.filter { item ->
                    val matchesQuery =
                            if (query.isEmpty()) true
                            else
                                    (item.file.name.contains(query, ignoreCase = true) ||
                                            item.groupName?.contains(query, ignoreCase = true) ==
                                                    true)

                    val matchesType =
                            when (currentTypeFilter) {
                                "VIDEO" -> isVideo(item.file)
                                "AUDIO" -> isAudio(item.file)
                                "IMAGE" -> isImage(item.file) && !isGif(item.file)
                                "GIF" -> isGif(item.file)
                                "STICKER" ->
                                        item.file.path.contains("stickers", ignoreCase = true) ||
                                                item.file.path.contains(
                                                        "Stickers",
                                                        ignoreCase = true
                                                )
                                else -> true
                            }
                    matchesQuery && matchesType
                }

        filtered =
                when (currentSortMode) {
                    SortMode.SIZE_ASC -> filtered.sortedBy { it.file.length() }
                    SortMode.SIZE_DESC -> filtered.sortedByDescending { it.file.length() }
                    SortMode.DATE_ASC -> filtered.sortedBy { it.file.lastModified() }
                    SortMode.DATE_DESC -> filtered.sortedByDescending { it.file.lastModified() }
                }

        adapter.submitList(filtered)
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun isVideo(file: File) =
            listOf("mp4", "mkv", "mov", "avi").contains(file.extension.lowercase())
    private fun isAudio(file: File) =
            listOf("mp3", "wav", "m4a", "ogg").contains(file.extension.lowercase())
    private fun isImage(file: File) =
            listOf("jpg", "jpeg", "png", "webp", "gif").contains(file.extension.lowercase())
    private fun isGif(file: File) = file.extension.lowercase() == "gif"

    private fun showDeleteDialog(items: List<FileItem>) {
        val dlg = com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(layoutInflater)

        dlg.tvTitle.text = if (items.size == 1) "Delete File?" else "Delete Multiple Files?"
        dlg.tvMessage.text =
                if (items.size == 1) "Delete '${items[0].file.name}'? This action cannot be undone."
                else "Delete ${items.size} files permanently?"

        dlg.btnPrimary.text = "Delete"
        dlg.btnSecondary.text = "Cancel"

        val dialog =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setView(dlg.root)
                        .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dlg.btnPrimary.setOnClickListener {
            items.forEach { it.file.delete() }
            adapter.clearSelection()
            loadFiles()
            dialog.dismiss()
            Toast.makeText(this, "Deleted ${items.size} files", Toast.LENGTH_SHORT).show()
        }

        dlg.btnSecondary.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    data class FileItem(val file: File, val category: String, val groupName: String? = null)

    class StorageFileAdapter(
            private val onDelete: (FileItem) -> Unit,
            private val onSelectionChanged: (Int) -> Unit
    ) : RecyclerView.Adapter<StorageFileAdapter.ViewHolder>() {

        private var items = listOf<FileItem>()
        private var isSelectionMode = false
        private val selectedPaths = mutableSetOf<String>()

        fun submitList(newList: List<FileItem>) {
            items = newList
            notifyDataSetChanged()
        }

        fun getSelectedItems() = items.filter { selectedPaths.contains(it.file.absolutePath) }

        fun clearSelection() {
            selectedPaths.clear()
            isSelectionMode = false
            notifyDataSetChanged()
            onSelectionChanged(0)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                    LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_saved_project, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val card = view as com.google.android.material.card.MaterialCardView
            private val icon: ImageView = view.findViewById(R.id.ivProjectIcon)
            private val title: TextView = view.findViewById(R.id.tvProjectName)
            private val subtitle: TextView = view.findViewById(R.id.tvLastModified)
            private val size: TextView = view.findViewById(R.id.tvDuration)
            private val btnFavorite: View = view.findViewById(R.id.btnFavoriteProject)

            fun bind(item: FileItem) {
                val file = item.file
                title.text = file.name

                val date = java.text.SimpleDateFormat("MMM dd, yyyy").format(file.lastModified())
                subtitle.text = if (item.groupName != null) "${item.groupName} • $date" else date

                size.text = StorageManager.formatSize(file.length())
                btnFavorite.visibility = View.GONE

                icon.setImageResource(
                        when (item.category) {
                            "PROJECTS" -> R.drawable.ic_folder
                            "DOWNLOADS" -> R.drawable.ic_cloud_download
                            "EXPORTS" -> R.drawable.ic_movie_filter
                            else -> R.drawable.ic_speed
                        }
                )

                val isSelected = selectedPaths.contains(file.absolutePath)
                card.isChecked = isSelected
                card.isCheckable = isSelectionMode

                // standard V4 selection style
                card.strokeColor =
                        android.graphics.Color.parseColor(
                                if (isSelected) "#00D2D3" else "#1AFFFFFF"
                        )
                card.strokeWidth = if (isSelected) 4 else 2

                itemView.setOnClickListener {
                    if (isSelectionMode) toggleSelection(file)
                    else {
                        // Preview or Delete single
                        onDelete(item)
                    }
                }

                itemView.setOnLongClickListener {
                    if (!isSelectionMode) {
                        isSelectionMode = true
                        toggleSelection(file)
                    }
                    true
                }
            }

            private fun toggleSelection(file: File) {
                if (selectedPaths.contains(file.absolutePath)) {
                    selectedPaths.remove(file.absolutePath)
                } else {
                    selectedPaths.add(file.absolutePath)
                }
                if (selectedPaths.isEmpty()) isSelectionMode = false
                notifyDataSetChanged()
                onSelectionChanged(selectedPaths.size)
            }
        }
    }
}
