package com.example.videoeditorapp.ui.editor

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.videoeditorapp.R
import com.example.videoeditorapp.databinding.*
import com.example.videoeditorapp.model.timeline.*
import com.example.videoeditorapp.ui.editor.pickers.*
import com.example.videoeditorapp.utils.ViewUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.videoeditorapp.utils.AppDialog
import java.io.File

class EditorDialogHelper(
    private val activity: AppCompatActivity,

    private val fragmentManager: FragmentManager,

    private val layoutInflater: LayoutInflater,

    private val onProjectChanged: () -> Unit,

    private val onHistorySaveRequested: () -> Unit,

    private val calculateExportDimensions: (String, String) -> Pair<Int, Int>

) {

    fun showEmojiPicker(onEmojiSelected: (String) -> Unit) {
        EmojiPickerBottomSheet { emoji -> onEmojiSelected(emoji) }
            .show(fragmentManager, "emoji_picker")
    }

    fun showStickerPicker(onStickerSelected: (String) -> Unit) {
        StickerPickerBottomSheet { path -> onStickerSelected(path) }
            .show(fragmentManager, "sticker_picker")
    }

    fun showGifPicker(onGifSelected: (String) -> Unit) {
        GifPickerBottomSheet { url -> onGifSelected(url) }
            .show(fragmentManager, "gif_picker")
    }

    fun showFiltersPicker(clip: TimelineClip, onFilterSelected: (String, Float) -> Unit) {
        val options = listOf("VINTAGE", "CYRPUNK", "MONO", "DREAM").map { type ->
            EditorOption(type, R.drawable.ic_filter, type.lowercase().replaceFirstChar { it.uppercase() }, clip.effects.find { it.type == type }?.intensity ?: 0f)
        }
        showUnifiedEditor("Cinematic Filters", options) { id, value -> onFilterSelected(id, value) }
    }

    fun showEffectsPicker(clip: TimelineClip, onEffectSelected: (String, Float) -> Unit) {
        val options = listOf("BLUR", "GLITCH", "PIXELATE", "SHAKE").map { type ->
            EditorOption(type, R.drawable.ic_magic, type.lowercase().replaceFirstChar { it.uppercase() }, clip.effects.find { it.type == type }?.intensity ?: 0f)
        }
        showUnifiedEditor("Special Effects", options) { id, value -> onEffectSelected(id, value) }
    }

    fun showUnifiedEditor(
        title: String,
        options: List<EditorOption>,
        onValueChange: (String, Float) -> Unit
    ) {
        val binding = DialogUnifiedEditorBinding.inflate(layoutInflater)
        binding.rvOptions.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        
        val dialog = BottomSheetDialog(activity, R.style.TransparentBottomSheetDialog)
        dialog.setContentView(binding.root)
        // Ensure background is transparent for the custom bg_sheet_dark to show correctly
        (binding.root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)

        binding.tvDialogTitle.text = title.uppercase()
        binding.btnClose.setOnClickListener { dialog.dismiss() }

        var currentOption = options.first()

        fun updateControl(option: EditorOption) {
            currentOption = option
            binding.tvSliderLabel.text = option.label.uppercase()
            
            // 🛡️ CRASH PROTECTION: Standardize slider range calculation
            val min = option.minValue
            val max = if (option.maxValue <= min) min + 100f else option.maxValue
            val current = option.value.coerceIn(min, max)
            
            binding.sliderControl.apply {
                valueFrom = min
                valueTo = max
                value = current
            }
            
            binding.tvSliderValue.text = when (option.id) {
                "speed" -> String.format("%.2fx", current)
                "volume" -> String.format("%.0f%%", current * 100)
                "quality", "resolution", "fps" -> String.format("%.0f", current)
                else -> String.format("%.1f", current)
            }
        }

        val adapter = EditorOptionsAdapter(options) { selected ->
            updateControl(selected)
            if (selected.type == OptionType.ACTION) onValueChange(selected.id, 0f)
        }
        binding.rvOptions.adapter = adapter
        updateControl(currentOption)

        binding.sliderControl.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                try {
                    currentOption.let { opt ->
                        opt.value = value
                        binding.tvSliderValue.text = when (opt.id) {
                            "speed" -> String.format("%.2fx", value)
                            "volume" -> String.format("%.0f%%", value * 100)
                            else -> String.format("%.1f", value)
                        }
                        onValueChange(opt.id, value)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EditorDialog", "Slider sync failure", e)
                }
            }
        }

        binding.btnDone.setOnClickListener {
            onHistorySaveRequested()
            dialog.dismiss()
            onProjectChanged()
        }
        dialog.show()
    }
fun showClipInfoDialog(clip: TimelineClip) {

    val file = File(clip.filePath)

    val message = buildString {
        append("Name: ${file.name}\n")
        append("Path: ${clip.filePath}\n")
        append("Start: ${ViewUtils.formatTime(clip.startTimeMs)}\n")
        append("Duration: ${ViewUtils.formatTime(clip.durationMs)}\n")
        append("Speed: ${clip.playbackSpeed}x\n")
        append("Volume: ${(clip.audioVolume * 100).toInt()}%\n")

        if (clip.effects.isNotEmpty()) {
            append("\nEffects:\n")
            clip.effects.forEach {
                append("• ${it.type} (${(it.intensity * 100).toInt()}%)\n")
            }
        }
    }

    AppDialog.showInfo(
        context = activity,
        title = "Clip Information",
        message = message,
        iconRes = R.drawable.ic_info
    )
}

    fun showProjectSettingsDialog(project: TimelineProject) {
        val binding = DialogProjectSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(
            activity,
            R.style.TransparentBottomSheetDialog
        )

        dialog.setContentView(binding.root)
        val prefs =
            activity.getSharedPreferences(
                "app_settings",
                Context.MODE_PRIVATE
            )

        val defaultRes =
            prefs.getString(
                "default_res",
                "1080"
            )

        val defaultAspect =
            prefs.getString(
                "default_aspect",
                "16:9"
            )
        val currentRes =
            project.metadata["EXPORT_RES"]
                ?: defaultRes

        when (currentRes) {
            "720", "720p" -> binding.toggleResolution.check(R.id.btnRes720)
            "2160", "4K" -> binding.toggleResolution.check(R.id.btnRes4K)
            "ORIGINAL", "Original" -> binding.toggleResolution.check(R.id.btnResOriginal)
            else -> binding.toggleResolution.check(R.id.btnRes1080)
        }
        when (project.aspectWidth) {
            9 if project.aspectHeight == 16 ->
                binding.toggleAspectRatio.check(R.id.btnRatio916)

            16 if project.aspectHeight == 9 ->
                binding.toggleAspectRatio.check(R.id.btnRatio169)

            1 if project.aspectHeight == 1 ->
                binding.toggleAspectRatio.check(R.id.btnRatio11)

            else -> {
                when (defaultAspect) {
                    "9:16" ->
                        binding.toggleAspectRatio.check(R.id.btnRatio916)

                    "16:9" ->
                        binding.toggleAspectRatio.check(R.id.btnRatio169)

                    "1:1" ->
                        binding.toggleAspectRatio.check(R.id.btnRatio11)

                    else ->
                        binding.toggleAspectRatio.check(R.id.btnRatioOriginal)
                }
            }
        }


        fun updateDimensionsPreview() {
            val resolution = when (binding.toggleResolution.checkedButtonId) {
                R.id.btnRes720 -> "720p"
                R.id.btnRes1080 -> "1080p"
                R.id.btnRes4K -> "4K"
                R.id.btnResOriginal -> "Original"
                else -> "1080p"
            }

            val aspectRatio = when (binding.toggleAspectRatio.checkedButtonId) {
                R.id.btnRatio916 -> "9:16"
                R.id.btnRatio169 -> "16:9"
                R.id.btnRatio11 -> "1:1"
                else -> "Original"
            }

            val (width, height) =
                calculateExportDimensions(
                    resolution,
                    aspectRatio
                )

            binding.tvExportDimensions.text =
                "${width} × ${height}"
        }

        binding.toggleAspectRatio.addOnButtonCheckedListener { _, _, _ ->
            updateDimensionsPreview()
        }

        binding.toggleResolution.addOnButtonCheckedListener { _, _, _ ->
            updateDimensionsPreview()
        }

        updateDimensionsPreview()

        binding.btnApplySettings.setOnClickListener {

            onHistorySaveRequested()

            when (binding.toggleAspectRatio.checkedButtonId) {
                R.id.btnRatio916 -> {
                    project.aspectWidth = 9
                    project.aspectHeight = 16
                }

                R.id.btnRatio169 -> {
                    project.aspectWidth = 16
                    project.aspectHeight = 9
                }

                R.id.btnRatio11 -> {
                    project.aspectWidth = 1
                    project.aspectHeight = 1
                }

                else -> {
                    project.aspectWidth = 0
                    project.aspectHeight = 0
                }
            }

            project.metadata["EXPORT_RES"] =
                when (binding.toggleResolution.checkedButtonId) {
                    R.id.btnRes720 -> "720p"
                    R.id.btnRes4K -> "4K"
                    R.id.btnResOriginal -> "Original"
                    else -> "1080p"
                }

            dialog.dismiss()
            onProjectChanged()
        }

        dialog.show()
    }

    fun updateClipEffect(clip: TimelineClip, type: String, intensity: Float) {
        val existing = clip.effects.find { it.type == type }
        if (existing != null) {
            existing.intensity = intensity
        } else {
            clip.effects.add(TimelineEffect(type = type, category = EffectCategory.VIDEO, intensity = intensity))
        }
    }
}
