package com.example.videoeditorapp.ui.editor.pickers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.videoeditorapp.R
import com.example.videoeditorapp.model.timeline.StickerLibrary
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StickerPickerBottomSheet(private val onStickerSelected: (String) -> Unit) :
        BottomSheetDialogFragment() {

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_sticker_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvStickerGrid = view.findViewById<RecyclerView>(R.id.rvStickerGrid)
        val etSearch = view.findViewById<android.widget.EditText>(R.id.etGifSearch)
        rvStickerGrid.layoutManager = GridLayoutManager(requireContext(), 3)

        val allStickers = mutableListOf<String>()
        StickerLibrary.getAllStickers().forEach { allStickers.add("res://$it") }
        StickerLibrary.remoteStickers.forEach { allStickers.add(it.url) }

        val adapter =
                StickerAdapter(allStickers) { stickerPath ->
                    onStickerSelected(stickerPath)
                    dismiss()
                }
        rvStickerGrid.adapter = adapter

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
                        val filtered =
                                allStickers.filter {
                                    it.lowercase().contains(query) ||
                                            it.contains(
                                                    "res://"
                                            ) // Keep local ones for now or filter by name if
                                    // available
                                }
                        adapter.updateList(filtered)
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )
    }

    override fun onStart() {
        super.onStart()
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            peekHeight = resources.displayMetrics.heightPixels / 2
        }
    }

    class StickerAdapter(
            private var stickerPaths: List<String>,
            private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<StickerAdapter.ViewHolder>() {

        fun updateList(newList: List<String>) {
            stickerPaths = newList
            notifyDataSetChanged()
        }
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivSticker: ImageView = view as ImageView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val iv =
                    ImageView(parent.context).apply {
                        layoutParams =
                                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 250)
                        setPadding(32, 32, 32, 32)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        isClickable = true
                        isFocusable = true
                        val outValue = android.util.TypedValue()
                        context.theme.resolveAttribute(
                                android.R.attr.selectableItemBackground,
                                outValue,
                                true
                        )
                        setBackgroundResource(outValue.resourceId)
                    }
            return ViewHolder(iv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val path = stickerPaths[position]
            if (path.startsWith("res://")) {
                val resId = path.replace("res://", "").toInt()
                holder.ivSticker.setImageResource(resId)
            } else {
                Glide.with(holder.ivSticker.context)
                        .load(path)
                        .fitCenter()
                        .placeholder(R.color.white_10)
                        .into(holder.ivSticker)
            }
            holder.ivSticker.setOnClickListener { onClick(path) }
        }

        override fun getItemCount() = stickerPaths.size
    }
}
