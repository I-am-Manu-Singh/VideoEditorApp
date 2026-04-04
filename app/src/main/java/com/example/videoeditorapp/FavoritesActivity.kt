package com.example.videoeditorapp

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.videoeditorapp.databinding.ActivityMyFavoritesBinding
import com.example.videoeditorapp.ui.fragments.ExportsFragment
import com.example.videoeditorapp.ui.fragments.SavedProjectsFragment
import com.example.videoeditorapp.utils.SearchViewModel
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import com.google.android.material.tabs.TabLayoutMediator

class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyFavoritesBinding
    private val searchViewModel: SearchViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()

        // Glass Back Button Logic
        binding.btnBackContainer.setOnClickListener { finish() }

        // Setup ViewPager with Tabs
        val adapter = ViewPagerAdapter(this)
        binding.viewPagerFavorites.adapter = adapter

        TabLayoutMediator(binding.tabLayoutFavorites, binding.viewPagerFavorites) { tab, position ->
                    tab.text = if (position == 0) "PROJECTS" else "EXPORTS"
                }
                .attach()

        setupLandscapeRail()

        // 🍏 THE FIX: Connect Search Box to the Fragments
        binding.searchEditText.addTextChangedListener { text ->
            searchViewModel.updateQuery(text?.toString() ?: "")
        }

        binding.tabLayoutFavorites.getTabAt(0)?.select()
    }

    private fun setupLandscapeRail() {
        val isLandscape =
                resources.configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) return

        binding.btnTabProjects?.setOnClickListener {
            binding.viewPagerFavorites.currentItem = 0
            updateRailSelection(0)
        }
        binding.btnTabExports?.setOnClickListener {
            binding.viewPagerFavorites.currentItem = 1
            updateRailSelection(1)
        }

        binding.viewPagerFavorites.registerOnPageChangeCallback(
                object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        updateRailSelection(position)
                    }
                }
        )
    }

    private fun updateRailSelection(position: Int) {
        val cyan = resources.getColor(R.color.brand_primary, theme)
        val white50 = resources.getColor(R.color.white_50, theme)

        binding.btnTabProjects?.apply {
            setTextColor(if (position == 0) cyan else white50)
            iconTint =
                    android.content.res.ColorStateList.valueOf(if (position == 0) cyan else white50)
        }
        binding.btnTabExports?.apply {
            setTextColor(if (position == 1) cyan else white50)
            iconTint =
                    android.content.res.ColorStateList.valueOf(if (position == 1) cyan else white50)
        }
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }

    class ViewPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return if (position == 0) {
                SavedProjectsFragment.newInstance(onlyFavorites = true)
            } else {
                ExportsFragment.newInstance(onlyFavorites = true)
            }
        }
    }
}
