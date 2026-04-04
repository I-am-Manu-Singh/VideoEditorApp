package com.example.videoeditorapp.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FavoritesFragment : Fragment() {

        private lateinit var recyclerView: RecyclerView
        private lateinit var emptyView: TextView

        override fun onCreateView(
                inflater: LayoutInflater,
                container: ViewGroup?,
                savedInstanceState: Bundle?
        ): View? {
                val context = requireContext()
                val frameLayout = android.widget.FrameLayout(context)
                frameLayout.layoutParams =
                        ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        )

                recyclerView = RecyclerView(context)
                recyclerView.layoutParams =
                        ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        )
                // Grid layout for templates
                recyclerView.layoutManager = GridLayoutManager(context, 2)
                frameLayout.addView(recyclerView)

                emptyView = TextView(context)
                emptyView.text = "No favorites yet"
                emptyView.gravity = android.view.Gravity.CENTER
                emptyView.textSize = 18f
                emptyView.setTextColor(android.graphics.Color.GRAY)
                frameLayout.addView(emptyView)

                return frameLayout
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
                super.onViewCreated(view, savedInstanceState)

        }

        override fun onResume() {
                super.onResume()
        }


}
