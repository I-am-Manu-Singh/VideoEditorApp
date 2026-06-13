package com.example.videoeditorapp.utils

import com.example.videoeditorapp.R
import com.example.videoeditorapp.data.TutorialRepository
import com.example.videoeditorapp.model.HighlightItem
import com.example.videoeditorapp.model.timeline.AssetStore

object HomeContentRepository {

    fun getFeaturedTutorials(): List<HighlightItem> =
        TutorialRepository.getArticles().take(5)

    fun getTrendingAssets(): List<HighlightItem> {
        return AssetStore.getRecommendedAssets(4).map { asset ->
            HighlightItem(
                id = asset.id,
                title = asset.title,
                tag = asset.category,
                imageRes = asset.thumbnailResId ?: R.drawable.ic_magic
            )
        }
    }
}