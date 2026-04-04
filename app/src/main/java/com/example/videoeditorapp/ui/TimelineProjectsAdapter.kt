package com.example.videoeditorapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.R
import com.example.videoeditorapp.model.FavoriteManager
import com.example.videoeditorapp.model.timeline.TimelineProject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimelineProjectsAdapter(
        private var projects: List<TimelineProject>,
        private val onProjectClick: (TimelineProject) -> Unit,
        private val onDeleteClick: (TimelineProject) -> Unit,
        private val onFavoriteClick: (TimelineProject) -> Unit,
        private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<TimelineProjectsAdapter.ProjectViewHolder>() {

    private var isSelectionMode = false
    private val selectedIds = mutableSetOf<String>()

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) selectedIds.clear()
            notifyDataSetChanged()
            onSelectionChanged(selectedIds.size)
        }
    }

    fun getSelectedProjects(): List<TimelineProject> {
        return projects.filter { selectedIds.contains(it.id) }
    }

    fun clearSelection() {
        selectedIds.clear()
        isSelectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val view =
                LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_saved_project, parent, false)
        return ProjectViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(projects[position])
    }

    override fun getItemCount(): Int = projects.size

    fun submitList(newProjects: List<TimelineProject>) {
        projects = newProjects
        notifyDataSetChanged()
    }

    inner class ProjectViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvProjectName: TextView = itemView.findViewById(R.id.tvProjectName)
        private val tvLastModified: TextView = itemView.findViewById(R.id.tvLastModified)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivProjectIcon)
        private val btnFavorite: ImageButton = itemView.findViewById(R.id.btnFavoriteProject)

        fun bind(project: TimelineProject) {
            tvProjectName.text = project.name

            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            tvLastModified.text = "Last edited: ${dateFormat.format(Date(project.lastModified))}"

            val durationMs = project.getDurationMs()
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / 1000) / 60
            val clipCount = project.tracks.sumOf { it.clips.size }

            tvDuration.text =
                    String.format(
                            "%02d:%02d • %d clips • %s",
                            minutes,
                            seconds,
                            clipCount,
                            project.templateId.replace("_", " ").lowercase().replaceFirstChar {
                                it.uppercase()
                            }
                    )

            // Icon based on Template ID
            ivIcon.setImageResource(
                    when (project.templateId) {
                        "CINEMATIC", "CINEMATIC_FRAME", "CINEMATIC_TITLE" ->
                                R.drawable.ic_movie_filter
                        "NEWS" -> R.drawable.ic_newspaper
                        "CAMERA" -> R.drawable.ic_camera_alt
                        "GLITCH_TEXT" -> R.drawable.ic_movie_filter // Or appropriate
                        else -> R.drawable.ic_video_camera
                    }
            )

            val isFav = FavoriteManager.isTimelineProjectFavorite(itemView.context, project.id)
            btnFavorite.setImageResource(
                    if (isFav) R.drawable.ic_heart else R.drawable.ic_heart_outline
            )

            // Fix: Use theme-aware color for unselected favorite
            val defaultColor = resolveColor(itemView.context, android.R.attr.textColorPrimary)
            btnFavorite.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                            if (isFav) android.graphics.Color.parseColor("#FF4081")
                            else defaultColor
                    )

            val isSelected = selectedIds.contains(project.id)
            (itemView as? com.google.android.material.card.MaterialCardView)?.apply {
                isChecked = isSelected
                isCheckable = isSelectionMode
            }

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(project)
                } else {
                    onProjectClick(project)
                }
            }
            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    setSelectionMode(true)
                    toggleSelection(project)
                }
                true
            }
            btnFavorite.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(project)
                } else {
                    onFavoriteClick(project)
                }
            }
        }

        private fun toggleSelection(project: TimelineProject) {
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return

            if (selectedIds.contains(project.id)) {
                selectedIds.remove(project.id)
            } else {
                selectedIds.add(project.id)
            }
            notifyItemChanged(pos)
            onSelectionChanged(selectedIds.size)
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
    }
}
