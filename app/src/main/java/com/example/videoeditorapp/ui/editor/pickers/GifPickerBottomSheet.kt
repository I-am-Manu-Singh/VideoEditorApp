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
import com.example.videoeditorapp.model.timeline.GifItem
import com.example.videoeditorapp.model.timeline.GifLibrary
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class GifPickerBottomSheet(private val onGifSelected: (String) -> Unit) :
        BottomSheetDialogFragment() {

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_gif_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvGifGrid = view.findViewById<RecyclerView>(R.id.rvGifGrid)
        val etSearch = view.findViewById<android.widget.EditText>(R.id.etGifSearch)
        rvGifGrid.layoutManager = GridLayoutManager(requireContext(), 2)

        val allGifs = GifLibrary.trendingGifs

        val adapter =
                GifAdapter(allGifs) { gifUrl ->
                    onGifSelected(gifUrl)
                    dismiss()
                }
        rvGifGrid.adapter = adapter

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
                                allGifs.filter {
                                    it.id.lowercase().contains(query) ||
                                            it.gifUrl.lowercase().contains(query)
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

    class GifAdapter(private var gifs: List<GifItem>, private val onClick: (String) -> Unit) :
            RecyclerView.Adapter<GifAdapter.ViewHolder>() {

        fun updateList(newList: List<GifItem>) {
            gifs = newList
            notifyDataSetChanged()
        }
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivGif: ImageView = view as ImageView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val iv =
                    ImageView(parent.context).apply {
                        layoutParams =
                                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setPadding(4, 4, 4, 4)
                        isClickable = true
                        isFocusable = true
                    }
            return ViewHolder(iv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = gifs[position]
            Glide.with(holder.ivGif.context)
                    .load(item.previewUrl)
                    .centerCrop()
                    .placeholder(R.color.white_10)
                    .into(holder.ivGif)
            holder.ivGif.setOnClickListener { onClick(item.gifUrl) }
        }

        override fun getItemCount() = gifs.size
    }
}
