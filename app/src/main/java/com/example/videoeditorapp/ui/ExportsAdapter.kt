package com.example.videoeditorapp.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.R
import com.example.videoeditorapp.databinding.ItemExportsBinding
import java.io.File
import java.text.DecimalFormat

data class ExportItem(val file: java.io.File, var isFavorite: Boolean)

class ExportsAdapter(
        private val onPlay: (java.io.File) -> Unit,
        private val onShare: (java.io.File) -> Unit,
        private val onRename: (java.io.File) -> Unit,
        private val onDelete: (java.io.File) -> Unit,
        private val onFavoriteClick: (java.io.File) -> Unit,
        private val onSelectionChanged: (Int) -> Unit = {}
) : ListAdapter<ExportItem, ExportsAdapter.ExportViewHolder>(ExportDiffCallback()) {

    private var isSelectionMode = false
    private val selectedPaths = mutableSetOf<String>()

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) selectedPaths.clear()
            notifyDataSetChanged()
            onSelectionChanged(selectedPaths.size)
        }
    }

    fun getSelectedFiles(): List<java.io.File> {
        return currentList.filter { selectedPaths.contains(it.file.absolutePath) }.map { it.file }
    }

    fun clearSelection() {
        selectedPaths.clear()
        isSelectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExportViewHolder {
        val binding = ItemExportsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ExportViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExportViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ExportViewHolder(private val binding: ItemExportsBinding) :
            RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExportItem) {
            val file = item.file
            binding.tvFileName.text = file.name
            binding.tvFileSize.text = getFileSize(file)
            binding.tvDate.text = getDate(file)

            val metadata = getVideoMetadata(file)
            binding.tvDuration.text = metadata.first
            binding.tvResolution.text = metadata.second
            binding.tvTechnicalInfo.text = metadata.third

            binding.btnPlay.setOnClickListener { onPlay(file) }

            val isFav = item.isFavorite
            binding.btnFavorite.setImageResource(
                    if (isFav) R.drawable.ic_heart else R.drawable.ic_heart_outline
            )

            val defaultColor = resolveColor(binding.root.context, android.R.attr.textColorPrimary)

            binding.btnFavorite.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                            if (isFav) android.graphics.Color.parseColor("#FF4081")
                            else defaultColor
                    )

            binding.btnFavorite.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(item)
                } else {
                    onFavoriteClick(file)
                }
            }

        val isSelected = selectedPaths.contains(file.absolutePath)

(binding.root as? com.google.android.material.card.MaterialCardView)?.let { card ->

    card.isCheckable = false

    card.strokeColor =
        if (isSelected) {
            android.graphics.Color.parseColor("#00D2D3")
        } else {
            android.graphics.Color.parseColor("#1AFFFFFF")
        }

    card.strokeWidth =
        if (isSelected) {
            4
        } else {
            2
        }

    card.cardElevation =
        if (isSelected) {
            12f
        } else {
            0f
        }

    card.setCardBackgroundColor(
        if (isSelected) {
            android.graphics.Color.parseColor("#3300D2D3")
        } else {
            android.graphics.Color.parseColor("#0DFFFFFF")
        }
    )
}

          binding.root.setOnClickListener {
    if (isSelectionMode) {
        toggleSelection(item)
    } else {
        onPlay(file)
    }
}

binding.root.setOnLongClickListener {

    if (!isSelectionMode) {
        setSelectionMode(true)
    }

    toggleSelection(item)
    true
}
        }

private fun toggleSelection(item: ExportItem) {

    val pos = bindingAdapterPosition
    if (pos == RecyclerView.NO_POSITION) return

    val path = item.file.absolutePath

    if (selectedPaths.contains(path)) {
        selectedPaths.remove(path)
    } else {
        selectedPaths.add(path)
    }

    notifyItemChanged(pos)
    onSelectionChanged(selectedPaths.size)

    if (selectedPaths.isEmpty()) {
        isSelectionMode = false
        notifyDataSetChanged()
    }
}

        private fun resolveColor(context: android.content.Context, attr: Int): Int {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return if (typedValue.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        }

        private fun getFileSize(file: java.io.File): String {
            val size = file.length()
            val df = DecimalFormat("#.##")
            val mb = size / (1024.0 * 1024.0)
            return "${df.format(mb)} MB"
        }

        private fun getDate(file: java.io.File): String {
            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(file.lastModified()))
        }

        private fun getVideoMetadata(file: java.io.File): Triple<String, String, String> {
            val mmr = android.media.MediaMetadataRetriever()
            return try {
                mmr.setDataSource(file.absolutePath)
                val durationStr =
                        mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                        )
                val width =
                        mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                        )
                val height =
                        mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                        )

                val durationMs = durationStr?.toLongOrNull() ?: 0L
                val duration = formatDuration(durationMs)

                val bitrate =
                        mmr.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE
                                )
                                ?.toLongOrNull()
                                ?: 0L
                val bitrateStr = if (bitrate > 0) "${bitrate / 1000}kbps" else ""
                val mime =
                        mmr.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE
                                )
                                ?.substringAfter("/")
                                ?: "mp4"

                var resolution = "Unknown"
                if (width != null && height != null) {
                    val w = width.toInt()
                    val h = height.toInt()
                    resolution = "${w}x${h}"
                }

                Triple(duration, resolution, "$bitrateStr • $mime".trim { it == ' ' || it == '•' })
            } catch (e: Exception) {
                Triple("00:00", "Unknown", "")
            } finally {
                try {
                    mmr.release()
                } catch (e: Exception) {}
            }
        }

        private fun gcd(a: Int, b: Int): Int {
            return if (b == 0) a else gcd(b, a % b)
        }

        @SuppressLint("DefaultLocale")
        private fun formatDuration(ms: Long): String {
            val seconds = (ms / 1000) % 60
            val minutes = (ms / 1000) / 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }

    class ExportDiffCallback : DiffUtil.ItemCallback<ExportItem>() {
        override fun areItemsTheSame(oldItem: ExportItem, newItem: ExportItem): Boolean {
            return oldItem.file.absolutePath == newItem.file.absolutePath
        }

        override fun areContentsTheSame(oldItem: ExportItem, newItem: ExportItem): Boolean {
            return oldItem.file.lastModified() == newItem.file.lastModified() &&
                    oldItem.isFavorite == newItem.isFavorite
        }
    }
}
