package com.example.videoeditorapp.data

import com.example.videoeditorapp.R
import com.example.videoeditorapp.model.HighlightItem

object TutorialRepository {

    fun getArticles(): List<HighlightItem> {
        return listOf(
            HighlightItem(
                "a1",
                "Cinematic Depth of Field",
                "FX • LENS",
                R.drawable.temp_cinematic
            ),
            HighlightItem(
                "a2",
                "Mastering the L-Cut",
                "AUDIO • FLOW",
                R.drawable.temp_news
            ),
            HighlightItem(
                "a3",
                "Color Theory Basics",
                "LUTS • MOOD",
                R.drawable.temp_lofi
            ),
            HighlightItem(
                "a4",
                "Speed Ramping Pro",
                "TIMING • FX",
                R.drawable.temp_text
            ),
            HighlightItem(
                "a5",
                "Advanced Multi-Layering",
                "LAYOUT • PRO",
                R.drawable.temp_glitch
            )
        )
    }
}