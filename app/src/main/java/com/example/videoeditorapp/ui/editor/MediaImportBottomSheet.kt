package com.example.videoeditorapp.ui.editor

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.example.videoeditorapp.R
import com.example.videoeditorapp.databinding.BottomSheetImportManagerBinding
import com.example.videoeditorapp.utils.ImportMediaRepository
import com.example.videoeditorapp.utils.ImportedMediaItem
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.example.videoeditorapp.ui.editor.pickers.EmojiPickerBottomSheet
import com.example.videoeditorapp.ui.editor.pickers.StickerPickerBottomSheet
import com.example.videoeditorapp.ui.editor.pickers.GifPickerBottomSheet
import androidx.recyclerview.widget.LinearLayoutManager
import android.Manifest
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore

import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class MediaImportBottomSheet(
    private val onMediaSelected: (Uri, com.example.videoeditorapp.ui.editor.AdditionMode) -> Unit
) : BottomSheetDialogFragment() {
    private val historyMedia =
    mutableListOf<ImportedMediaItem>()

private val deviceMedia =
    mutableListOf<ImportedMediaItem>()

    private var _binding: BottomSheetImportManagerBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var adapter: MediaImportAdapter
    private var currentFilter = "ALL"

    private fun getAdditionMode(): com.example.videoeditorapp.ui.editor.AdditionMode {
        return if (binding.switchOverlay.isChecked) 
            com.example.videoeditorapp.ui.editor.AdditionMode.OVERLAY 
            else com.example.videoeditorapp.ui.editor.AdditionMode.APPEND
    }

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            onMediaSelected(it, getAdditionMode())
            dismiss()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetImportManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { dialog ->
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                val metrics = resources.displayMetrics
                val screenHeight = metrics.heightPixels
                val desiredHeight = (screenHeight * 0.80).toInt() // 80% of screen height
                sheet.layoutParams.height = desiredHeight
                behavior.peekHeight = desiredHeight
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        if (hasMediaPermissions()) {
            loadDeviceMedia()
        } else {
            requestMediaPermissions()
        }
        loadData()
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener { dismiss() }
        
        adapter = MediaImportAdapter(emptyList()) { item ->
            onMediaSelected(Uri.parse(item.uri), getAdditionMode())
            dismiss()
        }
        binding.rvMediaList.adapter = adapter
        binding.rvMediaList.layoutManager = LinearLayoutManager(requireContext())
        
        val icons = listOf(
            R.drawable.ic_filter,      
            R.drawable.ic_camera,
            R.drawable.ic_image,
            R.drawable.ic_music,
            R.drawable.ic_emoji,
            R.drawable.ic_sticker,
            R.drawable.ic_gif,
            R.drawable.ic_first_page
        )
        for (i in 0 until binding.tabLayout.tabCount) {
            val tab = binding.tabLayout.getTabAt(i)
            if (tab != null && i < icons.size) {
                tab.setIcon(icons[i])
            }
        }
binding.rvMediaList.viewTreeObserver
    .addOnGlobalLayoutListener {

        android.util.Log.d(
            "MEDIA_DEBUG",
            "Recycler=${binding.rvMediaList.height}"
        )

        android.util.Log.d(
            "MEDIA_DEBUG",
            "Tabs=${binding.tabLayout.height}"
        )

        android.util.Log.d(
            "MEDIA_DEBUG",
            "Bottom=${binding.bottomActions.height}"
        )
    }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentFilter = when (tab?.position) {
                    0 -> "ALL"
                    1 -> "VIDEO"
                    2 -> "IMAGE"
                    3 -> "AUDIO"
                    4 -> "EMOJI"
                    5 -> "STICKER"
                    6 -> "GIF"
                    7 -> "HISTORY"
                    else -> "ALL"
                }
                filterList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnPickFile.setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }

        binding.etSearchMedia.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
private fun loadData() {

    historyMedia.clear()

    historyMedia.addAll(
        ImportMediaRepository.getHistory(requireContext())
    )

    filterList()
}
   private val permissionLauncher =
    registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadDeviceMedia()
    }
    private fun hasMediaPermissions(): Boolean {
        val context = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermissions() {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        )

    } else {

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
    }
}
private fun loadDeviceMedia() {

    binding.loadingProgress.visibility = View.VISIBLE

    lifecycleScope.launch(Dispatchers.IO) {

        val media =
            buildList {
                com.example.videoeditorapp.model.timeline.AssetStore.featuredAssets.forEach { asset ->
                    val typeStr = when (asset.type) {
                        com.example.videoeditorapp.model.timeline.AssetType.VIDEO_BROLL,
                        com.example.videoeditorapp.model.timeline.AssetType.VIDEO_OVERLAY -> "video"
                        com.example.videoeditorapp.model.timeline.AssetType.AUDIO_MUSIC,
                        com.example.videoeditorapp.model.timeline.AssetType.AUDIO_SFX -> "audio"
                        com.example.videoeditorapp.model.timeline.AssetType.IMAGE_STICKER -> "image"
                        else -> null
                    }
                    if (typeStr != null) {
                        add(com.example.videoeditorapp.utils.ImportedMediaItem(
                            uri = asset.url,
                            type = typeStr,
                            name = asset.title,
                            size = 1024L * 1024L
                        ))
                    }
                }
                addAll(loadVideos())
                addAll(loadImages())
                addAll(loadAudio())
            }

            withContext(Dispatchers.Main) {

            deviceMedia.clear()
            deviceMedia.addAll(media)

            android.util.Log.d(
                "MEDIA_DEBUG",
                "Loaded media count = ${deviceMedia.size}"
            )
            binding.loadingProgress.visibility = View.GONE
            filterList()
        }
    }
}
    private fun loadVideos(): List<ImportedMediaItem> {
    val items = mutableListOf<ImportedMediaItem>()
    try {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
        )

        requireContext().contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)
                val size = cursor.getLong(sizeIndex)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                items.add(
                    ImportedMediaItem(
                        uri = contentUri.toString(),
                        type = "video",
                        name = name,
                        size = size
                    )
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MEDIA_DEBUG", "Error loading videos", e)
    }
    return items
}

private fun loadAudio(): List<ImportedMediaItem> {
    val items = mutableListOf<ImportedMediaItem>()
    try {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE
        )
        requireContext().contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)
                val size = cursor.getLong(sizeIndex)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                items.add(
                    ImportedMediaItem(
                        uri = contentUri.toString(),
                        type = "audio",
                        name = name,
                        size = size
                    )
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MEDIA_DEBUG", "Error loading audio", e)
    }
    return items
}

private fun loadImages(): List<ImportedMediaItem> {
    val items = mutableListOf<ImportedMediaItem>()
    try {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )
        requireContext().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)
                val size = cursor.getLong(sizeIndex)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                items.add(
                    ImportedMediaItem(
                        uri = contentUri.toString(),
                        type = "image",
                        name = name,
                        size = size
                    )
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MEDIA_DEBUG", "Error loading images", e)
    }
    return items
}

 private fun filterList() {

    val query = binding.etSearchMedia.text
        .toString()
        .lowercase()

    binding.rvMediaList.recycledViewPool.clear()
    binding.rvMediaList.layoutManager = when (currentFilter) {
        "EMOJI" -> androidx.recyclerview.widget.GridLayoutManager(requireContext(), 7)
        "STICKER" -> androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
        "GIF" -> androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        else -> androidx.recyclerview.widget.LinearLayoutManager(requireContext())
    }

    val source = when (currentFilter) {
        "HISTORY" -> historyMedia
        "VIDEO" -> deviceMedia.filter { it.type.equals("video", true) }
        "IMAGE" -> deviceMedia.filter { it.type.equals("image", true) }
        "AUDIO" -> deviceMedia.filter { it.type.equals("audio", true) }
        "EMOJI" -> {
            com.example.videoeditorapp.model.timeline.EmojiLibrary.getAllEmojis().map { emoji ->
                ImportedMediaItem(
                    uri = "emoji://$emoji",
                    type = "emoji",
                    name = emoji,
                    size = 0L
                )
            }
        }
        "STICKER" -> {
            val stickerItems = mutableListOf<ImportedMediaItem>()
            com.example.videoeditorapp.model.timeline.StickerLibrary.getAllStickers().forEach { resId ->
                stickerItems.add(ImportedMediaItem(
                    uri = "res://$resId",
                    type = "sticker",
                    name = "Sticker",
                    size = 0L
                ))
            }
            com.example.videoeditorapp.model.timeline.StickerLibrary.remoteStickers.forEach { remote ->
                stickerItems.add(ImportedMediaItem(
                    uri = remote.url,
                    type = "sticker",
                    name = remote.category,
                    size = 0L
                ))
            }
            stickerItems
        }
        "GIF" -> {
            com.example.videoeditorapp.model.timeline.GifLibrary.trendingGifs.map { gif ->
                ImportedMediaItem(
                    uri = gif.gifUrl,
                    type = "gif",
                    name = gif.id,
                    size = 0L
                )
            }
        }
        "ALL" -> (historyMedia + deviceMedia).distinctBy { it.uri }
        else -> emptyList()
    }

    val filtered = source.filter {
        it.name.lowercase().contains(query)
    }

    android.util.Log.d(
        "MEDIA_DEBUG",
        "filter=$currentFilter device=${deviceMedia.size} history=${historyMedia.size} filtered=${filtered.size}"
    )

    adapter.submitList(filtered)
    binding.rvMediaList.post {

    android.util.Log.d(
        "MEDIA_DEBUG",
        """
        rvHeight=${binding.rvMediaList.height}
        rvWidth=${binding.rvMediaList.width}
        adapterCount=${adapter.itemCount}
        """.trimIndent()
    )
}
}

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
//    private fun getMediaCategory(item: ImportedMediaItem): String {

//     val name = item.name.lowercase()

//     return when {

//         name.endsWith(".mp4") ||
//         name.endsWith(".mov") ||
//         name.endsWith(".mkv") ||
//         name.endsWith(".webm") ->
//             "VIDEO"

//         name.endsWith(".png") ||
//         name.endsWith(".jpg") ||
//         name.endsWith(".jpeg") ||
//         name.endsWith(".webp") ->
//             "IMAGE"

//         name.endsWith(".mp3") ||
//         name.endsWith(".wav") ||
//         name.endsWith(".aac") ||
//         name.endsWith(".m4a") ->
//             "AUDIO"

//         name.endsWith(".gif") ->
//             "GIF"

//         item.type.equals("sticker", true) ->
//             "STICKER"

//         item.type.equals("emoji", true) ->
//             "EMOJI"

//         else ->
//             "OTHER"
//     }

