package com.example.videoeditorapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.media3.common.util.UnstableApi
import com.example.videoeditorapp.databinding.ActivityMainBinding
import com.example.videoeditorapp.model.HighlightAdapter
import com.example.videoeditorapp.model.timeline.AssetStore
import com.example.videoeditorapp.model.timeline.TimelineProject
import com.example.videoeditorapp.ui.RecentProjectsAdapter
import com.example.videoeditorapp.utils.HomeContentRepository

@UnstableApi
class MainActivity : AppCompatActivity() {

        private lateinit var binding: ActivityMainBinding

        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                binding = ActivityMainBinding.inflate(layoutInflater)
                setContentView(binding.root)

                setupHomeSections()
                setupNavigationDrawer()
                setupClickListeners()
                
                // Load recent projects
                refreshRecentProjects()
        }

        override fun onResume() {
            super.onResume()
            refreshRecentProjects()
        }

        override fun onPause() {
            super.onPause()
        }

        private fun refreshRecentProjects() {
            val projects = com.example.videoeditorapp.utils.ProjectManager.listProjects(this)
            setupRecentProjects(projects.sortedByDescending { it.lastModified })
        }

        private fun setupClickListeners() {
            // Home Screen Main Nav
            val favListener = View.OnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
            val projectsListener = View.OnClickListener { startActivity(Intent(this, MyProjectsActivity::class.java)) }

            binding.btnNavFavorites.setOnClickListener(favListener)
            binding.btnNavMyProjects.setOnClickListener(projectsListener)
            
            // Check for landscape-specific IDs (they might be inside a different container or renamed)
            // Using findViewByID if necessary or just relying on view binding if they have same IDs

            binding.btnMenuHome.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
            binding.fabNewProject.setOnClickListener { createNewProject() }
            
            binding.btnResumeProject.setOnClickListener {
                val lastProject = com.example.videoeditorapp.utils.ProjectManager.listProjects(this)
                    .maxByOrNull { it.lastModified }
                if (lastProject != null) {
                    val intent = Intent(this, TimelineTemplateEditorActivity::class.java)
                    intent.putExtra("PROJECT_ID", lastProject.id)
                    intent.putExtra("PROJECT_NAME", lastProject.name)
                    intent.putExtra("TEMPLATE_ID", lastProject.templateId)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "No recent projects found", Toast.LENGTH_SHORT).show()
                }
            }

            binding.btnProfile.setOnClickListener {
                 startActivity(Intent(this, com.example.videoeditorapp.ui.profile.CreatorProfileActivity::class.java))
            }
            
            binding.btnViewAllTutorials.setOnClickListener { startActivity(Intent(this, TutorialActivity::class.java)) }
            binding.btnViewAllAssets.setOnClickListener { startActivity(Intent(this, AssetStoreActivity::class.java)) }
        }

        private fun createNewProject() {
            val name = com.example.videoeditorapp.utils.NamingUtils.generateNewProjectName(this)
            val newProject = TimelineProject(name = name)
            com.example.videoeditorapp.utils.ProjectManager.saveProject(this, newProject)
            startActivity(Intent(this, TimelineTemplateEditorActivity::class.java).apply {
                putExtra("PROJECT_ID", newProject.id)
                putExtra("PROJECT_NAME", newProject.name)
            })
        }

        private fun setupRecentProjects(projects: List<TimelineProject>) {
                if (projects.isNotEmpty()) {
                        binding.rvRecentProjects.visibility = View.VISIBLE
                        binding.cardEmptyRecent.visibility = View.GONE
                        binding.rvRecentProjects.adapter = RecentProjectsAdapter(projects) { p ->
                                val intent = Intent(this, TimelineTemplateEditorActivity::class.java).apply {
                                        putExtra("PROJECT_ID", p.id)
                                        putExtra("PROJECT_NAME", p.name)
                                }
                                startActivity(intent)
                        }
                        binding.rvRecentProjects.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
                } else {
                        binding.rvRecentProjects.visibility = View.GONE
                        binding.cardEmptyRecent.visibility = View.VISIBLE
                }
        }

    private fun setupHomeSections() {

        val tutorials =
            HomeContentRepository
                .getFeaturedTutorials()

        binding.rvTutorials.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        binding.rvTutorials.adapter =
            HighlightAdapter(tutorials.take(5)) { item ->
                startActivity(
                    Intent(this, TutorialDetailActivity::class.java).apply {
                        putExtra("TUTORIAL_TITLE", item.title)
                        putExtra("TUTORIAL_TAG", item.tag)
                    }
                )
            }

        val trendingAssets =
            HomeContentRepository
                .getTrendingAssets()

        binding.rvTrendingAssets.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        binding.rvTrendingAssets.setHasFixedSize(false)
        binding.rvTrendingAssets.isNestedScrollingEnabled = false

        binding.rvTrendingAssets.adapter =
            HighlightAdapter(trendingAssets) { item ->

                val asset =
                    AssetStore.featuredAssets.find {
                        it.id == item.id
                    }

                asset?.let {
                    startActivity(
                        Intent(this, AssetDetailActivity::class.java)
                            .putExtra("ASSET", it)
                    )
                }
            }
    }

        private fun setupNavigationDrawer() {
                binding.navDrawer.navHeader.root.setOnClickListener {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                        startActivity(Intent(this, ProfileActivity::class.java))
                }

                binding.navDrawer.navBtnNewProject.setOnClickListener {
                        closeAndRun { createNewProject() }
                }

                binding.navDrawer.navBtnFavorites.setOnClickListener {
                        closeAndRun { startActivity(Intent(this, FavoritesActivity::class.java)) }
                }

                binding.navDrawer.navBtnProjects.setOnClickListener {
                        closeAndRun { startActivity(Intent(this, MyProjectsActivity::class.java)) }
                }

                binding.navDrawer.navBtnMarketplace.setOnClickListener {
                        closeAndRun { startActivity(Intent(this, AssetStoreActivity::class.java)) }
                }

                binding.navDrawer.navBtnTutorials.setOnClickListener {
                        closeAndRun { startActivity(Intent(this, TutorialActivity::class.java)) }
                }

                binding.navDrawer.navBtnHelp.setOnClickListener {
                        closeAndRun { startActivity(Intent(this, HelpActivity::class.java)) }
                }

                binding.navDrawer.navBtnAppInfo.setOnClickListener {
                        closeAndRun { startActivity(Intent(this, AppInfoActivity::class.java)) }
                }

                binding.navDrawer.layoutNavSettings.setOnClickListener {
                        closeAndRun { startActivity(Intent(this, SettingsActivity::class.java)) }
                }

                binding.navDrawer.navBtnUpgrade.setOnClickListener {
                        closeAndRun { startActivity(Intent(this, UpgradeActivity::class.java)) }
                }
        }

        private fun closeAndRun(action: () -> Unit) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            action()
        }
}
