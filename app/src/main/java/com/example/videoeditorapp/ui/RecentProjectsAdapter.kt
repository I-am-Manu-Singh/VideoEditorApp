package com.example.videoeditorapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.R
import com.example.videoeditorapp.model.timeline.TimelineProject

class RecentProjectsAdapter(
    private var projects: List<TimelineProject>,
    private val onProjectClick: (TimelineProject) -> Unit
) : RecyclerView.Adapter<RecentProjectsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_project_mini, parent, false)

        // ensure click enabled at root
        view.isClickable = true
        view.isFocusable = true

        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(projects[position])
    }

    override fun getItemCount(): Int = projects.size

    fun submitList(newProjects: List<TimelineProject>) {
        projects = newProjects
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tvProjectName)
        private val tvTime: TextView = itemView.findViewById(R.id.tvLastEdited)
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivProjectThumb)

        fun bind(project: TimelineProject) {
            tvName.text = project.name

            val diff = System.currentTimeMillis() - project.lastModified
            val hours = diff / (1000 * 60 * 60)
            val days = hours / 24

            tvTime.text = when {
                days > 0 -> "$days days ago"
                hours > 0 -> "$hours hours ago"
                else -> "Just now"
            }

            // 🍏 Load Thumbnail if available
            project.thumbnailPath?.let { path ->
                val file = java.io.File(path)
                if (file.exists()) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                    ivThumb.setImageBitmap(bitmap)
                    ivThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                } else {
                    ivThumb.setImageResource(R.drawable.temp_cinematic)
                }
            } ?: run {
                ivThumb.setImageResource(R.drawable.temp_cinematic)
            }

            // ensure click works
            itemView.setOnClickListener {
                onProjectClick(project)
            }
        }
    }
}