package com.example.videoeditorapp.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels // Ensure you have fragment-ktx dependency
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditorapp.R
import com.example.videoeditorapp.TimelineTemplateEditorActivity
import com.example.videoeditorapp.model.FavoriteManager
import com.example.videoeditorapp.model.timeline.TimelineProject
import com.example.videoeditorapp.ui.TimelineProjectsAdapter
import com.example.videoeditorapp.utils.ProjectManager
import com.example.videoeditorapp.utils.SearchViewModel // Your ViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SavedProjectsFragment : Fragment() {

        // 🍏 THE FIX: Connect to the SearchBox in the Activity via Shared ViewModel
        private val searchViewModel: SearchViewModel by activityViewModels()

        private lateinit var recyclerView: RecyclerView
        private lateinit var adapter: TimelineProjectsAdapter
        private lateinit var emptyView: TextView // Must be TextView to use .text
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
                                        val dialogBinding = com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(layoutInflater)

                                        dialogBinding.tvTitle.text = "Delete Project"
                                        dialogBinding.tvMessage.text = "Delete project '${project.name}'?"

                                        dialogBinding.btnPrimary.text = "Delete"
                                        dialogBinding.btnSecondary.text = "Cancel"

                                        val dialog = MaterialAlertDialogBuilder(requireContext())
                                            .setView(dialogBinding.root)
                                            .create()

                                        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                                        dialogBinding.btnPrimary.setOnClickListener {
                                            dialog.dismiss()
                                            ProjectManager.deleteProject(requireContext(), project.id)
                                            refreshList()
                                        }

                                        dialogBinding.btnSecondary.setOnClickListener {
                                            dialog.dismiss()
                                        }

                                        dialog.show()
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
                // If we want a global delete button, we'd need to talk to the Activity.
                // For now, let's just show a Toast or a temporary delete button.
                // Better: Let's use a snackbar with a Delete action.
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
                val dlg = com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(layoutInflater)

                dlg.tvTitle.text = "Delete Multiple Projects"
                dlg.tvMessage.text = "Are you sure you want to delete ${selected.size} projects?"

                dlg.btnPrimary.text = "Delete"
                dlg.btnSecondary.text = "Cancel"

                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setView(dlg.root)
                        .create()

                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                dlg.btnPrimary.setOnClickListener {
                        selected.forEach {
                                ProjectManager.deleteProject(requireContext(), it.id)
                        }
                        adapter.clearSelection()
                        refreshList()
                        dialog.dismiss()
                }

                dlg.btnSecondary.setOnClickListener {
                        adapter.clearSelection()
                        dialog.dismiss()
                }

                dialog.show()
        }
}
