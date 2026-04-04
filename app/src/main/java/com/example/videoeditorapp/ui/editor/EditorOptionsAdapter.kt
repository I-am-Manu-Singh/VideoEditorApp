package com.example.videoeditorapp.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.databinding.ItemEditorOptionBinding

class EditorOptionsAdapter(
        private val options: List<EditorOption>,
        private val onOptionSelected: (EditorOption) -> Unit
) : RecyclerView.Adapter<EditorOptionsAdapter.ViewHolder>() {

    private var selectedId: String? = options.firstOrNull()?.id

    inner class ViewHolder(private val binding: ItemEditorOptionBinding) :
            RecyclerView.ViewHolder(binding.root) {

        fun bind(option: EditorOption) {
            binding.tvLabel.text = option.label
            binding.imgIcon.setImageResource(option.iconRes)

            // Progress Logic (0-100)
            val range = option.maxValue - option.minValue
            val progress =
                    if (range > 0) {
                        ((option.value - option.minValue) / range * 100).toInt()
                    } else {
                        0
                    }
            binding.progressCircle.progress = progress

            // Selection State
            val isSelected = option.id == selectedId
            binding.selectionRing.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.tvLabel.setTextColor(if (isSelected) 0xFF00D2D3.toInt() else 0xFFD0D0D0.toInt())
            binding.imgIcon.setColorFilter(
                    if (isSelected) 0xFF00D2D3.toInt() else 0xFFD0D0D0.toInt()
            )

            binding.root.setOnClickListener {
                val prevId = selectedId
                selectedId = option.id
                // Notify changes for visual update
                notifyDataSetChanged() // efficient enough for small lists
                onOptionSelected(option)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
                ItemEditorOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(options[position])
    }

    override fun getItemCount(): Int = options.size

    fun updateValue(id: String, newValue: Float) {
        val index = options.indexOfFirst { it.id == id }
        if (index != -1) {
            options[index].value = newValue
            notifyItemChanged(index)
        }
    }
}
