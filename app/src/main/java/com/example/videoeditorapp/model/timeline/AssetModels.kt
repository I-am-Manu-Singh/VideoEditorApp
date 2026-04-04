package com.example.videoeditorapp.model.timeline

import android.os.Parcelable
import com.example.videoeditorapp.R
import java.util.UUID
import kotlinx.parcelize.Parcelize

@Parcelize
data class AssetItem(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val category: String, // "CINEMATIC", "GLITCH", "NATURE", "LO-FI", "SFX"
        val type: AssetType,
        val url: String,
        val thumbnailResId: Int? = null,
        val thumbnailUrl: String? = null,
        val isPremium: Boolean = false,
        var isDownloaded: Boolean = false,
        var localPath: String? = null,
        val downloads: Int = 0,
        val uses: Int = 0
) : Parcelable

@Parcelize
enum class AssetType : Parcelable {
        VIDEO_BROLL,
        VIDEO_OVERLAY,
        IMAGE_STICKER,
        AUDIO_MUSIC,
        AUDIO_SFX,
        EFFECT_PRESET
}

object AssetStore {
        /** Returns assets sorted by popularity score (downloads + uses). */
        fun getRecommendedAssets(limit: Int = 10): List<AssetItem> {
                return featuredAssets.sortedByDescending { it.downloads + it.uses }.take(limit)
        }

        val featuredAssets =
                listOf(
                        // OVERLAYS
                        AssetItem(
                                title = "Cinematic Particles",
                                category = "OVERLAY",
                                type = AssetType.VIDEO_OVERLAY,
                                url =
                                        "https://assets.mixkit.co/videos/preview/mixkit-dust-particles-in-the-air-under-a-light-beam-12345-large.mp4",
                                isPremium = true,
                                thumbnailResId = R.drawable.asset_landscape,
                                downloads = 12500,
                                uses = 8400
                        ),
                        AssetItem(
                                title = "Light Leaks",
                                category = "OVERLAY",
                                type = AssetType.VIDEO_OVERLAY,
                                url =
                                        "https://assets.mixkit.co/videos/preview/mixkit-light-leaks-on-a-film-camera-12346-large.mp4",
                                isPremium = false,
                                thumbnailResId = R.drawable.asset_landscape,
                                downloads = 8000,
                                uses = 5000
                        ),
                        // EFFECTS
                        AssetItem(
                                title = "Cyberpunk Glitch",
                                category = "EFFECTS",
                                type = AssetType.EFFECT_PRESET,
                                url = "https://mock/glitch.json",
                                isPremium = false,
                                thumbnailResId = R.drawable.asset_glitch,
                                downloads = 45000,
                                uses = 32000
                        ),
                        AssetItem(
                                title = "VHS Distortion",
                                category = "EFFECTS",
                                type = AssetType.EFFECT_PRESET,
                                url = "https://mock/vhs.json",
                                isPremium = true,
                                thumbnailResId = R.drawable.asset_cyberpunk,
                                downloads = 18000,
                                uses = 15000
                        ),
                        // MUSIC
                        AssetItem(
                                title = "Lo-Fi Study Beats",
                                category = "MUSIC",
                                type = AssetType.AUDIO_MUSIC,
                                url =
                                        "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                                isPremium = true,
                                thumbnailResId = R.drawable.asset_lofi,
                                downloads = 25000,
                                uses = 12000
                        ),
                        AssetItem(
                                title = "Midnight Jazz",
                                category = "MUSIC",
                                type = AssetType.AUDIO_MUSIC,
                                url =
                                        "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                                isPremium = false,
                                thumbnailResId = R.drawable.asset_lofi,
                                downloads = 8000,
                                uses = 3000
                        ),
                        AssetItem(
                                title = "Epic Orchestral",
                                category = "MUSIC",
                                type = AssetType.AUDIO_MUSIC,
                                url =
                                        "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
                                isPremium = true,
                                thumbnailResId = R.drawable.asset_lofi,
                                downloads = 14000,
                                uses = 9000
                        ),
                        // SFX
                        AssetItem(
                                title = "Deep Bass Drop",
                                category = "SFX",
                                type = AssetType.AUDIO_SFX,
                                url =
                                        "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
                                isPremium = true,
                                thumbnailResId = R.drawable.ic_audio_label,
                                downloads = 22000,
                                uses = 19000
                        ),
                        AssetItem(
                                title = "Whoosh Transition",
                                category = "SFX",
                                type = AssetType.AUDIO_SFX,
                                url =
                                        "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-10.mp3",
                                isPremium = false,
                                thumbnailResId = R.drawable.ic_audio_label,
                                downloads = 54000,
                                uses = 48000
                        ),
                        // STICKERS
                        AssetItem(
                                title = "Neon Heart",
                                category = "STICKERS",
                                type = AssetType.IMAGE_STICKER,
                                url = "res://ic_heart",
                                isPremium = true,
                                thumbnailResId = R.drawable.ic_heart,
                                downloads = 5000,
                                uses = 2000
                        ),
                        AssetItem(
                                title = "Golden Crown",
                                category = "STICKERS",
                                type = AssetType.IMAGE_STICKER,
                                url = "res://ic_crown",
                                isPremium = false,
                                thumbnailResId = R.drawable.ic_crown,
                                downloads = 12000,
                                uses = 9500
                        ),
                        // B-ROLL
                        AssetItem(
                                title = "City Timelapse",
                                category = "B-ROLL",
                                type = AssetType.VIDEO_BROLL,
                                url =
                                        "https://assets.mixkit.co/videos/preview/mixkit-city-traffic-at-night-time-lapse-12347-large.mp4",
                                isPremium = true,
                                thumbnailResId = R.drawable.asset_landscape,
                                downloads = 30000,
                                uses = 22000
                        )
                )
}
