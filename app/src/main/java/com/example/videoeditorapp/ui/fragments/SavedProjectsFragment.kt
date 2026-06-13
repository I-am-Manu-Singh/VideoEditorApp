package com.example.videoeditorapp.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.R
import com.example.videoeditorapp.TimelineTemplateEditorActivity
import com.example.videoeditorapp.model.FavoriteManager
import com.example.videoeditorapp.model.timeline.TimelineProject
import com.example.videoeditorapp.ui.TimelineProjectsAdapter
import com.example.videoeditorapp.utils.ProjectManager
import com.example.videoeditorapp.utils.SearchViewModel 
import com.example.videoeditorapp.utils.AppDialog

class SavedProjectsFragment : Fragment() {

        private val searchViewModel: SearchViewModel by activityViewModels()

        private lateinit var recyclerView: RecyclerView
        private lateinit var adapter: TimelineProjectsAdapter
        private lateinit var emptyView: TextView
        private lateinit var emptyViewContainer: View
        private var onlyFavorites: Boolean = false
        private var allProjects: List<TimelineProject> = emptyList()

        // ❌ DELETED: searchEditText (it's in the Activity now)

        companion object {
                private const val ARG_ONLY_FAVORITES = "only_favorites"

                fun newInstance(onlyFavorites: Boolean = false): SavedProjectsFragment {
                        val fragment = SavedProjectsFragment()
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
                return inflater.inflate(R.layout.fragment_searchable_list, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
                super.onViewCreated(view, savedInstanceState)

                recyclerView = view.findViewById(R.id.recyclerView)
                emptyView = view.findViewById(R.id.emptyView) // Don't forget to init this!
                emptyViewContainer = view.findViewById(R.id.emptyViewContainer)

                recyclerView.layoutManager = LinearLayoutManager(requireContext())

                adapter =
                        TimelineProjectsAdapter(
                                emptyList(),
                                onProjectClick = { project ->
                                        val intent =
                                                Intent(
                                                        requireContext(),
                                                        TimelineTemplateEditorActivity::class.java
                                                )
                                        intent.putExtra("PROJECT_ID", project.id)
                                        intent.putExtra("PROJECT_NAME", project.name)
                                        intent.putExtra("TEMPLATE_ID", project.templateId)
                                        startActivity(intent)
                                },
                                onDeleteClick = { project ->
                                        AppDialog.showDelete(
                                            context = requireContext(),
                                            title = "Delete Project",
                                            message = "Delete project '${project.name}'?\n\nThis action cannot be undone.",
                                            onDelete = {
                                                val deleted =
                                                    ProjectManager.deleteProject(
                                                        requireContext(),
                                                        project.id
                                                    )

                                                android.util.Log.d(
                                                    "PROJECT_DELETE",
                                                    "${project.name} -> $deleted"
                                                )

                                                if (deleted) {
                                                    FavoriteManager.removeTimelineProjectFavorite(
                                                        requireContext(),
                                                        project.id
                                                    )
                                                }

                                                refreshList()
                                            }
                                        )
                                },
                                onFavoriteClick = { project ->
                                        FavoriteManager.toggleTimelineProjectFavorite(
                                                requireContext(),
                                                project.id
                                        )
                                        refreshList()
                                },
                                onSelectionChanged = { count -> updateSelectionUI(count) }
                        )

                recyclerView.adapter = adapter

                // 🍏 THE FIX: Observe the search query from the Activity's Search Bar
                searchViewModel.searchQuery.observe(viewLifecycleOwner) { query ->
                        filterList(query)
                }
        }

        // ❌ DELETED: setupSearch function (Activity handles the listener now)

        private fun filterList(query: String?) {
                val searchText = query?.trim()?.lowercase() ?: ""
                val filtered =
                        if (searchText.isEmpty()) {
                                allProjects
                        } else {
                                allProjects.filter { it.name.lowercase().contains(searchText) }
                        }

                adapter.submitList(filtered)
                val isEmpty = filtered.isEmpty()

                // Update the text specifically
                emptyView.text =
                        if (searchText.isEmpty()) {
                                if (onlyFavorites) "No favorite projects yet"
                                else "No saved projects yet"
                        } else {
                                "No matching projects found"
                        }

                // Control the visibility of the whole group (Icon + Text)
                emptyViewContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
                recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }

        override fun onResume() {
                super.onResume()
                refreshList()
        }

        private fun refreshList() {
                var projects = ProjectManager.listProjects(requireContext())

                if (onlyFavorites) {
                        val favIds = FavoriteManager.getFavoriteTimelineProjects(requireContext())
                        projects = projects.filter { favIds.contains(it.id) }
                }

                allProjects = projects

                // 🍏 THE FIX: Read query from ViewModel instead of missing EditText
                filterList(searchViewModel.searchQuery.value)
        }

        private fun updateSelectionUI(count: Int) {
                searchViewModel.selectedCount.postValue(count)
                if (count > 0) {
                        val view = view ?: return
                        com.google.android.material.snackbar.Snackbar.make(
                                         view,
                                         "$count projects selected",
                                         com.google.android.material.snackbar.Snackbar
                                                 .LENGTH_INDEFINITE
                                 )
                                 .setAction("Delete") { deleteSelectedProjects() }
                                 .show()
                }
        }

       private fun deleteSelectedProjects() {

    val selected = adapter.getSelectedProjects()
    if (selected.isEmpty()) return
AppDialog.showDelete(
    context = requireContext(),
    title = "Delete Multiple Projects",
    message = "Are you sure you want to delete ${selected.size} projects?",
    onDelete = {
        selected.forEach { project ->
            val deleted =
                ProjectManager.deleteProject(
                    requireContext(),
                    project.id
                )
            android.util.Log.d(
                "PROJECT_DELETE",
                "${project.name} -> $deleted"
            )
            if (deleted) {
                FavoriteManager.removeTimelineProjectFavorite(
                    requireContext(),
                    project.id
                )
            }
        }
        adapter.clearSelection()
        refreshList()
    }
)
}
}
