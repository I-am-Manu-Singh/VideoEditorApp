package com.example.videoeditorapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.videoeditorapp.databinding.ActivityAssetStoreBinding
import com.example.videoeditorapp.model.timeline.AssetItem
import com.example.videoeditorapp.model.timeline.AssetStore
import com.example.videoeditorapp.model.timeline.RemoteAssetManager
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class AssetStoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssetStoreBinding
    private lateinit var adapter: AssetAdapter
    private var currentFilter: String = "ALL"

    private val assetDetailLauncher =
            registerForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts
                            .StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    setResult(RESULT_OK, result.data)
                    finish()
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssetStoreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTabs()
        setupRecyclerView()

        setupEdgeToEdge()
        setupSearch()
        filterAssets("ALL")
    }

    private fun setupSearch() {
        binding.etSearch?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAssets(currentFilter, s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }

    private fun setupToolbar() {
        binding.btnBackContainer.setOnClickListener { finish() }
    }

    private fun setupTabs() {
        // Check if we're in landscape mode with button-based navigation
        val isLandscape =
                resources.configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            // Wire up vertical navigation rail buttons
            binding.btnCategoryTemplates?.setOnClickListener {
                selectCategory("ALL", it as com.google.android.material.button.MaterialButton)
            }
            binding.btnCategoryMusic?.setOnClickListener {
                selectCategory("MUSIC", it as com.google.android.material.button.MaterialButton)
            }
            binding.btnCategoryEffects?.setOnClickListener {
                selectCategory("EFFECTS", it as com.google.android.material.button.MaterialButton)
            }
            binding.btnCategoryStickers?.setOnClickListener {
                selectCategory("STICKERS", it as com.google.android.material.button.MaterialButton)
            }
            binding.btnCategoryFonts?.setOnClickListener {
                selectCategory("FONTS", it as com.google.android.material.button.MaterialButton)
            }

            // Select first category by default
            binding.btnCategoryTemplates?.let { selectCategory("ALL", it) }
        } else {
            // Portrait mode: use TabLayout
            val categories = listOf("ALL", "B-ROLL", "EFFECTS", "MUSIC", "SFX", "STICKERS")
            binding.tabCategories?.let { tabs ->
                categories.forEach { cat -> tabs.addTab(tabs.newTab().setText(cat)) }
            }

            binding.tabCategories?.addOnTabSelectedListener(
                    object : TabLayout.OnTabSelectedListener {
                        override fun onTabSelected(tab: TabLayout.Tab?) {
                            filterAssets(tab?.text?.toString() ?: "ALL")
                        }
                        override fun onTabUnselected(tab: TabLayout.Tab?) {}
                        override fun onTabReselected(tab: TabLayout.Tab?) {}
                    }
            )
        }
    }

    private var selectedButton: com.google.android.material.button.MaterialButton? = null

    private fun selectCategory(
            category: String,
            button: com.google.android.material.button.MaterialButton
    ) {
        // Deselect previous button
        selectedButton?.apply {
            setTextColor(getColor(R.color.white_50))
            iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.white_50))
        }

        // Select new button
        button.apply {
            setTextColor(getColor(R.color.brand_primary))
            iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_primary))
        }
        selectedButton = button

        filterAssets(category)
    }

    private fun setupRecyclerView() {
        adapter =
                AssetAdapter(
                        onDownloadClick = { asset -> downloadAsset(asset) },
                        onItemClick = { asset ->
                            val intent =
                                    android.content.Intent(this, AssetDetailActivity::class.java)
                                            .apply { putExtra("ASSET", asset) }
                            assetDetailLauncher.launch(intent)
                        }
                )

        // Use 4 columns in landscape, 2 in portrait
        val spanCount =
                if (resources.configuration.orientation ==
                                android.content.res.Configuration.ORIENTATION_LANDSCAPE
                )
                        4
                else 2
        binding.rvAssets.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvAssets.adapter = adapter
    }

    private fun filterAssets(category: String, query: String = "") {
        currentFilter = category
        val filtered = AssetStore.featuredAssets.filter { asset ->
            val matchesCategory = if (category == "ALL") true 
                                 else (asset.type.name.contains(category) || asset.category == category)
            val matchesQuery = if (query.isEmpty()) true
                               else (asset.title.contains(query, ignoreCase = true) || asset.category.contains(query, ignoreCase = true))
            
            matchesCategory && matchesQuery
        }
        adapter.submitList(filtered)
    }

    private fun downloadAsset(asset: AssetItem) {
        if (asset.isDownloaded) return
        if (asset.isPremium) {
            val prefs = getSharedPreferences("VideoEditorPrefs", android.content.Context.MODE_PRIVATE)
            val isPro = prefs.getBoolean("IS_PRO", false)
            if (!isPro) {
                val intent = android.content.Intent(this, UpgradeActivity::class.java)
                startActivity(intent)
                return
            }
        }

        lifecycleScope.launch {
            Toast.makeText(
                            this@AssetStoreActivity,
                            "Downloading ${asset.title}...",
                            Toast.LENGTH_SHORT
                    )
                    .show()
            val path = RemoteAssetManager.downloadAsset(this@AssetStoreActivity, asset.url)
            if (path != null) {
                asset.isDownloaded = true
                asset.localPath = path
                adapter.notifyDataSetChanged()
                Toast.makeText(this@AssetStoreActivity, "Download Complete!", Toast.LENGTH_SHORT)
                        .show()
            } else {
                Toast.makeText(this@AssetStoreActivity, "Download Failed", Toast.LENGTH_SHORT)
                        .show()
            }
        }
    }
}
