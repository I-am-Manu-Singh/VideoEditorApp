package com.example.videoeditorapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.drawable.toBitmap
import android.webkit.MimeTypeMap
import android.media.MediaMetadataRetriever

object AssetUtils {

    /**
     * Helper to robustly detect if a URI is audio, video, or image.
     */
    fun getUriMediaType(context: Context, uri: android.net.Uri): String {
        val mime = context.contentResolver.getType(uri)
        if (mime != null) {
            if (mime.startsWith("audio/")) return "audio"
            if (mime.startsWith("image/")) return "image"
            if (mime.startsWith("video/")) return "video"
        }
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase()
        if (ext != null) {
            val mimeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (mimeFromExt != null) {
                if (mimeFromExt.startsWith("audio/")) return "audio"
                if (mimeFromExt.startsWith("image/")) return "image"
                if (mimeFromExt.startsWith("video/")) return "video"
            }
            val audioExts = listOf("mp3", "wav", "m4a", "ogg", "aac", "flac")
            val imageExts = listOf("jpg", "jpeg", "png", "webp", "gif")
            val videoExts = listOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
            if (ext in audioExts) return "audio"
            if (ext in imageExts) return "image"
            if (ext in videoExts) return "video"
        }
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            val hasVideo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            mmr.release()
            if (hasVideo) return "video"
            if (hasAudio) return "audio"
        } catch (e: Exception) {}
        return "video"
    }

    /**
     * Resolves a virtual path (e.g. emoji:// or res://) to a physical cached file path. If the path
     * is already a file path, checks existence and returns it.
     */
    fun getCachedAssetPath(context: Context, path: String): String? {
        return try {
            when {
                path.startsWith("emoji://") -> {
                    val emojiChar = path.removePrefix("emoji://")
                    val cacheFile = File(context.cacheDir, "emoji_${emojiChar.hashCode()}.png")

                    if (!cacheFile.exists()) {
                        generateEmojiImage(context, emojiChar, cacheFile)
                    }
                    cacheFile.absolutePath
                }
                path.startsWith("res://") -> {
                    // Resolve resource URI to a cached file for FFmpeg compatibility
                    val resString = path.removePrefix("res://")
                    val resId = resString.toIntOrNull() ?: context.resources.getIdentifier(
                        resString,
                        "drawable",
                        context.packageName
                    )
                    
                    if (resId != 0) {
                        val cacheFile = File(context.cacheDir, "res_${resId}.png")
                        if (!cacheFile.exists()) {
                            try {
                                context.resources.openRawResource(resId).use { input ->
                                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                                }
                            } catch (e: Exception) {
                                // If it's a vector drawable, openRawResource might fail or return XML
                                // For simplicity here, we assume it's a bitmap or we need to render it
                                val drawable = ContextCompat.getDrawable(context, resId)
                                drawable?.toBitmap()?.let { bitmap ->
                                    FileOutputStream(cacheFile).use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                }
                            }
                        }
                        cacheFile.absolutePath
                    } else null
                }
                path.startsWith("content://") -> {
                    // Resolve content URI to a cached file
                    val uri = android.net.Uri.parse(path)
                    val fileName = "cached_content_${path.hashCode()}"
                    val cacheFile = File(context.cacheDir, fileName)

                    // Robust copy for FFmpeg
                    if (!cacheFile.exists() || cacheFile.length() == 0L) {
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            return null
                        }
                    }
                    if (cacheFile.exists()) cacheFile.absolutePath else null
                }
                path.startsWith("http://") || path.startsWith("https://") -> {
                    path
                }
                else -> {
                    val file = File(path)
                    if (file.exists()) path else null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun generateEmojiImage(context: Context, emoji: String, outFile: File) {
        // High resolution for video
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Transparent background
        canvas.drawColor(Color.TRANSPARENT)

        val paint =
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = size * 0.8f
                    textAlign = Paint.Align.CENTER
                }

        // Vertically center logic
        val xPos = (canvas.width / 2).toFloat()
        val yPos = (canvas.height / 2 - (paint.descent() + paint.ascent()) / 2)

        canvas.drawText(emoji, xPos, yPos, paint)

        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }
}
