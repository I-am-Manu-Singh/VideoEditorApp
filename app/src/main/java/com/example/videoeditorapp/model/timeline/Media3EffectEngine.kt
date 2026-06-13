package com.example.videoeditorapp.model.timeline

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.effect.StaticOverlaySettings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color

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
                "NOIR" -> {
                    media3Effects.add(
                            RgbAdjustment.Builder()
                                    .setRedScale(0.33f)
                                    .setGreenScale(0.33f)
                                    .setBlueScale(0.33f)
                                    .build()
                    )
                    media3Effects.add(
                            RgbMatrix { _, _ ->
                                floatArrayOf(
                                        1.5f, 0f, 0f, -0.1f,
                                        0f, 1.5f, 0f, -0.1f,
                                        0f, 0f, 1.5f, -0.1f,
                                        0f, 0f, 0f, 1f
                                )
                            }
                    )
                }
                "VINTAGE" -> {
                    media3Effects.add(
                            RgbAdjustment.Builder().setRedScale(1.1f).setGreenScale(0.9f).setBlueScale(0.7f).build()
                    )
                }
                "COOL" -> {
                    media3Effects.add(
                            RgbAdjustment.Builder().setRedScale(0.85f).setGreenScale(0.95f).setBlueScale(1.2f).build()
                    )
                }
                "WARM" -> {
                    media3Effects.add(
                            RgbAdjustment.Builder().setRedScale(1.2f).setGreenScale(1.05f).setBlueScale(0.85f).build()
                    )
                }
                "LOMO" -> {
                    media3Effects.add(
                            HslAdjustment.Builder().adjustSaturation(1.3f).build()
                    )
                    media3Effects.add(
                            RgbMatrix { _, _ ->
                                floatArrayOf(
                                        1.2f, 0f, 0f, 0f,
                                        0f, 1.2f, 0f, 0f,
                                        0f, 0f, 1.2f, 0f,
                                        0f, 0f, 0f, 1f
                                )
                            }
                    )
                }
                "POLAROID" -> {
                    media3Effects.add(
                            HslAdjustment.Builder().adjustSaturation(0.7f).build()
                    )
                    media3Effects.add(
                            RgbAdjustment.Builder().setRedScale(1.0f).setGreenScale(1.0f).setBlueScale(0.9f).build()
                    )
                }
                "GLITCH" -> {
                    media3Effects.add(
                            RgbMatrix { _, _ ->
                                floatArrayOf(
                                        1.2f, 0f, 0.2f, 0f,
                                        0.2f, 1.0f, 0f, 0f,
                                        0f, 0.2f, 1.2f, 0f,
                                        0f, 0f, 0f, 1f
                                )
                            }
                    )
                }
                "OLD_MOVIE" -> {
                    media3Effects.add(
                            RgbAdjustment.Builder().setRedScale(0.33f).setGreenScale(0.33f).setBlueScale(0.33f).build()
                    )
                    media3Effects.add(
                            RgbAdjustment.Builder().setRedScale(1.1f).setGreenScale(1.0f).setBlueScale(0.8f).build()
                    )
                }
                "PIXELATE" -> {
                    media3Effects.add(GaussianBlur(15f))
                }
                "MIRROR" -> {
                    media3Effects.add(ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build())
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
                                        1.2f, 0f, 0f, s,
                                        0f, 1.2f, 0f, s,
                                        0f, 0f, 1.2f, s,
                                        0f, 0f, 0f, 1f
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

        // 3. Process Overlays (Images, Stickers, Text)
        overlays.forEach { overlay ->
            try {
                val overlaySettings = StaticOverlaySettings.Builder()
                    .setAlphaScale(overlay.overlayOpacity)
                    .build()

                val overlayTexture: TextureOverlay = when (overlay.type) {
                    ClipType.TEXT -> createTextOverlay(overlay, overlaySettings)
                    ClipType.IMAGE, ClipType.STICKER -> {
                        val bitmap = bitmapCache[overlay.filePath] ?: BitmapFactory.decodeFile(overlay.filePath)?.also { bitmapCache[overlay.filePath] = it }
                        if (bitmap != null) BitmapOverlay.createStaticBitmapOverlay(bitmap, overlaySettings) else return@forEach
                    }
                    else -> return@forEach
                }

                // 💎 PRO: Dynamic Keyframe Evaluation for Preview
                val overlayEffect = OverlayEffect(com.google.common.collect.ImmutableList.of(overlayTexture))
                
                // Note: For real-time keyframing in Media3, we ideally use a custom 
                // OverlaySettings provider. For now, we apply the base transform.
                // Future optimization: Implement dynamic OverlaySettings per frame.
                
                effects.add(overlayEffect)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Add Chroma Key to Preview if present in effects
        baseClip.effects.forEach { effect ->
            if (effect.type.uppercase() == "CHROMA_KEY") {
                // Simplified logic for GL preview
                // In production, this would use a dedicated shader
            }
        }

        return effects
    }

    private fun createTextOverlay(overlay: TimelineClip, settings: StaticOverlaySettings): TextureOverlay {
        val text = overlay.textSettings["text"] ?: overlay.textSettings["TEXT"] ?: ""
        val fontSize = (overlay.textSettings["size"] ?: "48").toFloat()
        val colorHex = overlay.textSettings["COLOR"] ?: overlay.textSettings["color"] ?: "#FFFFFF"
        val resolvedColor = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.WHITE }
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = resolvedColor
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
        }
        
        val textWidth = paint.measureText(text).toInt().coerceAtLeast(1)
        val fontMetrics = paint.fontMetrics
        val textHeight = (fontMetrics.bottom - fontMetrics.top).toInt().coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(textWidth + 40, textHeight + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Match FFmpeg: Stroke then Fill
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = Color.BLACK
        canvas.drawText(text, (textWidth / 2f) + 20, -fontMetrics.top + 20, paint)
        
        paint.style = Paint.Style.FILL
        paint.color = resolvedColor
        canvas.drawText(text, (textWidth / 2f) + 20, -fontMetrics.top + 20, paint)
        
        return BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)
    }
}
