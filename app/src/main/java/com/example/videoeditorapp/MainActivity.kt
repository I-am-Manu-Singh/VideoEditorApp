package com.example.videoeditorapp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.videoeditorapp.databinding.ActivityMainBinding
import com.example.videoeditorapp.model.HighlightAdapter
import com.example.videoeditorapp.model.HighlightItem
import com.example.videoeditorapp.model.timeline.AssetStore
import com.example.videoeditorapp.ui.RecentProjectsAdapter
import com.example.videoeditorapp.utils.ProjectManager
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MainActivity : AppCompatActivity() {

        private lateinit var binding: ActivityMainBinding

        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)

                // 1. Theme setup
                val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                val themeMode =
                        prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                AppCompatDelegate.setDefaultNightMode(themeMode)

                // 2. Binding setup
                binding = ActivityMainBinding.inflate(layoutInflater)
                setContentView(binding.root)

                // 3. UI Initialization
                setupEditorEdgeToEdge()
                setupHomeSections()
                setupClicks()
                setupNavigationDrawer()

                // Initial animation
                binding.root.alpha = 0f
                binding.root.animate().alpha(1f).setDuration(500).start()
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun onResume() {
                super.onResume()
                loadRecentProjects()
        }

        private fun showProjectNameDialog(onProjectCreated: (String) -> Unit) {
                val dialogBinding =
                        com.example.videoeditorapp.databinding.DialogInputBinding.inflate(
                                layoutInflater
                        )
                dialogBinding.dialogTitle.text = "New Project"
                dialogBinding.etInput.hint = "Enter project name"
                dialogBinding.etInput.setText(
                        com.example.videoeditorapp.utils.NamingUtils.generateNewProjectName(this)
                )

                val dialog = MaterialAlertDialogBuilder(this).setView(dialogBinding.root).create()

                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
                dialogBinding.btnConfirm.setOnClickListener {
                        val name =
                                dialogBinding.etInput.text.toString().ifBlank {
                                        "Project_${System.currentTimeMillis()}"
                                }
                        dialog.dismiss()
                        onProjectCreated(name)
                }
                dialog.show()
        }
        private fun setupClicks() {
                binding.btnMenuHome.setOnClickListener {
                        binding.drawerLayout.openDrawer(GravityCompat.START)
                }

                binding.btnResumeProject?.setOnClickListener { resumeLastProject() }

                binding.btnNavFavorites?.setOnClickListener {
                        startActivity(Intent(this, FavoritesActivity::class.java))
                }

                binding.btnNavMyProjects?.setOnClickListener {
                        startActivity(Intent(this, MyProjectsActivity::class.java))
                }

                binding.btnHeroMenu?.setOnClickListener {
                        startActivity(Intent(this, UpgradeActivity::class.java))
                }

                binding.fabNewProject?.setOnClickListener {
                        showProjectNameDialog { name ->
                                val intent =
                                        Intent(this, TimelineTemplateEditorActivity::class.java)
                                                .apply {
                                                        putExtra("TEMPLATE_ID", "NEW_PROJECT")
                                                        putExtra("PROJECT_NAME", name)
                                                }
                                startActivity(intent)
                        }
                }

                binding.btnViewAllAssets?.setOnClickListener {
                        startActivity(
                                Intent(
                                        this,
                                        com.example.videoeditorapp.AssetStoreActivity::class.java
                                )
                        )
                }
                binding.btnViewAllTutorials?.setOnClickListener {
                        startActivity(
                                Intent(
                                        this,
                                        com.example.videoeditorapp.TutorialActivity::class.java
                                )
                        )
                }
        }

        private fun loadRecentProjects() {
                val projects = ProjectManager.listProjects(this).take(5)

                // 🍏 FIX: cardEmptyRecent was removed in the refactor or inside a different
                // container
                // Ensure you have an ID android:id="@+id/cardEmptyRecent" in your XML
                if (projects.isNotEmpty()) {
                        binding.rvRecentProjects.visibility = View.VISIBLE
                        // Use safe-call or check visibility if cardEmptyRecent exists
                        binding.cardEmptyRecent.visibility = View.GONE
                        binding.rvRecentProjects.adapter =
                                RecentProjectsAdapter(projects) { project ->
                                        val intent =
                                                Intent(
                                                                this,
                                                                TimelineTemplateEditorActivity::class
                                                                        .java
                                                        )
                                                        .apply {
                                                                putExtra("PROJECT_ID", project.id)
                                                                putExtra(
                                                                        "PROJECT_NAME",
                                                                        project.name
                                                                )
                                                        }
                                        startActivity(intent)
                                }
                        binding.rvRecentProjects.layoutManager =
                                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

                        binding.rvRecentProjects.isNestedScrollingEnabled = false
                        binding.rvRecentProjects.descendantFocusability =
                                ViewGroup.FOCUS_BLOCK_DESCENDANTS
                } else {
                        binding.rvRecentProjects.visibility = View.GONE
                        binding.cardEmptyRecent.visibility = View.VISIBLE
                }
                Log.d("DEBUG", "RECENT PROJECTS COUNT = ${projects.size}")
        }

        private fun setupHomeSections() {
                val masteryItems =
                        listOf(
                                HighlightItem(
                                        "t1",
                                        "Trim & Split",
                                        "BASIC",
                                        R.drawable.ic_content_cut
                                ),
                                HighlightItem("t2", "Adding Music", "AUDIO", R.drawable.ic_text),
                                HighlightItem("t3", "Chroma Key", "PRO", R.drawable.ic_magic),
                                HighlightItem("t4", "Export in 4K", "TIPS", R.drawable.ic_add)
                        )
                binding.rvTutorials.layoutManager =
                        LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
                binding.rvTutorials.adapter =
                        HighlightAdapter(masteryItems) { item ->
                                val intent =
                                        Intent(this, TutorialDetailActivity::class.java).apply {
                                                putExtra("TUTORIAL_TITLE", item.title)
                                                putExtra("TUTORIAL_TAG", item.tag)
                                        }
                                startActivity(intent)
                        }

                // Map real assets from AssetStore to HighlightItems for "Trending" using popularity
                // ranking
                val recommendedAssets: List<com.example.videoeditorapp.model.timeline.AssetItem> =
                        com.example.videoeditorapp.model.timeline.AssetStore.getRecommendedAssets(4)

                val trendingItems =
                        recommendedAssets.map { asset ->
                                HighlightItem(
                                        id = asset.title,
                                        title = asset.title,
                                        tag = asset.category,
                                        imageRes = asset.thumbnailResId ?: R.drawable.ic_magic
                                )
                        }

                binding.rvTrendingAssets.layoutManager =
                        GridLayoutManager(this, 1, GridLayoutManager.HORIZONTAL, false)
                binding.rvTrendingAssets.adapter =
                        HighlightAdapter(trendingItems) { item ->
                                val originalAsset =
                                        com.example.videoeditorapp.model.timeline.AssetStore
                                                .featuredAssets
                                                .find { it.title == item.title }
                                if (originalAsset != null) {
                                        val intent =
                                                Intent(this, AssetDetailActivity::class.java)
                                                        .apply { putExtra("ASSET", originalAsset) }
                                        startActivity(intent)
                                } else {
                                        startActivity(Intent(this, AssetStoreActivity::class.java))
                                }
                        }
        }

        private fun setupNavigationDrawer() {
                // New Custom Layout Logic
                // Use binding directly as these IDs are now compliant in valid XML
                binding.navBtnNewProject?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        binding.fabNewProject?.performClick()
                }

                binding.navBtnFavorites?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        startActivity(Intent(this, FavoritesActivity::class.java))
                }

                binding.navBtnProjects?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        startActivity(Intent(this, MyProjectsActivity::class.java))
                }

                binding.navBtnMarketplace?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        startActivity(
                                Intent(
                                        this,
                                        com.example.videoeditorapp.AssetStoreActivity::class.java
                                )
                        )
                }

                binding.navBtnTutorials?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        btnTutorialClick()
                }

                binding.navBtnHelp?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        btnHelpClick()
                }

                binding.navBtnUpgrade?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        btnUpgradeClick()
                }

                binding.navBtnAppInfo?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        startActivity(Intent(this, AppInfoActivity::class.java))
                }

                binding.layoutNavSettings?.setOnClickListener {
                        (binding.drawerLayout as? androidx.drawerlayout.widget.DrawerLayout)
                                ?.closeDrawer(GravityCompat.START)
                        startActivity(Intent(this, SettingsActivity::class.java))
                }
        }

        private fun resumeLastProject() {
                val projects = ProjectManager.listProjects(this)
                if (projects.isNotEmpty()) {
                        val lastProject = projects.sortedByDescending { it.lastModified }.first()
                        val projectFile = File(filesDir, "projects/${lastProject.id}.json")
                        val intent =
                                Intent(this, TimelineTemplateEditorActivity::class.java).apply {
                                        putExtra("PROJECT_PATH", projectFile.absolutePath)
                                }
                        startActivity(intent)
                } else {
                        Snackbar.make(
                                        binding.root,
                                        "No recent projects found",
                                        Snackbar.LENGTH_SHORT
                                )
                                .show()
                }
        }

        private fun btnTutorialClick() = startActivity(Intent(this, TutorialActivity::class.java))
        private fun btnHelpClick() = startActivity(Intent(this, HelpActivity::class.java))
        private fun btnUpgradeClick() = startActivity(Intent(this, UpgradeActivity::class.java))
}
