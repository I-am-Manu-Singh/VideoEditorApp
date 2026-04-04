package com.example.videoeditorapp.ui.editor

import androidx.annotation.DrawableRes

data class EditorOption(
        val id: String,
        @DrawableRes var iconRes: Int,
        var label: String,
        var value: Float = 0f,
        val minValue: Float = 0f,
        val maxValue: Float = 1f,
        val isSelected: Boolean = false,
        val type: OptionType = OptionType.ADJUSTMENT,
        val controlType: ControlType = ControlType.SLIDER
)

enum class ControlType {
    SLIDER,
    WHEEL,
    CIRCULAR
}

enum class OptionType {
    ADJUSTMENT, // Slider control
    EFFECT, // Toggle or intensity
    FILTER, // Toggle or intensity
    PROPERTY, // e.g. Speed, Volume
    ACTION // Triggers external dialog (Color, Font, etc.)
}
