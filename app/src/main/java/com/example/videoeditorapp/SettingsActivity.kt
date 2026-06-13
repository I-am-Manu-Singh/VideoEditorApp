package com.example.videoeditorapp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.example.videoeditorapp.databinding.ActivitySettingsBinding
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import com.example.videoeditorapp.utils.AppDialog
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        // setupEditorEdgeToEdge handles the bars now
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupListeners()
        // displayVersionInfo() // Uncomment if you add tvVersion back to XML
        setupEdgeToEdge()
    }

    private fun setupEdgeToEdge() {
        // Targeted the AppBarLayout for edge-to-edge transparency
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }

    private fun setupToolbar() {
        // Correctly reference the custom back button container
        binding.btnBackContainer.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun loadSettings() {
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES)
        when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.rgThemeSelection.check(R.id.btnThemeLight)
            else -> binding.rgThemeSelection.check(R.id.btnThemeDark)
        }
        val defaultAspect = prefs.getString("default_aspect", "16:9")

        when (defaultAspect) {
            "9:16" -> binding.rgDefaultAspect?.check(R.id.btnAspect916)
            "16:9" -> binding.rgDefaultAspect?.check(R.id.btnAspect169)
            "1:1" -> binding.rgDefaultAspect?.check(R.id.btnAspect11)
            "Original" -> binding.rgDefaultAspect?.check(R.id.btnAspectOriginal)
        }
        val defaultRes = prefs.getString("default_res", "1080")
        when (defaultRes) {
            "720" -> binding.rgDefaultResolution.check(R.id.btnDefault720)
            "1080" -> binding.rgDefaultResolution.check(R.id.btnDefault1080)
            "2160" -> binding.rgDefaultResolution.check(R.id.btnDefault4K)
            "ORIGINAL" -> binding.rgDefaultResolution.check(R.id.btnDefaultOriginal)
            else -> binding.rgDefaultResolution.check(R.id.btnDefault1080)
        }

        // Auto-save interval
        val autoSave = prefs.getString("auto_save", "1m")
        when (autoSave) {
            "30s" -> binding.rgAutoSave.check(R.id.btnAutoSave30s)
            "1m" -> binding.rgAutoSave.check(R.id.btnAutoSave1m)
            "2m" -> binding.rgAutoSave.check(R.id.btnAutoSave2m)
            "off" -> binding.rgAutoSave.check(R.id.btnAutoSaveOff)
            else -> binding.rgAutoSave.check(R.id.btnAutoSave1m)
        }

        // Export quality
        val quality = prefs.getString("export_quality", "high")
        when (quality) {
            "low" -> binding.rgExportQuality.check(R.id.btnQualityLow)
            "medium" -> binding.rgExportQuality.check(R.id.btnQualityMedium)
            "high" -> binding.rgExportQuality.check(R.id.btnQualityHigh)
            "ultra" -> binding.rgExportQuality.check(R.id.btnQualityUltra)
            else -> binding.rgExportQuality.check(R.id.btnQualityHigh)
        }

        // Preview quality
        val previewQuality = prefs.getString("preview_quality", "medium")
        when (previewQuality) {
            "low" -> binding.rgPreviewQuality?.check(R.id.btnPreviewLow)
            "medium" -> binding.rgPreviewQuality?.check(R.id.btnPreviewMedium)
            "high" -> binding.rgPreviewQuality?.check(R.id.btnPreviewHigh)
            else -> binding.rgPreviewQuality?.check(R.id.btnPreviewMedium)
        }

        // Default Codec
        val defaultCodec = prefs.getString("default_codec", "h264_mediacodec")
        when (defaultCodec) {
            "h264_mediacodec" -> binding.codecSelectionGroup?.check(R.id.btnSettingsCodecH264)
            "hevc_mediacodec" -> binding.codecSelectionGroup?.check(R.id.btnSettingsCodecH265)
            else -> binding.codecSelectionGroup?.check(R.id.btnSettingsCodecH264)
        }

        // Hardware acceleration
        binding.switchHardwareAccel?.isChecked = prefs.getBoolean("hardware_accel", true)

        // Watermark
        binding.switchWatermark?.isChecked = prefs.getBoolean("watermark", false)

        // Audio Quality
        val audioQuality = prefs.getString("default_audio_quality", "192K")
        when (audioQuality) {
            "128K" -> binding.rgAudioQuality?.check(R.id.btnAudio128)
            "192K" -> binding.rgAudioQuality?.check(R.id.btnAudio192)
            "320K" -> binding.rgAudioQuality?.check(R.id.btnAudio320)
            else -> binding.rgAudioQuality?.check(R.id.btnAudio192)
        }

        updateCodecWarning(defaultCodec ?: "h264_mediacodec")
    }

    private fun updateCodecWarning(codec: String) {
        val h265Supported = isCodecSupported("video/hevc")
        if (codec == "hevc_mediacodec" && !h265Supported) {
            binding.tvCodecWarning.visibility = android.view.View.VISIBLE
            binding.tvCodecWarning.text =
                    "CRITICAL: H.265 (HEVC) is NOT detected on this device. Export may fail or use software fallback."
            binding.tvCodecWarning.setTextColor(Color.parseColor("#FF4444"))
        } else if (codec == "hevc_mediacodec") {
            binding.tvCodecWarning.visibility = android.view.View.VISIBLE
            binding.tvCodecWarning.text =
                    "H.265 provides better quality at smaller size but may not be supported on all playback devices."
            binding.tvCodecWarning.setTextColor(resources.getColor(R.color.white_50, theme))
        } else {
            binding.tvCodecWarning.visibility = android.view.View.GONE
        }
    }

    private fun isCodecSupported(mimeType: String): Boolean {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) {
                for (type in info.supportedTypes) {
                    if (type.equals(mimeType, ignoreCase = true)) return true
                }
            }
        }
        return false
    }
    private fun setupListeners() {

        binding.rgDefaultAspect?.addOnButtonCheckedListener { _, checkedId, isChecked ->

            if (!isChecked) return@addOnButtonCheckedListener

            val aspect = when (checkedId) {
                R.id.btnAspect916 -> "9:16"
                R.id.btnAspect11 -> "1:1"
                R.id.btnAspectOriginal -> "Original"
                else -> "16:9"
            }

            prefs.edit {
                putString("default_aspect", aspect)
            }
        }
        // Theme selection
        binding.rgThemeSelection.addOnButtonCheckedListener {
                group: com.google.android.material.button.MaterialButtonToggleGroup,
                checkedId: Int,
                isChecked: Boolean ->

            if (isChecked) {
                val mode =
                        if (checkedId == R.id.btnThemeLight) AppCompatDelegate.MODE_NIGHT_NO
                        else AppCompatDelegate.MODE_NIGHT_YES

                if (AppCompatDelegate.getDefaultNightMode() != mode) {
                    prefs.edit().putInt("theme_mode", mode).apply()
                    AppCompatDelegate.setDefaultNightMode(mode)

                    // 🍏 Smooth Transition:
                    // This prevents the "flash" by giving the system
                    // a moment to stabilize the theme colors.
                    window.setWindowAnimations(android.R.style.Animation_Toast)
                    recreate()
                }
            }
        }

        // Default resolution
        binding.rgDefaultResolution.addOnButtonCheckedListener {
                group: com.google.android.material.button.MaterialButtonToggleGroup,
                checkedId: Int,
                isChecked: Boolean ->
            if (isChecked) {
                val res =
                        when (checkedId) {
                            R.id.btnDefault720 -> "720"
                            R.id.btnDefault1080 -> "1080"
                            R.id.btnDefault4K -> "2160"
                            R.id.btnDefaultOriginal -> "ORIGINAL"
                            else -> "1080"
                        }
                prefs.edit { putString("default_res", res) }
            }
        }

        // Auto-save interval
        binding.rgAutoSave.addOnButtonCheckedListener {
                group: com.google.android.material.button.MaterialButtonToggleGroup,
                checkedId: Int,
                isChecked: Boolean ->
            if (isChecked) {
                val interval =
                        when (checkedId) {
                            R.id.btnAutoSave30s -> "30s"
                            R.id.btnAutoSave1m -> "1m"
                            R.id.btnAutoSave2m -> "2m"
                            R.id.btnAutoSaveOff -> "off"
                            else -> "1m"
                        }
                prefs.edit { putString("auto_save", interval) }
            }
        }

        // Export quality
        binding.rgExportQuality.addOnButtonCheckedListener {
                group: com.google.android.material.button.MaterialButtonToggleGroup,
                checkedId: Int,
                isChecked: Boolean ->
            if (isChecked) {
                val quality =
                        when (checkedId) {
                            R.id.btnQualityLow -> "low"
                            R.id.btnQualityMedium -> "medium"
                            R.id.btnQualityHigh -> "high"
                            R.id.btnQualityUltra -> "ultra"
                            else -> "high"
                        }
                prefs.edit { putString("export_quality", quality) }
            }
        }

        // Preview quality
        binding.rgPreviewQuality?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val quality =
                        when (checkedId) {
                            R.id.btnPreviewLow -> "low"
                            R.id.btnPreviewMedium -> "medium"
                            R.id.btnPreviewHigh -> "high"
                            else -> "medium"
                        }
                prefs.edit { putString("preview_quality", quality) }
            }
        }

        // Video Codec
        binding.codecSelectionGroup.addOnButtonCheckedListener {
                group: com.google.android.material.button.MaterialButtonToggleGroup,
                checkedId: Int,
                isChecked: Boolean ->
            if (isChecked) {
                val codec =
                        when (checkedId) {
                            R.id.btnSettingsCodecH264 -> "h264_mediacodec"
                            R.id.btnSettingsCodecH265 -> "hevc_mediacodec"
                            else -> "h264_mediacodec"
                        }
                prefs.edit { putString("default_codec", codec) }
                updateCodecWarning(codec)
            }
        }

        // Hardware acceleration
        binding.switchHardwareAccel?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("hardware_accel", isChecked) }
        }

        // Watermark
        binding.switchWatermark?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("watermark", isChecked) }
        }

        // Audio Quality
        binding.rgAudioQuality?.addOnButtonCheckedListener {
                group: com.google.android.material.button.MaterialButtonToggleGroup,
                checkedId: Int,
                isChecked: Boolean ->
            if (isChecked) {
                val quality =
                        when (checkedId) {
                            R.id.btnAudio128 -> "128K"
                            R.id.btnAudio192 -> "192K"
                            R.id.btnAudio320 -> "320K"
                            else -> "192K"
                        }
                prefs.edit { putString("default_audio_quality", quality) }
            }
        }

        // Storage Navigation
        binding.btnStorage.setOnClickListener {
            startActivity(android.content.Intent(this, StorageActivity::class.java))
        }

        // Wipe Data
        binding.btnClearHistory.setOnClickListener { showClearHistoryDialog() }

        // App Info
        binding.btnAppInfo.setOnClickListener {
            startActivity(android.content.Intent(this, AppInfoActivity::class.java))
        }
    }

   private fun showClearHistoryDialog() {

     AppDialog.showDelete(
         context = this,
         title = "Clear History",
         message = "Are you sure you want to delete all exported videos and project history?",
         iconRes = R.drawable.ic_delete,
         onDelete = {
             clearProjectFiles()
         }
     )
}
private fun clearProjectFiles() {

    val exportsDir = File(getExternalFilesDir(null), "Exports")

    if (exportsDir.exists()) {
        exportsDir.listFiles()?.forEach {
            it.delete()
        }
    }

    getSharedPreferences(
        "favorites",
        Context.MODE_PRIVATE
    ).edit {
        clear()
    }

    AppDialog.showInfo(
        context = this,
        title = "History Cleared",
        message = "History cleared successfully.",
        iconRes = R.drawable.ic_info
    )
}
}
