package com.example.videoeditorapp.model.timeline

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*

@UnstableApi
object Media3EffectEngine {

    private val bitmapCache = mutableMapOf<String, android.graphics.Bitmap>()

    fun clearCache() {
        bitmapCache.clear()
    }

    /**
     * Converts a list of TimelineEffect models into Media3 GlEffect objects for ExoPlayer preview.
     */
    fun getPreviewEffects(effects: List<TimelineEffect>): List<GlEffect> {
        val media3Effects = mutableListOf<GlEffect>()

        effects.forEach { effect ->
            if (effect.category != EffectCategory.VIDEO) return@forEach
            when (effect.type.uppercase()) {
                "BRIGHTNESS" -> {
                    // Brightness using RgbMatrix color transformation
                    // intensity: 0.0 (dark) to 2.0 (bright), 1.0 = normal
                    val brightness = effect.intensity
                    media3Effects.add(
                            RgbMatrix { _, _ ->
                                floatArrayOf(
                                        brightness,
                                        0f,
                                        0f,
                                        0f, // Red channel
                                        0f,
                                        brightness,
                                        0f,
                                        0f, // Green channel
                                        0f,
                                        0f,
                                        brightness,
                                        0f, // Blue channel
                                        0f,
                                        0f,
                                        0f,
                                        1f // Alpha channel
                                )
                            }
                    )
                }
                "CONTRAST" -> {
                    // Contrast using RgbMatrix
                    // intensity: 0.0 (no contrast) to 2.0 (high contrast), 1.0 = normal
                    val contrast = effect.intensity
                    val offset = (1f - contrast) * 0.5f
                    media3Effects.add(
                            RgbMatrix { _, _ ->
                                floatArrayOf(
                                        contrast,
                                        0f,
                                        0f,
                                        offset, // Red channel
                                        0f,
                                        contrast,
                                        0f,
                                        offset, // Green channel
                                        0f,
                                        0f,
                                        contrast,
                                        offset, // Blue channel
                                        0f,
                                        0f,
                                        0f,
                                        1f // Alpha channel
                                )
                            }
                    )
                }
                "SATURATION" -> {
                    media3Effects.add(
                            HslAdjustment.Builder().adjustSaturation(effect.intensity).build()
                    )
                }
                "HUE" -> {
                    // intensity 0.0 to 1.0 -> 0 to 360 degrees
                    media3Effects.add(
                            HslAdjustment.Builder().adjustHue(effect.intensity * 360f).build()
                    )
                }
                "TEMPERATURE" -> {
                    // Approximation using RGB scaling
                    val r = 1.0f + (effect.intensity - 1.0f) * 0.2f
                    val b = 1.0f - (effect.intensity - 1.0f) * 0.2f
                    media3Effects.add(
                            RgbAdjustment.Builder().setRedScale(r).setBlueScale(b).build()
                    )
                }
                "GRAYSCALE" -> {
                    // Grayscale by averaging RGB channels
                    media3Effects.add(
                            RgbAdjustment.Builder()
                                    .setRedScale(0.33f)
                                    .setGreenScale(0.33f)
                                    .setBlueScale(0.33f)
                                    .build()
                    )
                }
                "SEPIA" -> {
                    // Sepia approximation using RGB adjustment
                    media3Effects.add(
                            RgbAdjustment.Builder()
                                    .setRedScale(1.2f)
                                    .setGreenScale(1.0f)
                                    .setBlueScale(0.8f)
                                    .build()
                    )
                }
                "BLUR" -> {
                    // media3 1.5.0 has GaussianBlur
                    media3Effects.add(GaussianBlur(effect.intensity * 20f))
                }
                "SHARPEN" -> {
                    // Basic Sharpen using RgbMatrix (boosts high frequency contrast)
                    val s = effect.intensity * 0.5f
                    media3Effects.add(
                            RgbMatrix { _, _ ->
                                floatArrayOf(
                                        1f + s,
                                        -s,
                                        0f,
                                        0f,
                                        -s,
                                        1f + s,
                                        0f,
                                        0f,
                                        0f,
                                        -s,
                                        1f + s,
                                        0f,
                                        0f,
                                        0f,
                                        0f,
                                        1f
                                )
                            }
                    )
                }
            }
        }
        return media3Effects
    }

    /**
     * Generates a combined list of effects for a clip, including its own filters and any
     * overlapping overlays (images/stickers/text) from other tracks.
     */
    fun getCombinedEffects(
            project: TimelineProject,
            baseClip: TimelineClip,
            overlays: List<TimelineClip>
    ): List<GlEffect> {
        val effects = mutableListOf<GlEffect>()

        // 1. Base Canvas Scale (Fit to project aspect ratio)
        val targetAspect = project.aspectWidth.toFloat() / project.aspectHeight.toFloat()
        effects.add(
                Presentation.createForAspectRatio(targetAspect, Presentation.LAYOUT_SCALE_TO_FIT)
        )

        // 2. Base Clip Effects (Brightness, Contrast, etc.)
        effects.addAll(getPreviewEffects(baseClip.effects))

        // 3. Overlay Effects (Images, Stickers) using BitmapOverlay
        overlays.forEach { overlay ->
            if (overlay.type == ClipType.IMAGE || overlay.type == ClipType.STICKER) {
                try {
                    var bitmap = bitmapCache[overlay.filePath]
                    if (bitmap == null) {
                        bitmap = android.graphics.BitmapFactory.decodeFile(overlay.filePath)
                        if (bitmap != null) {
                            bitmapCache[overlay.filePath] = bitmap
                        }
                    }

                    if (bitmap != null) {
                        val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(bitmap)
                        effects.add(
                                OverlayEffect(
                                        com.google.common.collect.ImmutableList.of<TextureOverlay>(
                                                bitmapOverlay
                                        )
                                )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return effects
    }
}
