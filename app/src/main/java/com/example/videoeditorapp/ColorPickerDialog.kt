package com.example.videoeditorapp

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import com.example.videoeditorapp.databinding.DialogColorPickerBinding

class ColorPickerDialog(
        context: Context,
        private val initialColor: Int,
        private val onColorSelected: (Int) -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogColorPickerBinding
    private var currentColor = initialColor
    private var isUpdatingFromHex = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogColorPickerBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        setupInitialState()
        setupListeners()
    }

    private fun setupInitialState() {
        updateSlidersFromColor(currentColor)
        updateHexAndPreview(currentColor)
    }

    private fun setupListeners() =
            with(binding) {
                val sliderListener =
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                    seekBar: SeekBar?,
                                    progress: Int,
                                    fromUser: Boolean
                            ) {
                                if (fromUser) {
                                    val r = seekRedDialog.progress
                                    val g = seekGreenDialog.progress
                                    val b = seekBlueDialog.progress
                                    currentColor = Color.rgb(r, g, b)
                                    isUpdatingFromHex = false // Manual slider overrides hex typing
                                    updateHexAndPreview(currentColor)
                                }
                            }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                        }

                seekRedDialog.setOnSeekBarChangeListener(sliderListener)
                seekGreenDialog.setOnSeekBarChangeListener(sliderListener)
                seekBlueDialog.setOnSeekBarChangeListener(sliderListener)

                etHex.addTextChangedListener(
                        object : TextWatcher {
                            override fun afterTextChanged(s: Editable?) {
                                if (isUpdatingFromHex) return

                                val hex = s.toString()
                                if (hex.length == 7 && hex.startsWith("#")) {
                                    try {
                                        val color = Color.parseColor(hex)
                                        currentColor = color
                                        // Update sliders without triggering loop
                                        updateSlidersFromColor(color)
                                        // Update preview
                                        binding.viewPreview.backgroundTintList =
                                                ColorStateList.valueOf(color)
                                    } catch (e: IllegalArgumentException) {
                                        // Invalid hex, ignore
                                    }
                                }
                            }
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
                            ) {}
                        }
                )

                btnCopyHex.setOnClickListener {
                    val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Hex Color", etHex.text.toString())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                }

                btnDone.setOnClickListener {
                    onColorSelected(currentColor)
                    dismiss()
                }
            }

    private fun updateSlidersFromColor(color: Int) =
            with(binding) {
                seekRedDialog.progress = Color.red(color)
                seekGreenDialog.progress = Color.green(color)
                seekBlueDialog.progress = Color.blue(color)
            }

    private fun updateHexAndPreview(color: Int) =
            with(binding) {
                viewPreview.backgroundTintList = ColorStateList.valueOf(color)
                val hex = String.format("#%06X", (0xFFFFFF and color))

                if (!etHex.isFocused) {
                    isUpdatingFromHex = true
                    etHex.setText(hex)
                    isUpdatingFromHex = false
                }
            }
}
