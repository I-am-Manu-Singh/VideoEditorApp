package com.example.videoeditorapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.databinding.ItemAssetBinding
import com.example.videoeditorapp.model.timeline.AssetItem

class AssetAdapter(
        private val onDownloadClick: (AssetItem) -> Unit,
        private val onItemClick: (AssetItem) -> Unit
) : ListAdapter<AssetItem, AssetAdapter.AssetViewHolder>(AssetDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetViewHolder {
        val binding =
                ItemAssetBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                )
        return AssetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AssetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AssetViewHolder(private val binding: ItemAssetBinding) :
            RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AssetItem) {
            binding.tvTitle.text = item.title
            binding.tvCategory.text = item.category
            binding.btnDownload.visibility = if (item.isDownloaded) View.GONE else View.VISIBLE
            binding.downloadCheck.visibility = if (item.isDownloaded) View.VISIBLE else View.GONE

            if (item.thumbnailUrl != null) {
                com.bumptech.glide.Glide.with(binding.imgThumb.context)
                    .load(item.thumbnailUrl)
                    .centerCrop()
                    .placeholder(R.drawable.temp_video)
                    .into(binding.imgThumb)
                binding.imgThumb.alpha = 1.0f
            } else if (item.thumbnailResId != null) {
                binding.imgThumb.setImageResource(item.thumbnailResId!!)
                binding.imgThumb.alpha = 1.0f
            } else {
                binding.imgThumb.setImageResource(R.drawable.temp_video)
                binding.imgThumb.alpha = 0.5f
            }

            binding.btnDownload.setOnClickListener { onDownloadClick(item) }
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    class AssetDiffCallback : DiffUtil.ItemCallback<AssetItem>() {
        override fun areItemsTheSame(oldItem: AssetItem, newItem: AssetItem): Boolean =
                oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AssetItem, newItem: AssetItem): Boolean =
                oldItem == newItem
    }

    companion object {
        private val companionObject = null // Placeholder for parent reference if needed
    }
}
