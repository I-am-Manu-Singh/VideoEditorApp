package com.example.videoeditorapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

data class StickerModel(val emoji: String? = null, val stickerRes: Int? = null)

class StickerAdapter(private val onItemClick: (StickerModel) -> Unit) :
        ListAdapter<StickerModel, StickerAdapter.StickerViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StickerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false)
        return StickerViewHolder(view)
    }

    override fun onBindViewHolder(holder: StickerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvEmoji: TextView = itemView.findViewById(R.id.tvEmoji)
        private val imgSticker: ImageView = itemView.findViewById(R.id.imgSticker)

        fun bind(item: StickerModel) {
            if (item.emoji != null) {
                tvEmoji.visibility = View.VISIBLE
                imgSticker.visibility = View.GONE
                tvEmoji.text = item.emoji
            } else if (item.stickerRes != null) {
                tvEmoji.visibility = View.GONE
                imgSticker.visibility = View.VISIBLE
                imgSticker.setImageResource(item.stickerRes)
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    companion object {
        private val DiffCallback =
                object : DiffUtil.ItemCallback<StickerModel>() {
                    override fun areItemsTheSame(old: StickerModel, new: StickerModel) = old == new
                    override fun areContentsTheSame(old: StickerModel, new: StickerModel) =
                            old == new
                }
    }
}
