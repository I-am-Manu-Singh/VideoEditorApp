package com.example.videoeditorapp

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.videoeditorapp.databinding.ActivityMyProjectsBinding
import com.example.videoeditorapp.ui.fragments.ExportsFragment
import com.example.videoeditorapp.ui.fragments.SavedProjectsFragment
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import com.google.android.material.tabs.TabLayoutMediator

class MyProjectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyProjectsBinding
    private val searchViewModel: com.example.videoeditorapp.utils.SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMyProjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()

        // Setup Toolbar
        binding.btnBackContainer.setOnClickListener { finish() }

        // 🍏 THE FIX: Connect Search Box to the Fragments
        binding.searchEditText.addTextChangedListener(
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
                        searchViewModel.updateQuery(s?.toString() ?: "")
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        // Setup ViewPager with Tabs
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                    tab.text = if (position == 0) "Saved Exports" else "Saved Projects"
                }
                .attach()

        setupLandscapeRail()

        // Select first tab programmatically to trigger styling if needed, or rely on Default
        binding.tabLayout.getTabAt(0)?.select()

        searchViewModel.selectedCount.observe(this) { count ->
            if (count > 0) {
                binding.tvToolbarTitle.text = "$count ITEMS SELECTED"
            } else {
                binding.tvToolbarTitle.text = "PROJECT MANAGER"
            }
        }
    }

    private fun setupLandscapeRail() {
        val isLandscape =
                resources.configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) return

        binding.btnTabExports?.setOnClickListener {
            binding.viewPager.currentItem = 0
            updateRailSelection(0)
        }
        binding.btnTabProjects?.setOnClickListener {
            binding.viewPager.currentItem = 1
            updateRailSelection(1)
        }

        binding.viewPager.registerOnPageChangeCallback(
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

        binding.btnTabExports?.apply {
            setTextColor(if (position == 0) cyan else white50)
            iconTint =
                    android.content.res.ColorStateList.valueOf(if (position == 0) cyan else white50)
        }
        binding.btnTabProjects?.apply {
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
                ExportsFragment.newInstance(onlyFavorites = false)
            } else {
                SavedProjectsFragment.newInstance(onlyFavorites = false)
            }
        }
    }
}
