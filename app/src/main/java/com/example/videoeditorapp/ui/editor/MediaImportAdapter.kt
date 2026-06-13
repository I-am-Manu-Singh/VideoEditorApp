package com.example.videoeditorapp.ui.editor

import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.videoeditorapp.R
import com.example.videoeditorapp.utils.ImportedMediaItem

class MediaImportAdapter(
    private var items: List<ImportedMediaItem>,
    private val onImport: (ImportedMediaItem) -> Unit
) : RecyclerView.Adapter<BaseMediaViewHolder>() {

    fun submitList(newItems: List<ImportedMediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type.lowercase()) {
            "emoji" -> 1
            "sticker" -> 2
            "gif" -> 3
            else -> 0
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseMediaViewHolder {
        val context = parent.context
        return when (viewType) {
            1 -> {
                val heightPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    64f,
                    context.resources.displayMetrics
                ).toInt()
                val tv = TextView(context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        heightPx
                    )
                    gravity = Gravity.CENTER
                    textSize = 28f
                    setTextColor(android.graphics.Color.WHITE)
                    isClickable = true
                    isFocusable = true
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                }
                EmojiViewHolder(tv)
            }
            2 -> {
                val heightPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    90f,
                    context.resources.displayMetrics
                ).toInt()
                val iv = ImageView(context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        heightPx
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(16, 16, 16, 16)
                    isClickable = true
                    isFocusable = true
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                }
                StickerViewHolder(iv)
            }
            3 -> {
                val heightPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    120f,
                    context.resources.displayMetrics
                ).toInt()
                val iv = ImageView(context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        heightPx
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setPadding(4, 4, 4, 4)
                    isClickable = true
                    isFocusable = true
                }
                GifViewHolder(iv)
            }
            else -> {
                val view = LayoutInflater.from(context).inflate(R.layout.item_media_import, parent, false)
                StandardMediaViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: BaseMediaViewHolder, position: Int) {
        holder.bind(items[position], onImport)
    }

    override fun getItemCount(): Int = items.size
}

abstract class BaseMediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    abstract fun bind(item: ImportedMediaItem, onImport: (ImportedMediaItem) -> Unit)
}

class EmojiViewHolder(val textView: TextView) : BaseMediaViewHolder(textView) {
    override fun bind(item: ImportedMediaItem, onImport: (ImportedMediaItem) -> Unit) {
        textView.text = item.name
        textView.setOnClickListener { onImport(item) }
    }
}

class StickerViewHolder(val imageView: ImageView) : BaseMediaViewHolder(imageView) {
    override fun bind(item: ImportedMediaItem, onImport: (ImportedMediaItem) -> Unit) {
        if (item.uri.startsWith("res://")) {
            val resId = item.uri.replace("res://", "").toIntOrNull()
            if (resId != null) {
                imageView.setImageResource(resId)
            } else {
                imageView.setImageDrawable(null)
            }
        } else {
            Glide.with(imageView.context)
                .load(item.uri)
                .fitCenter()
                .placeholder(R.drawable.bg_tag_glass)
                .into(imageView)
        }
        imageView.setOnClickListener { onImport(item) }
    }
}

class GifViewHolder(val imageView: ImageView) : BaseMediaViewHolder(imageView) {
    override fun bind(item: ImportedMediaItem, onImport: (ImportedMediaItem) -> Unit) {
        Glide.with(imageView.context)
            .load(item.uri)
            .centerCrop()
            .placeholder(R.drawable.bg_tag_glass)
            .into(imageView)
        imageView.setOnClickListener { onImport(item) }
    }
}

class StandardMediaViewHolder(itemView: View) : BaseMediaViewHolder(itemView) {
    private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumbnail)
    private val tvName: TextView = itemView.findViewById(R.id.tvMediaName)
    private val tvMeta: TextView = itemView.findViewById(R.id.tvMediaMeta)
    private val ivType: ImageView = itemView.findViewById(R.id.ivType)
    private val btnImport: Button = itemView.findViewById(R.id.btnImport)

    override fun bind(item: ImportedMediaItem, onImport: (ImportedMediaItem) -> Unit) {
        tvName.text = item.name
        val sizeMb = item.size / (1024f * 1024f)
        tvMeta.text = String.format("%.1f MB • %s", sizeMb, item.type.uppercase())

        ivType.setImageResource(
            when (item.type.lowercase()) {
                "video" -> R.drawable.ic_movie_filter
                "audio" -> R.drawable.ic_music_note
                "image" -> R.drawable.ic_image
                else -> R.drawable.ic_folder
            }
        )

        if (item.type.equals("image", true) || item.type.equals("video", true)) {
            Glide.with(itemView.context)
                .load(Uri.parse(item.uri))
                .placeholder(R.drawable.bg_tag_glass)
                .error(R.drawable.bg_tag_glass)
                .centerCrop()
                .into(ivThumb)
        } else {
            ivThumb.setImageResource(R.drawable.ic_music_note)
        }

        btnImport.setOnClickListener { onImport(item) }
        itemView.setOnClickListener { onImport(item) }
    }
}