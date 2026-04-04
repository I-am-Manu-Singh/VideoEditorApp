package com.example.videoeditorapp.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels // IMPORTANT
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.ExportActivity
import com.example.videoeditorapp.R
import com.example.videoeditorapp.model.FavoriteManager
import com.example.videoeditorapp.service.ExportService
import com.example.videoeditorapp.ui.ExportsAdapter
import com.example.videoeditorapp.utils.SearchViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class ExportsFragment : Fragment() {

        // Connects to the SearchBox in the Activity via Shared ViewModel
        private val searchViewModel: SearchViewModel by activityViewModels()

        private lateinit var recyclerView: RecyclerView
        private lateinit var adapter: ExportsAdapter
        private lateinit var emptyView: android.widget.TextView
        private lateinit var emptyViewContainer: View

        private var onlyFavorites: Boolean = false
        private var allExportItems: List<com.example.videoeditorapp.ui.ExportItem> = emptyList()

        companion object {
                private const val ARG_ONLY_FAVORITES = "only_favorites"

                fun newInstance(onlyFavorites: Boolean = false): ExportsFragment {
                        val fragment = ExportsFragment()
                        val args = Bundle()
                        args.putBoolean(ARG_ONLY_FAVORITES, onlyFavorites)
                        fragment.arguments = args
                        return fragment
                }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                onlyFavorites = arguments?.getBoolean(ARG_ONLY_FAVORITES) ?: false
        }

        override fun onCreateView(
                inflater: LayoutInflater,
                container: ViewGroup?,
                savedInstanceState: Bundle?
        ): View? {
                // fragment_searchable_list should NO LONGER contain the search box
                return inflater.inflate(R.layout.fragment_searchable_list, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
                super.onViewCreated(view, savedInstanceState)

                // Initialize views present in fragment_searchable_list.xml
                recyclerView = view.findViewById(R.id.recyclerView)
                emptyView = view.findViewById(R.id.emptyView)
                emptyViewContainer = view.findViewById(R.id.emptyViewContainer)

                recyclerView.layoutManager = LinearLayoutManager(requireContext())

                adapter =
                        ExportsAdapter(
                                onPlay = { playVideo(it) },
                                onShare = { shareVideo(it) },
                                onRename = { /* Handle Rename */},
                                onDelete = { file ->
                                        MaterialAlertDialogBuilder(requireContext())
                                                .setTitle("Delete Video")
                                                .setMessage("Delete '${file.name}' permanently?")
                                                .setPositiveButton("Delete") { _, _ ->
                                                        if (file.exists() && file.delete()) {
                                                                refreshList()
                                                                Toast.makeText(
                                                                                requireContext(),
                                                                                "Deleted",
                                                                                Toast.LENGTH_SHORT
                                                                        )
                                                                        .show()
                                                        }
                                                }
                                                .setNegativeButton("Cancel", null)
                                                .show()
                                },
                                onFavoriteClick = { file ->
                                        FavoriteManager.toggleExportFavorite(
                                                requireContext(),
                                                file.absolutePath
                                        )
                                        refreshList()
                                },
                                onSelectionChanged = { count -> updateSelectionUI(count) }
                        )

                recyclerView.adapter = adapter

                // Observe the query from the Activity's Search Bar
                searchViewModel.searchQuery.observe(viewLifecycleOwner) { query ->
                        filterList(query)
                }
        }

        private fun filterList(query: String?) {
                val searchText = query?.trim()?.lowercase() ?: ""
                val filtered =
                        if (searchText.isEmpty()) {
                                allExportItems
                        } else {
                                allExportItems.filter {
                                        it.file.name.lowercase().contains(searchText)
                                }
                        }

                adapter.submitList(filtered)

                val isEmpty = filtered.isEmpty()

                // Update empty state text
                emptyView.text =
                        if (searchText.isEmpty()) {
                                if (onlyFavorites) "No favorite exports yet" else "No exports yet"
                        } else {
                                "No matching exports found"
                        }

                // Show/Hide container (includes icon and text)
                emptyViewContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
                recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }

        override fun onResume() {
                super.onResume()
                refreshList()
        }

        private fun refreshList() {
                val folders =
                        listOf(
                                com.example.videoeditorapp.utils.NamingUtils.getExportDirectory(),
                                File(requireContext().getExternalFilesDir(null), "Exports"),
                                File(requireContext().getExternalFilesDir(null), "EditedVideos")
                        )

                var allFiles =
                        folders
                                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                                .filter {
                                        it.isFile &&
                                                (it.extension.lowercase() == "mp4" ||
                                                        it.extension.lowercase() == "mkv")
                                }
                                .sortedByDescending { it.lastModified() }

                if (onlyFavorites) {
                        val favPaths = FavoriteManager.getFavoriteExports(requireContext())
                        allFiles = allFiles.filter { favPaths.contains(it.absolutePath) }
                }

                allExportItems =
                        allFiles.map { file ->
                                com.example.videoeditorapp.ui.ExportItem(
                                        file,
                                        FavoriteManager.isExportFavorite(
                                                requireContext(),
                                                file.absolutePath
                                        )
                                )
                        }

                // Fetch the current query from ViewModel to apply filtering to the refreshed list
                filterList(searchViewModel.searchQuery.value)
        }

        private fun playVideo(file: File) {
                val intent =
                        Intent(requireContext(), ExportActivity::class.java).apply {
                                putExtra(ExportService.EXTRA_OUTPUT_PATH, file.absolutePath)
                        }
                startActivity(intent)
        }

        private fun shareVideo(file: File) {
                val uri =
                        FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.fileprovider",
                                file
                        )

                val shareIntent =
                        Intent(Intent.ACTION_SEND).apply {
                                type = "video/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                startActivity(Intent.createChooser(shareIntent, "Share Video"))
        }

        private fun updateSelectionUI(count: Int) {
                if (count > 0) {
                        val view = view ?: return
                        com.google.android.material.snackbar.Snackbar.make(
                                        view,
                                        "$count videos selected",
                                        com.google.android.material.snackbar.Snackbar
                                                .LENGTH_INDEFINITE
                                )
                                .setAction("Delete") { deleteSelectedVideos() }
                                .show()
                }
        }

        private fun deleteSelectedVideos() {
                val selected = adapter.getSelectedFiles()
                MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete Multiple Videos")
                        .setMessage("Are you sure you want to delete ${selected.size} videos?")
                        .setPositiveButton("Delete") { _, _ ->
                                selected.forEach { it.delete() }
                                adapter.clearSelection()
                                refreshList()
                        }
                        .setNegativeButton("Cancel") { _, _ -> adapter.clearSelection() }
                        .show()
        }
}
