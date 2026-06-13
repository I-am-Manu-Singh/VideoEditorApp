package com.example.videoeditorapp.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.R

data class HighlightItem(val id: String, val title: String, val tag: String, val imageRes: Int)

class HighlightAdapter(
    private val items: List<HighlightItem>,
    private val onClick: (HighlightItem) -> Unit
) : RecyclerView.Adapter<HighlightAdapter.HighlightViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HighlightViewHolder {
        val view =
                LayoutInflater.from(parent.context).inflate(R.layout.item_recent_project_mini, parent, false)
        return HighlightViewHolder(view)
    }

    override fun onBindViewHolder(holder: HighlightViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HighlightViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgHighlight: ImageView = view.findViewById(R.id.ivProjectThumb)
        private val tvTag: TextView = view.findViewById(R.id.tvLastEdited)
        private val tvTitle: TextView = view.findViewById(R.id.tvProjectName)

        fun bind(item: HighlightItem) {
            imgHighlight.setImageResource(item.imageRes)
            tvTag.text = item.tag
            tvTitle.text = item.title
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
