package com.example.videoeditorapp

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import com.google.android.material.button.MaterialButton
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.media3.common.util.UnstableApi
import com.example.videoeditorapp.databinding.*
import com.example.videoeditorapp.model.timeline.*
import com.example.videoeditorapp.ui.editor.pickers.*
import com.example.videoeditorapp.ui.timeline.TimelineTool
import com.example.videoeditorapp.ui.timeline.TimelineView
import com.example.videoeditorapp.utils.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.util.UUID

@UnstableApi
fun TimelineTemplateEditorActivity.showClipActionsDialog(clip: TimelineClip) {
    val dialogBinding =
        com.example.videoeditorapp.databinding.DialogClipActionsBinding.inflate(layoutInflater)
    val dialog =
    AppDialog.showCustomView(
        context = this,
        view = dialogBinding.root
    )

    dialogBinding.tvClipTitle.text = clip.type.name + " CLIP"
    dialogBinding.tvClipDuration.text = ViewUtils.formatTime(clip.durationMs)

    val linkedClips = binding.editorPreview.timelineView.findLinkedClips(clip) ?: emptyList()
    val hasLinkedVideo = linkedClips.any { it.type == ClipType.VIDEO }
    val hasLinkedAudio = linkedClips.any { it.type == ClipType.AUDIO }
    val isLinked = linkedClips.size > 1

    if (isLinked) {
        dialogBinding.actionSplitLinked.visibility = View.VISIBLE
        dialogBinding.actionSplitVideo.visibility =
            if (hasLinkedVideo) View.VISIBLE else View.GONE
        dialogBinding.actionSplitAudio.visibility =
            if (hasLinkedAudio) View.VISIBLE else View.GONE
    } else {
        dialogBinding.actionSplitLinked.visibility = View.VISIBLE
        dialogBinding.actionSplitVideo.visibility = View.GONE
        dialogBinding.actionSplitAudio.visibility = View.GONE
    }

    dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

    dialogBinding.actionSplitLinked.setOnClickListener {
        dialog.dismiss()
        splitClipAtPlayhead(clip, splitAllLinked = true)
    }

    dialogBinding.actionSplitVideo.setOnClickListener {
        dialog.dismiss()
        splitClipAtPlayhead(clip, splitVideoOnly = true)
    }

    dialogBinding.actionSplitAudio.setOnClickListener {
        dialog.dismiss()
        splitClipAtPlayhead(clip, splitAudioOnly = true)
    }

    dialogBinding.actionTrimStart.setOnClickListener {
        dialog.dismiss()
        trimClipToPlayhead(clip, trimStart = true)
    }

    dialogBinding.actionTrimEnd.setOnClickListener {
        dialog.dismiss()
        trimClipToPlayhead(clip, trimStart = false)
    }

    var hasPotentialPartners = false
    if (!isLinked) {
        val partners = mutableListOf<TimelineClip>()
        project.tracks.forEach { track ->
            track.clips.forEach { c ->
                if (c.filePath == clip.filePath &&
                    c.startTimeMs == clip.startTimeMs &&
                    c.durationMs == clip.durationMs
                ) {
                    partners.add(c)
                }
            }
        }
        hasPotentialPartners = partners.size > 1
    }

    if (isLinked) {
        dialogBinding.dividerUnlink.visibility = View.VISIBLE
        dialogBinding.actionUnlink.visibility = View.VISIBLE
        dialogBinding.actionLink.visibility = View.GONE
        dialogBinding.actionUnlink.setOnClickListener {
            dialog.dismiss()
            toggleLinkUnlink(clip)
        }
    } else if (hasPotentialPartners) {
        dialogBinding.dividerUnlink.visibility = View.VISIBLE
        dialogBinding.actionUnlink.visibility = View.GONE
        dialogBinding.actionLink.visibility = View.VISIBLE
        dialogBinding.actionLink.setOnClickListener {
            dialog.dismiss()
            toggleLinkUnlink(clip)
        }
    } else {
        dialogBinding.dividerUnlink.visibility = View.GONE
        dialogBinding.actionUnlink.visibility = View.GONE
        dialogBinding.actionLink.visibility = View.GONE
    }

    dialogBinding.btnDeleteClip.setOnClickListener {
        dialog.dismiss()
        binding.editorPreview.timelineView.deleteSelectedClip()
        updateToolUI()
    }

}

@UnstableApi
fun TimelineTemplateEditorActivity.trimClipToPlayhead(targetClip: TimelineClip, trimStart: Boolean) {
    val playhead = currentTimeMs
    if (playhead <= targetClip.startTimeMs || playhead >= targetClip.endTimeMs) {
        Toast.makeText(this, "Playhead must be inside clip to trim", Toast.LENGTH_SHORT).show()
        return
    }

    saveHistory()
    val partners = binding.editorPreview.timelineView.findLinkedClips(targetClip) ?: listOf(targetClip)

    partners.forEach { clip ->
        if (trimStart) {
            val delta = playhead - clip.startTimeMs
            clip.sourceStartTimeMs += delta
            clip.startTimeMs = playhead
            clip.durationMs -= delta
        } else {
            clip.durationMs = playhead - clip.startTimeMs
        }
    }

    binding.editorPreview.timelineView.invalidate()
    rebuildPlayerFromTimeline(currentTimeMs)
    Toast.makeText(this, if (trimStart) "Trimmed Left" else "Trimmed Right", Toast.LENGTH_SHORT)
        .show()
}

@UnstableApi
fun TimelineTemplateEditorActivity.splitClipAtPlayhead(
    targetClip: TimelineClip,
    splitAllLinked: Boolean = false,
    splitVideoOnly: Boolean = false,
    splitAudioOnly: Boolean = false
) {
    val splitTime = currentTimeMs

    if (splitTime <= targetClip.startTimeMs + 1 || splitTime >= targetClip.endTimeMs - 1) {
        Toast.makeText(this, "Playhead must be inside clip to split", Toast.LENGTH_SHORT).show()
        return
    }

    saveHistory()
    val clipsToSplit = mutableListOf<TimelineClip>()

    if (splitAllLinked) {
        clipsToSplit.addAll(
            binding.editorPreview.timelineView.findLinkedClips(targetClip) ?: emptyList()
        )
    } else if (splitVideoOnly) {
        clipsToSplit.addAll(
            binding.editorPreview.timelineView.findLinkedClips(targetClip)?.filter {
                it.type == ClipType.VIDEO
            } ?: emptyList()
        )
    } else if (splitAudioOnly) {
        clipsToSplit.addAll(
            binding.editorPreview.timelineView.findLinkedClips(targetClip)?.filter {
                it.type == ClipType.AUDIO
            } ?: emptyList()
        )
    } else {
        clipsToSplit.add(targetClip)
    }

    var anySplit = false
    clipsToSplit.forEach { clip ->
        val newClip = binding.editorPreview.timelineView.splitClip(clip, splitTime)
        if (newClip != null) anySplit = true
    }

    if (anySplit) {
        selectedClip = null
        binding.editorPreview.timelineView.notifyDataChanged()
        rebuildPlayerFromTimeline(currentTimeMs)
        updateToolUI()
        Toast.makeText(this, "Split Successful", Toast.LENGTH_SHORT).show()
    }
}

fun TimelineTemplateEditorActivity.toggleLinkUnlink(clip: TimelineClip) {
    val currLinked = binding.editorPreview.timelineView.findLinkedClips(clip) ?: listOf(clip)
    val isCurrentlyLinked = currLinked.size > 1 && !clip.isUnlinked

    saveHistory()
    if (isCurrentlyLinked) {
        currLinked.forEach { 
            it.metadata["UNLINKED"] = "true" 
            it.isUnlinked = true
        }
        Toast.makeText(this, "Unlinked Audio & Video", Toast.LENGTH_SHORT).show()
    } else {
        val proj = project
        val partners = mutableListOf<TimelineClip>()
        proj.tracks.forEach { track ->
            track.clips.forEach { c ->
                if (c.filePath == clip.filePath &&
                    c.startTimeMs == clip.startTimeMs &&
                    c.durationMs == clip.durationMs
                ) {
                    partners.add(c)
                }
            }
        }
        if (partners.size > 1) {
            partners.forEach { 
                it.metadata.remove("UNLINKED") 
                it.isUnlinked = false
            }
            Toast.makeText(this, "Linked Audio & Video", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No matching audio/video to link", Toast.LENGTH_SHORT).show()
        }
    }
    binding.editorPreview.timelineView.invalidate()
    updateToolUI()
}

@UnstableApi
fun TimelineTemplateEditorActivity.showUnifiedEditor(
    title: String,
    options: List<com.example.videoeditorapp.ui.editor.EditorOption>,
    onValueChanged: (com.example.videoeditorapp.ui.editor.EditorOption, Float) -> Unit
) {
    val dialogBinding =
        com.example.videoeditorapp.databinding.DialogUnifiedEditorBinding.inflate(layoutInflater)

    dialogBinding.rvOptions.layoutManager =
        androidx.recyclerview.widget.LinearLayoutManager(
            this,
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
            false
        )

    val dialog =
        BottomSheetDialog(
            this,
            R.style.Theme_VideoEditorApp_BottomSheet
        )

    dialog.setContentView(dialogBinding.root)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
        sheet.background = null
        ViewCompat.setBackground(sheet, ColorDrawable(Color.TRANSPARENT))
    }

   dialog.dismissWithAnimation = true

    dialogBinding.tvDialogTitle.text = title.uppercase()
    dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

    var currentOption = options.first()

    fun updateSliderForOption(option: com.example.videoeditorapp.ui.editor.EditorOption) {
        currentOption = option

        dialogBinding.sliderControl.visibility = View.GONE
        dialogBinding.tvSliderLabel.visibility = View.VISIBLE
        dialogBinding.tvSliderValue.visibility = View.VISIBLE

        if (option.type == com.example.videoeditorapp.ui.editor.OptionType.ACTION) {
            dialogBinding.tvSliderLabel.visibility = View.INVISIBLE
            dialogBinding.tvSliderValue.visibility = View.INVISIBLE
            return
        }

        dialogBinding.tvSliderLabel.text = option.label

        val range = option.maxValue - option.minValue
        val calculatedStepSize =
            when {
                range > 10 -> 1.0f
                range > 1 -> 0.1f
                else -> 0.01f
            }

        val alignedMin =
            (Math.floor((option.minValue / calculatedStepSize).toDouble()) *
                    calculatedStepSize)
                .toFloat()
        val alignedMax =
            (Math.ceil((option.maxValue / calculatedStepSize).toDouble()) *
                    calculatedStepSize)
                .toFloat()

        dialogBinding.sliderControl.visibility = View.VISIBLE
        dialogBinding.sliderControl.stepSize = calculatedStepSize
        dialogBinding.sliderControl.valueFrom = alignedMin
        dialogBinding.sliderControl.valueTo =
            alignedMax.coerceAtLeast(alignedMin + calculatedStepSize)

        val snappedValue =
            (Math.round((option.value / calculatedStepSize).toDouble()) *
                    calculatedStepSize)
                .toFloat()
        dialogBinding.sliderControl.value = snappedValue.coerceIn(alignedMin, alignedMax)

        val displayValue =
            if (option.maxValue == 1f && option.minValue == 0f) {
                "${(option.value * 100).toInt()}%"
            } else {
                String.format("%.1f", option.value)
            }
        dialogBinding.tvSliderValue.text = displayValue
    }

    updateSliderForOption(currentOption)

    val adapter =
        com.example.videoeditorapp.ui.editor.EditorOptionsAdapter(options) { selected ->
            updateSliderForOption(selected)
            if (selected.type == com.example.videoeditorapp.ui.editor.OptionType.ACTION) {
                onValueChanged(selected, 0f)
            }
        }
    dialogBinding.rvOptions.adapter = adapter

    fun formatDisplayValue(
        option: com.example.videoeditorapp.ui.editor.EditorOption,
        value: Float
    ): String {
        val labelUpper = option.label.uppercase()
        return when {
            labelUpper.contains("(MS)") ||
                    labelUpper.contains("FADE") ||
                    labelUpper.contains("DELAY") -> {
                "${value.toInt()}ms"
            }
            option.maxValue == 1f && option.minValue == 0f -> {
                "${(value * 100).toInt()}%"
            }
            else -> String.format("%.1f", value)
        }
    }

    dialogBinding.sliderControl.addOnChangeListener { _, value, fromUser ->
        if (fromUser) {
            currentOption.value = value
            adapter.updateValue(currentOption.id, value)
            dialogBinding.tvSliderValue.text = formatDisplayValue(currentOption, value)
            onValueChanged(currentOption, value)
        }
    }



    dialogBinding.btnDone.setOnClickListener {
        saveProject()
        dialog.dismiss()
        Toast.makeText(this, "Changes Applied", Toast.LENGTH_SHORT).show()
    }

    dialog.show()
}

@UnstableApi
fun TimelineTemplateEditorActivity.showTextPropertiesDialog(existingClip: TimelineClip? = null) {
    if (existingClip == null) {
        val inputBinding = DialogInputBinding.inflate(layoutInflater)
        inputBinding.dialogTitle.text = "ADD TEXT OVERLAY"
        inputBinding.tilInput.hint = "TYPE YOUR TEXT..."

    val dialog =
    AppDialog.showCustomView(
        context = this,
        view = inputBinding.root
    )

        inputBinding.btnConfirm.setOnClickListener {
            val text = inputBinding.etInput.text.toString().ifEmpty { "MODERN TEXT" }
            val newClip = addTextClip(text, 50f, 2f)
            dialog.dismiss()
            showTextPropertiesDialog(newClip)
        }
        inputBinding.btnCancel.setOnClickListener { dialog.dismiss() }

     
        return
    }

    val clip = existingClip

    fun getSetting(key: String, def: Float): Float {
        return clip.textSettings[key]?.toFloatOrNull() ?: def
    }

    val currentAlign = clip.textSettings["ALIGN"] ?: "CENTER"
    val alignOption =
        com.example.videoeditorapp.ui.editor.EditorOption(
            "ALIGN",
            when (currentAlign) {
                "LEFT" -> R.drawable.ic_align_left
                "RIGHT" -> R.drawable.ic_align_right
                else -> R.drawable.ic_align_center
            },
            "Align: ${currentAlign.lowercase().replaceFirstChar { it.uppercase() }}",
            type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
        )

    val options =
        listOf(
            com.example.videoeditorapp.ui.editor.EditorOption(
                "SIZE",
                R.drawable.ic_title,
                "Size",
                getSetting("SIZE", 40f),
                10f,
                200f,
                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            alignOption,
            com.example.videoeditorapp.ui.editor.EditorOption(
                "COLOR",
                R.drawable.ic_color_lens,
                "Color",
                0f,
                0f,
                0f,
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "ANIM_FADE",
                R.drawable.ic_auto_awesome,
                "Fade In",
                if (getSetting("ANIM_FADE", 0f) > 0) 1f else 0f,
                0f,
                1f,
                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "ANIM_SLIDE",
                R.drawable.ic_fast_forward,
                "Slide Effect",
                0f,
                0f,
                0f,
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "EDIT_TEXT",
                R.drawable.ic_edit,
                "Edit Text",
                0f,
                0f,
                0f,
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            )
        )

    showUnifiedEditor("Motion Text // FX", options) { option, value ->
        when (option.id) {
            "SIZE" -> clip.textSettings["SIZE"] = value.toString()
            "ANIM_FADE" -> clip.textSettings["ANIM_FADE"] = if (value > 0.5f) "1000" else "0"
            "ANIM_SLIDE" -> {
                val current = clip.textSettings["ANIM_SLIDE"] ?: "NONE"
                val next =
                    when (current) {
                        "NONE" -> "LEFT_TO_RIGHT"
                        "LEFT_TO_RIGHT" -> "BOTTOM_UP"
                        else -> "NONE"
                    }
                clip.textSettings["ANIM_SLIDE"] = next
                option.label = "Slide: $next"
                Toast.makeText(this, "Motion: $next", Toast.LENGTH_SHORT).show()
            }
            "ALIGN" -> {
                val next =
                    when (clip.textSettings["ALIGN"]) {
                        "LEFT" -> "CENTER"
                        "CENTER" -> "RIGHT"
                        else -> "LEFT"
                    }
                clip.textSettings["ALIGN"] = next
                option.label = "Align: ${next.lowercase().replaceFirstChar { it.uppercase() }}"
                option.iconRes =
                    when (next) {
                        "LEFT" -> R.drawable.ic_align_left
                        "RIGHT" -> R.drawable.ic_align_right
                        else -> R.drawable.ic_align_center
                    }
            }
            "COLOR" -> showColorPickerDialog(clip)
            "EDIT_TEXT" -> showTextInputDialog(clip)
        }
        rebuildPlayerFromTimeline(currentTimeMs)
    }
}

@UnstableApi
fun TimelineTemplateEditorActivity.showColorPickerDialog(clip: TimelineClip) {
    val dialogBinding =
        com.example.videoeditorapp.databinding.DialogColorPickerBinding.inflate(layoutInflater)
 val dialog =
    AppDialog.showCustomView(
        context = this,
        view = dialogBinding.root
    )
    val currentColorHex = clip.textSettings["COLOR"] ?: "#FFFFFF"
    var currentColor =
        try {
            Color.parseColor(currentColorHex)
        } catch (e: Exception) {
            Color.WHITE
        }

    fun updateUI(color: Int, fromUser: Boolean = false) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        if (!fromUser) {
            dialogBinding.seekRedDialog.progress = r
            dialogBinding.seekGreenDialog.progress = g
            dialogBinding.seekBlueDialog.progress = b
        }

        dialogBinding.tvRedValue.text = r.toString()
        dialogBinding.tvGreenValue.text = g.toString()
        dialogBinding.tvBlueValue.text = b.toString()

        dialogBinding.viewPreview.setBackgroundColor(color)
        val hex = String.format("#%02X%02X%02X", r, g, b)
        if (!fromUser) {
            dialogBinding.etHex.setText(hex)
        }
    }

    updateUI(currentColor)

    val changeListener =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                p0: android.widget.SeekBar?,
                p1: Int,
                p2: Boolean
            ) {
                if (p2) {
                    val r = dialogBinding.seekRedDialog.progress
                    val g = dialogBinding.seekGreenDialog.progress
                    val b = dialogBinding.seekBlueDialog.progress
                    currentColor = Color.rgb(r, g, b)
                    updateUI(currentColor, true)
                }
            }
            override fun onStartTrackingTouch(p0: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(p0: android.widget.SeekBar?) {}
        }

    dialogBinding.seekRedDialog.setOnSeekBarChangeListener(changeListener)
    dialogBinding.seekGreenDialog.setOnSeekBarChangeListener(changeListener)
    dialogBinding.seekBlueDialog.setOnSeekBarChangeListener(changeListener)

    dialogBinding.btnDone.setOnClickListener {
        val r = dialogBinding.seekRedDialog.progress
        val g = dialogBinding.seekGreenDialog.progress
        val b = dialogBinding.seekBlueDialog.progress
        val finalHex = String.format("#%02X%02X%02X", r, g, b)
        clip.textSettings["COLOR"] = finalHex
        rebuildPlayerFromTimeline(currentTimeMs)
        dialog.dismiss()
    }

}

@UnstableApi
fun TimelineTemplateEditorActivity.showOverlayPropertiesDialog(clip: TimelineClip) {
    val options =
        listOf(
            com.example.videoeditorapp.ui.editor.EditorOption(
                "OPACITY",
                R.drawable.ic_visibility,
                "Opacity",
                clip.overlayOpacity,
                0f,
                1f,
                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "SCALE",
                R.drawable.ic_zoom_in,
                "Scale",
                clip.overlayScale,
                0.1f,
                5.0f,
                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                controlType = com.example.videoeditorapp.ui.editor.ControlType.WHEEL
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "ROTATION",
                R.drawable.ic_rotate_right,
                "Rotate",
                clip.overlayRotation,
                0f,
                360f,
                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                controlType = com.example.videoeditorapp.ui.editor.ControlType.WHEEL
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "FILTERS",
                R.drawable.ic_auto_awesome,
                "Filters",
                0f,
                0f,
                0f,
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            )
        )

    showUnifiedEditor("Overlay Options", options) { option, value ->
        when (option.id) {
            "OPACITY" -> clip.overlayOpacity = value
            "SCALE" -> clip.overlayScale = value
            "ROTATION" -> clip.overlayRotation = value
            "FILTERS" -> showFiltersPicker()
        }
        rebuildPlayerFromTimeline(currentTimeMs)
        binding.editorPreview.timelineView.invalidate()
    }
}
@UnstableApi
fun TimelineTemplateEditorActivity.showTextInputDialog(
    clip: TimelineClip
) {
    val binding =
        DialogInputBinding.inflate(layoutInflater)

    binding.dialogTitle.text = "EDIT TEXT"
    binding.etInput.setText(
        clip.textSettings["TEXT"] ?: ""
    )

    val dialog =
        AppDialog.showCustomView(
            context = this,
            view = binding.root
        )

    binding.btnConfirm.setOnClickListener {
        clip.textSettings["TEXT"] =
            binding.etInput.text.toString()

        rebuildPlayerFromTimeline(currentTimeMs)
        dialog.dismiss()
    }

    binding.btnCancel.setOnClickListener {
        dialog.dismiss()
    }
}
@UnstableApi
fun TimelineTemplateEditorActivity.showAudioMixerDialog(clip: TimelineClip) {

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 32, 48, 24)
    }

    val echoSwitch =
        com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "Echo"
            isChecked =
                clip.effects.any {
                    it.type == "ECHO" &&
                    it.category == EffectCategory.AUDIO
                }
        }

    val reverbSwitch =
        com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "Reverb"
            isChecked =
                clip.effects.any {
                    it.type == "REVERB" &&
                    it.category == EffectCategory.AUDIO
                }
        }

    val resetBtn =
        com.google.android.material.button.MaterialButton(this).apply {
            text = "Reset Audio Effects"

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 24
                }
        }

    container.addView(echoSwitch)
    container.addView(reverbSwitch)
    container.addView(resetBtn)
val closeBtn =
    MaterialButton(this).apply {
        text = "Close"

        layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
    }

container.addView(closeBtn)


val dialog =
    AppDialog.showCustomView(
        context = this,
        view = container
    )
    closeBtn.setOnClickListener {
    dialog.dismiss()
}

    echoSwitch.setOnCheckedChangeListener { _, isChecked ->

        if (isChecked) {
            if (clip.effects.none { it.type == "ECHO" }) {
                clip.effects.add(
                    TimelineEffect(
                        type = "ECHO",
                        category = EffectCategory.AUDIO
                    )
                )
            }
        } else {
            clip.effects.removeAll { it.type == "ECHO" }
        }

        rebuildPlayerFromTimeline(currentTimeMs)
    }

    reverbSwitch.setOnCheckedChangeListener { _, isChecked ->

        if (isChecked) {
            if (clip.effects.none { it.type == "REVERB" }) {
                clip.effects.add(
                    TimelineEffect(
                        type = "REVERB",
                        category = EffectCategory.AUDIO
                    )
                )
            }
        } else {
            clip.effects.removeAll { it.type == "REVERB" }
        }

        rebuildPlayerFromTimeline(currentTimeMs)
    }

    resetBtn.setOnClickListener {

        clip.effects.removeAll {
            it.category == EffectCategory.AUDIO
        }

        echoSwitch.isChecked = false
        reverbSwitch.isChecked = false

        rebuildPlayerFromTimeline(currentTimeMs)
    }
}

@UnstableApi
fun TimelineTemplateEditorActivity.addAssetToTimeline(path: String, type: String?, category: String?) {
    val file = File(path)
    if (!file.exists()) return

    if (type == "EFFECT_PRESET") {
        val clip = selectedClip
        if (clip == null) {
            Toast.makeText(this, "Please select a clip to apply effect", Toast.LENGTH_LONG).show()
            return
        }
        saveHistory()
        applyPresetToClip(clip, path)
        rebuildPlayerFromTimeline(currentTimeMs)
        binding.editorPreview.timelineView.invalidate()
        Toast.makeText(this, "Applied preset to selected clip", Toast.LENGTH_SHORT).show()
        return
    }

    saveHistory()

    val clipType =
        when (category) {
            "MUSIC", "SFX" -> ClipType.AUDIO
            "STICKERS" -> ClipType.STICKER
            else -> ClipType.VIDEO
        }

    val duration =
        if (clipType == ClipType.AUDIO || clipType == ClipType.VIDEO) {
            getMediaDuration(path)
        } else {
            DEFAULT_IMAGE_DURATION_MS
        }

    val clip =
        TimelineClip(
            id = UUID.randomUUID().toString(),
            filePath = path,
            type = clipType,
            startTimeMs = currentTimeMs,
            durationMs = duration,
            sourceStartTimeMs = 0,
            sourceDurationMs = duration,
            metadata = mutableMapOf("FROM_HUB" to "true")
        )

    val trackType =
        when (clipType) {
            ClipType.VIDEO -> TrackType.VIDEO
            ClipType.AUDIO -> TrackType.AUDIO
            ClipType.STICKER, ClipType.IMAGE, ClipType.TEXT -> TrackType.OVERLAY
            else -> TrackType.VIDEO
        }

    val track = findSmartTrack(trackType, currentTimeMs, duration)
    track.clips.add(clip)

    if (clipType == ClipType.VIDEO &&
        com.example.videoeditorapp.model.timeline.FFmpegTimelineUtils.hasAudioStream(path)
    ) {
        val audioClip =
            clip.copy(
                id = UUID.randomUUID().toString(),
                type = ClipType.AUDIO
            )
        val audioTrack = findSmartTrack(TrackType.AUDIO, currentTimeMs, duration)
        audioTrack.clips.add(audioClip)
        audioTrack.clips.sortBy { it.startTimeMs }
    }

    rebuildPlayerFromTimeline(currentTimeMs)
    binding.editorPreview.timelineView.invalidate()
    binding.editorPreview.timelineView.animateClip(clip.id)

    Toast.makeText(this, "Added ${file.name} to timeline", Toast.LENGTH_SHORT).show()
}

fun TimelineTemplateEditorActivity.applyPresetToClip(clip: TimelineClip, path: String) {
    val fileName = File(path).name.lowercase()
    when {
        fileName.contains("glitch") -> {
            clip.effects.add(TimelineEffect(type = "GLITCH", intensity = 0.8f))
        }
        fileName.contains("grain") -> {
            clip.effects.add(TimelineEffect(type = "GRAIN", intensity = 0.5f))
        }
        fileName.contains("vignette") -> {
            clip.effects.add(TimelineEffect(type = "VIGNETTE", intensity = 0.7f))
        }
        else -> {
            clip.effects.add(TimelineEffect(type = "PRESET_FX", intensity = 1.0f))
        }
    }
}

fun TimelineTemplateEditorActivity.showStickerPicker() {
    StickerPickerBottomSheet { stickerPath ->
        addAssetToTimeline(
            path = stickerPath,
            type = null,
            category = "STICKERS"
        )
    }.show(supportFragmentManager, "sticker_picker")
}

fun TimelineTemplateEditorActivity.showEmojiPicker() {
    EmojiPickerBottomSheet { emoji ->
        addAssetToTimeline(
            path = emoji,
            type = null,
            category = "EMOJIS"
        )
    }.show(supportFragmentManager, "emoji_picker")
}

fun TimelineTemplateEditorActivity.showGifPicker() {
    GifPickerBottomSheet { gifUrl ->
        addAssetToTimeline(
            path = gifUrl,
            type = null,
            category = "GIFS"
        )
    }.show(supportFragmentManager, "gif_picker")
}



@UnstableApi
fun TimelineTemplateEditorActivity.reverseClip(clip: TimelineClip) {
    saveHistory()
    if (clip.metadata["REVERSED"] == "true") {
        clip.metadata.remove("REVERSED")
        Toast.makeText(this, "Reverse Disabled", Toast.LENGTH_SHORT).show()
    } else {
        clip.metadata["REVERSED"] = "true"
        Toast.makeText(this, "Reverse Enabled", Toast.LENGTH_SHORT).show()
    }
    rebuildPlayerFromTimeline(currentTimeMs)
    updateToolUI()
    binding.editorPreview.timelineView.invalidate()
}

@UnstableApi
fun TimelineTemplateEditorActivity.showFadeDialog(clipToEdit: TimelineClip? = null) {
    val clip = clipToEdit ?: selectedClip ?: return
    val options = mutableListOf<com.example.videoeditorapp.ui.editor.EditorOption>()

    if (clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE) {
        val fadeIn = clip.metadata["VIDEO_FADE_IN"]?.toFloatOrNull() ?: 0f
        val fadeOut = clip.metadata["VIDEO_FADE_OUT"]?.toFloatOrNull() ?: 0f

        options.add(
            com.example.videoeditorapp.ui.editor.EditorOption(
                "video_fade_in",
                R.drawable.ic_fade,
                "Video Fade In (ms)",
                fadeIn,
                0f,
                2000f,
                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            )
        )
        options.add(
            com.example.videoeditorapp.ui.editor.EditorOption(
                "video_fade_out",
                R.drawable.ic_fade,
                "Video Fade Out (ms)",
                fadeOut,
                0f,
                2000f,
                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                controlType = com.example.videoeditorapp.ui.editor.ControlType.WHEEL
            )
        )
    }

    if (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO) {
        val afFadeIn = clip.metadata["AUDIO_FADE_IN"]?.toFloatOrNull() ?: 0f
        val afFadeOut = clip.metadata["AUDIO_FADE_OUT"]?.toFloatOrNull() ?: 0f

        options.add(
            com.example.videoeditorapp.ui.editor.EditorOption(
                "audio_fade_in",
                R.drawable.ic_volume_up,
                "Audio Fade In (ms)",
                afFadeIn,
                0f,
                2000f,
                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            )
        )
        options.add(
            com.example.videoeditorapp.ui.editor.EditorOption(
                "audio_fade_out",
                R.drawable.ic_volume_off,
                "Audio Fade Out (ms)",
                afFadeOut,
                0f,
                2000f,
                type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
                controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            )
        )
    }

    if (options.isEmpty()) {
        Toast.makeText(this, "Fades not available for this clip type", Toast.LENGTH_SHORT).show()
        return
    }

    showUnifiedEditor("Transitions & Fades", options) { option, value ->
        when (option.id) {
            "video_fade_in" -> clip.metadata["VIDEO_FADE_IN"] = value.toLong().toString()
            "video_fade_out" -> clip.metadata["VIDEO_FADE_OUT"] = value.toLong().toString()
            "audio_fade_in" -> clip.metadata["AUDIO_FADE_IN"] = value.toLong().toString()
            "audio_fade_out" -> clip.metadata["AUDIO_FADE_OUT"] = value.toLong().toString()
        }
    }
}

@UnstableApi
fun TimelineTemplateEditorActivity.showOpacityDialog(clipToEdit: TimelineClip? = null) {
    val clip = clipToEdit ?: selectedClip ?: return

    val opacityOption =
        com.example.videoeditorapp.ui.editor.EditorOption(
            id = "opacity",
            iconRes = R.drawable.ic_opacity,
            label = "Clip Opacity",
            value = clip.overlayOpacity,
            minValue = 0.0f,
            maxValue = 1.0f,
            isSelected = true,
            type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
            controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
        )

    showUnifiedEditor(title = "Opacity Control", options = listOf(opacityOption)) { _, value ->
        clip.overlayOpacity = value
        rebuildPlayerFromTimeline(currentTimeMs)
        updateToolUI()
    }
}

@UnstableApi
fun TimelineTemplateEditorActivity.showVolumeDialog() {
    val clip = selectedClip ?: return

    val volumeOption =
        com.example.videoeditorapp.ui.editor.EditorOption(
            id = "volume",
            iconRes = R.drawable.ic_volume_up,
            label = "Clip Volume",
            value = clip.audioVolume,
            minValue = 0f,
            maxValue = 2.0f,
            isSelected = true,
            type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
            controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
        )

    showUnifiedEditor(title = "Volume Control", options = listOf(volumeOption)) { option, value ->
        clip.audioVolume = value
        rebuildPlayerFromTimeline(currentTimeMs)
        updateToolUI()
    }
}

@UnstableApi
fun TimelineTemplateEditorActivity.showCropDialog() {
    val clip = selectedClip ?: return
    val currentPreset = clip.metadata["CROP_PRESET"] ?: "ORIGINAL"

    val options =
        listOf(
            com.example.videoeditorapp.ui.editor.EditorOption(
                "ORIGINAL",
                R.drawable.ic_crop,
                "Original",
                0f,
                isSelected = currentPreset == "ORIGINAL",
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "1:1",
                R.drawable.ic_crop,
                "1:1 (Square)",
                0f,
                isSelected = currentPreset == "1:1",
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "16:9",
                R.drawable.ic_crop,
                "16:9 (HD)",
                0f,
                isSelected = currentPreset == "16:9",
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "9:16",
                R.drawable.ic_crop,
                "9:16 (Reel)",
                0f,
                isSelected = currentPreset == "9:16",
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "4:5",
                R.drawable.ic_crop,
                "4:5 (Post)",
                0f,
                isSelected = currentPreset == "4:5",
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            )
        )

    showUnifiedEditor("Crop Aspect Ratio", options) { option, _ ->
        if (option.id == "ORIGINAL") {
            clip.metadata.remove("CROP_PRESET")
        } else {
            clip.metadata["CROP_PRESET"] = option.id
        }
        rebuildPlayerFromTimeline(currentTimeMs)
        updateToolUI()
    }
}

fun TimelineTemplateEditorActivity.clearSelection() {
    selectedClip = null
    binding.editorPreview.timelineView.invalidate()
    updateBottomBarVisibility()
}

fun TimelineTemplateEditorActivity.updateBottomBarVisibility() {
    val container = binding.editorPreview.bottomNavWrapper ?: return

    container.post {
        val fullHeight = container.height.toFloat()
        if (fullHeight == 0f) return@post

        if (selectedClip != null) {
            if (container.visibility != View.VISIBLE) {
                container.translationY = fullHeight
                container.alpha = 0f
                container.visibility = View.VISIBLE
            }
            container.animate().translationY(0f).alpha(1f).setDuration(220).start()
        } else {
            if (container.visibility != View.VISIBLE) {
                container.translationY = fullHeight
                container.alpha = 0f
                container.visibility = View.VISIBLE
            }
            container.animate().translationY(0f).alpha(1f).setDuration(220).start()
        }
    }
}

@UnstableApi
fun TimelineTemplateEditorActivity.addTextClip(text: String, size: Float, shadow: Float): TimelineClip {
    val duration = 3000L
    val track = findSmartTrack(TrackType.OVERLAY, currentTimeMs, duration)

    val clip =
        TimelineClip(
            filePath = "TEXT_CLIP",
            startTimeMs = currentTimeMs,
            durationMs = 3000L,
            sourceDurationMs = 3000L,
            type = ClipType.TEXT
        ).apply {
            textSettings["TEXT"] = text
            textSettings["SIZE"] = size.toString()
            textSettings["SHADOW"] = shadow.toString()
            textSettings["COLOR"] = "#FFFFFF"
        }

    track.clips.add(clip)
    binding.editorPreview.timelineView.animateClip(clip.id)
    rebuildPlayerFromTimeline(currentTimeMs)
    updateUIDuration()
    updateEditorVisibility()
    binding.editorPreview.timelineView.invalidate()
    return clip
}

@UnstableApi
fun TimelineTemplateEditorActivity.showClipInfoDialog() {

    val clip =
        selectedClip ?: run {
            Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show()
            return
        }

    val file = File(clip.filePath)

   val message = buildString {

    append("Name: ${file.name}\n")
    append("Path: ${clip.filePath}\n")
    append("Start: ${ViewUtils.formatTime(clip.startTimeMs)}\n")
    append("Duration: ${ViewUtils.formatTime(clip.durationMs)}\n")
    append("Speed: ${clip.playbackSpeed}x\n")
    append("Volume: ${(clip.audioVolume * 100).toInt()}%\n")

    if (clip.effects.isNotEmpty()) {

        append("\nEffects / Filters:\n")

        clip.effects.forEach {
            append("• ${it.type} (${(it.intensity * 100).toInt()}%)\n")
        }
    }
}
AppDialog.showInfo(
    context = this,
    title = "Clip Information",
    message = message,
    iconRes = R.drawable.ic_info
)
}

@UnstableApi
fun TimelineTemplateEditorActivity.updateToolUI() {
    val activeTool =
        binding.editorPreview.timelineView.getActiveTool()
            ?: TimelineTool.SELECT
    val clip = selectedClip
    val isSel = clip != null

    binding.editorPreview.clipActionsNav.visibility = if (isSel) View.VISIBLE else View.GONE
    binding.editorPreview.bottomNavTabs.visibility = if (isSel) View.GONE else View.VISIBLE

    if (isSel) {
        val selected = clip!!
        binding.editorPreview.btnSelect.visibility = View.VISIBLE
        binding.editorPreview.btnSplit.visibility = View.VISIBLE
        binding.editorPreview.btnTrim.visibility = View.VISIBLE
        binding.editorPreview.btnDeleteClip.visibility = View.VISIBLE
        binding.editorPreview.btnSpeed.visibility =
            if (selected.type == ClipType.VIDEO || selected.type == ClipType.AUDIO) View.VISIBLE
            else View.GONE
        binding.editorPreview.btnVolume.visibility =
            if (selected.type == ClipType.VIDEO || selected.type == ClipType.AUDIO) View.VISIBLE
            else View.GONE
        binding.editorPreview.btnCopyClip.visibility = View.VISIBLE
        binding.editorPreview.btnCropClip.visibility =
            if (selected.type == ClipType.VIDEO || selected.type == ClipType.IMAGE) View.VISIBLE
            else View.GONE
        binding.editorPreview.btnMoveToFront.visibility = View.VISIBLE
        binding.editorPreview.btnMoveToBack.visibility = View.VISIBLE
    }

    fun updateCard(view: View?, isActive: Boolean) {
        if (view is MaterialCardView) {
            view.strokeWidth = ViewUtils.dpToPx(this, if (isActive) 2 else 1)
            view.alpha = if (isActive) 1f else 0.85f
        }
    }
    updateCard(binding.editorPreview.btnSelect, activeTool == TimelineTool.SELECT)
    updateCard(binding.editorPreview.btnTrim, activeTool == TimelineTool.TRIM)
    updateCard(binding.editorPreview.btnSplit, activeTool == TimelineTool.SPLIT)
}

fun TimelineTemplateEditorActivity.updateEditorVisibility() {
    val hasClips = project.tracks.any { it.clips.isNotEmpty() }
    val visibility = if (hasClips) View.VISIBLE else View.GONE
    binding.editorPreview.playerView.visibility = visibility
    binding.editorPreview.timelineView.visibility = visibility
}

fun TimelineTemplateEditorActivity.showAdjustPanel() {
    val clip = selectedClip ?: return

    fun getEffectValue(type: String, def: Float = 1.0f): Float {
        return clip.effects.find { it.type == type }?.intensity ?: def
    }

    val adjustmentOptions =
        listOf(
            com.example.videoeditorapp.ui.editor.EditorOption(
                "EXPOSURE",
                R.drawable.ic_adjust,
                "Exposure",
                getEffectValue("EXPOSURE"),
                0.0f,
                2.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "BRIGHTNESS",
                R.drawable.ic_adjust,
                "Brightness",
                getEffectValue("BRIGHTNESS"),
                0.0f,
                2.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "CONTRAST",
                R.drawable.ic_adjust,
                "Contrast",
                getEffectValue("CONTRAST"),
                0.0f,
                2.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "SATURATION",
                R.drawable.ic_adjust,
                "Saturation",
                getEffectValue("SATURATION"),
                0.0f,
                2.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "VIGNETTE",
                R.drawable.ic_adjust,
                "Vignette",
                getEffectValue("VIGNETTE", 0f),
                0.0f,
                1.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "SHARPEN",
                R.drawable.ic_adjust,
                "Sharpness",
                getEffectValue("SHARPEN", 0f),
                0.0f,
                1.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "HIGHLIGHTS",
                R.drawable.ic_adjust,
                "Highlights",
                getEffectValue("HIGHLIGHTS"),
                0.0f,
                2.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "SHADOWS",
                R.drawable.ic_adjust,
                "Shadows",
                getEffectValue("SHADOWS"),
                0.0f,
                2.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "TEMPERATURE",
                R.drawable.ic_adjust,
                "Temp",
                getEffectValue("TEMPERATURE"),
                0.5f,
                1.5f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "TINT",
                R.drawable.ic_adjust,
                "Tint",
                getEffectValue("TINT"),
                0.5f,
                1.5f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                "GRAIN",
                R.drawable.ic_adjust,
                "Grain",
                getEffectValue("GRAIN", 0f),
                0.0f,
                1.0f,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            )
        )

    showUnifiedEditor("Adjustments", adjustmentOptions) { option, value ->
        updateClipEffect(clip, option.id, value)
        rebuildPlayerFromTimeline(currentTimeMs)
    }
}

fun TimelineTemplateEditorActivity.showFiltersPicker() {
    val clip = selectedClip ?: return

    fun getEffectValue(type: String, def: Float = 0.0f): Float {
        return clip.effects.find { it.type == type }?.intensity ?: def
    }

    val filterOptions =
        listOf(
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "GRAYSCALE",
                iconRes = R.drawable.ic_filter,
                label = "B&W",
                value = getEffectValue("GRAYSCALE"),
                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "NOIR",
                iconRes = R.drawable.ic_filter,
                label = "Noir",
                value = getEffectValue("NOIR"),
                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "SEPIA",
                iconRes = R.drawable.ic_filter,
                label = "Sepia",
                value = getEffectValue("SEPIA"),
                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "VINTAGE",
                iconRes = R.drawable.ic_filter,
                label = "Vintage",
                value = getEffectValue("VINTAGE"),
                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "COOL",
                iconRes = R.drawable.ic_filter,
                label = "Cool",
                value = getEffectValue("COOL"),
                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "WARM",
                iconRes = R.drawable.ic_filter,
                label = "Warm",
                value = getEffectValue("WARM"),
                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "LOMO",
                iconRes = R.drawable.ic_filter,
                label = "Lomo",
                value = getEffectValue("LOMO"),
                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "POLAROID",
                iconRes = R.drawable.ic_filter,
                label = "Instant",
                value = getEffectValue("POLAROID"),
                type = com.example.videoeditorapp.ui.editor.OptionType.FILTER,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "NONE",
                iconRes = R.drawable.ic_close,
                label = "Reset",
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            )
        )

    showUnifiedEditor("Cinematic Filters", filterOptions) { option, value ->
        if (option.id == "NONE") {
            clip.effects.clear()
        } else {
            updateClipEffect(clip, option.id, value)
        }
        rebuildPlayerFromTimeline(currentTimeMs)
    }
}

fun TimelineTemplateEditorActivity.showEffectsPicker() {
    val clip = selectedClip ?: return

    fun getEffectValue(type: String, def: Float = 0.0f): Float {
        return clip.effects.find { it.type == type }?.intensity ?: def
    }

    val effectOptions =
        listOf(
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "BLUR",
                iconRes = R.drawable.ic_magic,
                label = "Blur",
                value = getEffectValue("BLUR"),
                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "GLITCH",
                iconRes = R.drawable.ic_magic,
                label = "Glitch",
                value = getEffectValue("GLITCH"),
                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "SHAKE",
                iconRes = R.drawable.ic_magic,
                label = "Shake",
                value = getEffectValue("SHAKE"),
                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "OLD_MOVIE",
                iconRes = R.drawable.ic_magic,
                label = "Retro",
                value = getEffectValue("OLD_MOVIE"),
                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "PIXELATE",
                iconRes = R.drawable.ic_magic,
                label = "Pixelate",
                value = getEffectValue("PIXELATE"),
                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "MIRROR",
                iconRes = R.drawable.ic_magic,
                label = "Mirror",
                value = getEffectValue("MIRROR"),
                type = com.example.videoeditorapp.ui.editor.OptionType.EFFECT,
                controlType =
                    com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
            ),
            com.example.videoeditorapp.ui.editor.EditorOption(
                id = "RESET",
                iconRes = R.drawable.ic_close,
                label = "Reset",
                type = com.example.videoeditorapp.ui.editor.OptionType.ACTION
            )
        )

    showEffectsPicker(effectOptions)
}

fun TimelineTemplateEditorActivity.showEffectsPicker(effectOptions: List<com.example.videoeditorapp.ui.editor.EditorOption>) {
    val clip = selectedClip ?: return
    showUnifiedEditor("Special Effects", effectOptions) { option, value ->
        if (option.id == "RESET") {
            clip.effects.removeAll { it.type == "BLUR" || it.type == "GLITCH" || it.type == "SHAKE" || it.type == "OLD_MOVIE" || it.type == "PIXELATE" || it.type == "MIRROR" }
        } else {
            updateClipEffect(clip, option.id, value)
        }
        rebuildPlayerFromTimeline(currentTimeMs)
    }
}

fun TimelineTemplateEditorActivity.updateClipEffect(clip: TimelineClip, type: String, intensity: Float) {
    val existing = clip.effects.find { it.type == type }
    if (existing != null) {
        existing.intensity = intensity
    } else {
        clip.effects.add(
            TimelineEffect(
                type = type,
                category = EffectCategory.VIDEO,
                intensity = intensity
            )
        )
    }
}

fun TimelineTemplateEditorActivity.findSmartTrack(type: TrackType, startTime: Long, duration: Long): TimelineTrack {
    var found = project.tracks.find { it.type == type }
    if (found == null) {
        found = TimelineTrack(id = UUID.randomUUID().toString(), type = type)
        project.tracks.add(found)
    }
    return found
}

fun TimelineTemplateEditorActivity.moveSelectedClipToFront() {
    val clip = selectedClip ?: return
    val track = project.tracks.find { it.clips.contains(clip) } ?: return
    if (project.tracks.remove(track)) {
        project.tracks.add(track)
    }
    binding.editorPreview.timelineView.invalidate()
    rebuildPlayerFromTimeline()
}

fun TimelineTemplateEditorActivity.moveSelectedClipToBack() {
    val clip = selectedClip ?: return
    val track = project.tracks.find { it.clips.contains(clip) } ?: return
    if (project.tracks.remove(track)) {
        val firstOverlayIndex = project.tracks.indexOfFirst { it.type == TrackType.OVERLAY }
        if (firstOverlayIndex != -1) {
            project.tracks.add(firstOverlayIndex, track)
        } else {
            project.tracks.add(0, track)
        }
    }
    binding.editorPreview.timelineView.invalidate()
    rebuildPlayerFromTimeline()
}

fun TimelineTemplateEditorActivity.showSpeedDialog() {
    val clip = selectedClip ?: return

    val speedOption =
        com.example.videoeditorapp.ui.editor.EditorOption(
            id = "speed",
            iconRes = R.drawable.ic_speed,
            label = "Playback Speed",
            value = clip.playbackSpeed,
            minValue = 0.2f,
            maxValue = 4.0f,
            isSelected = true,
            type = com.example.videoeditorapp.ui.editor.OptionType.PROPERTY,
            controlType = com.example.videoeditorapp.ui.editor.ControlType.CIRCULAR
        )

    showUnifiedEditor(title = "Speed Control", options = listOf(speedOption)) { option, value ->
        clip.playbackSpeed = value
        exoPlayer?.setPlaybackSpeed(value)
        rebuildPlayerFromTimeline(currentTimeMs)
        binding.editorPreview.timelineView.invalidate()
    }
}

fun TimelineTemplateEditorActivity.pasteClipFromClipboard() {
    val clipToPaste = ClipClipboard.get() ?: return

    val newClip =
        clipToPaste.copy(id = UUID.randomUUID().toString(), startTimeMs = currentTimeMs)

    val trackType =
        when (newClip.type) {
            ClipType.VIDEO -> TrackType.VIDEO
            ClipType.AUDIO -> TrackType.AUDIO
            ClipType.STICKER, ClipType.IMAGE, ClipType.TEXT -> TrackType.OVERLAY
            else -> TrackType.VIDEO
        }

    val track = project.tracks.find { it.type == trackType } ?: project.tracks.first()
    track.clips.add(newClip)
    track.clips.sortBy { it.startTimeMs }
    binding.editorPreview.timelineView.animateClip(newClip.id)

    Toast.makeText(this, "Pasted at ${currentTimeMs / 1000}s", Toast.LENGTH_SHORT).show()

    rebuildPlayerFromTimeline(currentTimeMs)
    binding.editorPreview.timelineView.invalidate()
}

@UnstableApi
fun TimelineTemplateEditorActivity.showTransitionPickerDialog(clip: TimelineClip) {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 32, 48, 32)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#1F1F1F"))
            cornerRadius = 32f
        }
    }

    val title = TextView(this).apply {
        text = "TRANSITION SETTINGS"
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, 24)
    }
    container.addView(title)

    val currentType = clip.metadata["TRANSITION_TYPE"] ?: "NONE"
    val currentDuration = clip.metadata["TRANSITION_DURATION"]?.toFloatOrNull() ?: 1000f

    val types = listOf("NONE", "CROSSFADE", "FADE_BLACK", "SLIDE_LEFT", "SLIDE_RIGHT")
    val buttons = mutableListOf<com.google.android.material.button.MaterialButton>()
    
    var selectedType = currentType

    val buttonsContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, 24)
    }

    var dialogRef: androidx.appcompat.app.AlertDialog? = null

    types.forEach { type ->
        val btn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = type.replace("_", " ")
            textSize = 12f
            cornerRadius = 12
            strokeWidth = 2
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            
            fun updateStyle() {
                if (selectedType == type) {
                    strokeColor = ColorStateList.valueOf(Color.parseColor("#00D2D3"))
                    setTextColor(Color.parseColor("#00D2D3"))
                    setBackgroundColor(Color.parseColor("#1A00D2D3"))
                } else {
                    strokeColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.TRANSPARENT)
                }
            }
            updateStyle()
        }
        buttons.add(btn)
        buttonsContainer.addView(btn)
    }
    
    // Wire up refresh callback
    buttons.forEach { b ->
        val type = b.text.toString().replace(" ", "_")
        b.setOnClickListener {
            selectedType = type
            buttons.forEach { button ->
                val bType = button.text.toString().replace(" ", "_")
                if (selectedType == bType) {
                    button.strokeColor = ColorStateList.valueOf(Color.parseColor("#00D2D3"))
                    button.setTextColor(Color.parseColor("#00D2D3"))
                    button.setBackgroundColor(Color.parseColor("#1A00D2D3"))
                } else {
                    button.strokeColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
                    button.setTextColor(Color.WHITE)
                    button.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
    }
    container.addView(buttonsContainer)

    // Duration Label
    val durationLabel = TextView(this).apply {
        text = "Duration: ${String.format("%.1fs", currentDuration / 1000f)}"
        textSize = 13f
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 8)
    }
    container.addView(durationLabel)

    // Duration Seekbar
    val seek = android.widget.SeekBar(this).apply {
        max = 20
        progress = (currentDuration / 100).toInt().coerceIn(1, 20)
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: android.widget.SeekBar?, p1: Int, p2: Boolean) {
                val sec = p1.coerceAtLeast(1) / 10f
                durationLabel.text = "Duration: ${String.format("%.1fs", sec)}"
            }
            override fun onStartTrackingTouch(p0: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(p0: android.widget.SeekBar?) {}
        })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 24
        }
    }
    container.addView(seek)

    val applyBtn = com.google.android.material.button.MaterialButton(this).apply {
        text = "APPLY TRANSITION"
        setBackgroundColor(Color.parseColor("#00D2D3"))
        setTextColor(Color.BLACK)
        cornerRadius = 16
        setPadding(16, 16, 16, 16)
        
        setOnClickListener {
            saveHistory()
            if (selectedType == "NONE") {
                clip.metadata.remove("TRANSITION_TYPE")
                clip.metadata.remove("TRANSITION_DURATION")
            } else {
                clip.metadata["TRANSITION_TYPE"] = selectedType
                val durMs = (seek.progress.coerceAtLeast(1) * 100).toLong()
                clip.metadata["TRANSITION_DURATION"] = durMs.toString()
            }
            rebuildPlayerFromTimeline(currentTimeMs)
            binding.editorPreview.timelineView.invalidate()
            Toast.makeText(context, "Transition Updated", Toast.LENGTH_SHORT).show()
            dialogRef?.dismiss()
        }
    }
    container.addView(applyBtn)

    dialogRef = AppDialog.showCustomView(this, container)
}
