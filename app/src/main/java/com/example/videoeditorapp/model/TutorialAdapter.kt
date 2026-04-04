package com.example.videoeditorapp.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.R

class TutorialAdapter(
        private val items: List<HighlightItem>,
        private val onClick: (HighlightItem) -> Unit
) : RecyclerView.Adapter<TutorialAdapter.ArticleViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder {
        val view =
                LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_article_list, parent, false)
        return ArticleViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ArticleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgThumb: ImageView = view.findViewById(R.id.ivArticleThumb)
        private val tvTitle: TextView = view.findViewById(R.id.tvArticleTitle)
        private val tvTags: TextView = view.findViewById(R.id.tvArticleTags)

        fun bind(item: HighlightItem) {
            imgThumb.setImageResource(item.imageRes)
            tvTitle.text = item.title
            tvTags.text = item.tag
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
