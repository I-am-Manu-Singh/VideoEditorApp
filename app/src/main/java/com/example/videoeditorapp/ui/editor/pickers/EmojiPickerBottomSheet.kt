package com.example.videoeditorapp.ui.editor.pickers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.R
import com.example.videoeditorapp.model.timeline.EmojiLibrary
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class EmojiPickerBottomSheet(private val onEmojiSelected: (String) -> Unit) :
        BottomSheetDialogFragment() {

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_emoji_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvEmojiGrid = view.findViewById<RecyclerView>(R.id.rvEmojiGrid)
        val etSearch = view.findViewById<android.widget.EditText>(R.id.etGifSearch)
        val categoryContainer =
                view.findViewById<android.widget.LinearLayout>(R.id.emojiCategoryContainer)

        rvEmojiGrid.layoutManager = GridLayoutManager(requireContext(), 7)

        val allEmojis = EmojiLibrary.getAllEmojis()

        val adapter =
                EmojiAdapter(allEmojis) { emoji ->
                    onEmojiSelected(emoji)
                    dismiss()
                }
        rvEmojiGrid.adapter = adapter

        // Setup Categories
        EmojiLibrary.categories.keys.forEach { categoryName ->
            val chip =
                    com.google.android.material.button.MaterialButton(
                                    requireContext(),
                                    null,
                                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                            )
                            .apply {
                                text = categoryName
                                textSize = 9f
                                setPadding(16, 0, 16, 0)
                                layoutParams =
                                        android.widget.LinearLayout.LayoutParams(
                                                        android.widget.LinearLayout.LayoutParams
                                                                .WRAP_CONTENT,
                                                        80
                                                )
                                                .apply { setMargins(0, 0, 12, 0) }
                                setOnClickListener {
                                    adapter.updateList(
                                            EmojiLibrary.categories[categoryName] ?: emptyList()
                                    )
                                }
                            }
            categoryContainer.addView(chip)
        }

        etSearch.addTextChangedListener(
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
                        val query = s?.toString()?.lowercase() ?: ""
                        val filtered = allEmojis.filter { it.contains(query) }
                        adapter.updateList(filtered)
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )
    }

    private val Int.sp: Float
        get() = this * resources.displayMetrics.scaledDensity

    override fun onStart() {
        super.onStart()
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            peekHeight = resources.displayMetrics.heightPixels / 2
        }
    }

    class EmojiAdapter(private var emojis: List<String>, private val onClick: (String) -> Unit) :
            RecyclerView.Adapter<EmojiAdapter.ViewHolder>() {

        fun updateList(newList: List<String>) {
            emojis = newList
            notifyDataSetChanged()
        }
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvEmoji: TextView = view as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv =
                    TextView(parent.context).apply {
                        layoutParams =
                                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150)
                        gravity = android.view.Gravity.CENTER
                        textSize = 30f
                        isClickable = true
                        isFocusable = true
                        val outValue = android.util.TypedValue()
                        context.theme.resolveAttribute(
                                android.R.attr.selectableItemBackgroundBorderless,
                                outValue,
                                true
                        )
                        setBackgroundResource(outValue.resourceId)
                    }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.tvEmoji.text = emojis[position]
            holder.tvEmoji.setOnClickListener { onClick(emojis[position]) }
        }

        override fun getItemCount() = emojis.size
    }
}
