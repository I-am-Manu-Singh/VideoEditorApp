package com.example.videoeditorapp.utils

import android.content.Context
import android.os.Environment
import java.io.File

object StorageManager {

    // Calculate total size of a directory recursively
    fun getFolderSize(file: File): Long {
        if (!file.exists()) return 0
        if (!file.isDirectory) return file.length()

        var length: Long = 0
        val files = file.listFiles()
        if (files != null) {
            for (child in files) {
                length += getFolderSize(child)
            }
        }
        return length
    }

    // Format size to readable string
    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(
                "%.1f %s",
                size / Math.pow(1024.0, digitGroups.toDouble()),
                units[digitGroups]
        )
    }

    // --- CATEGORY: CACHE ---
    fun getCacheSize(context: Context): Long {
        return getFolderSize(context.cacheDir) + getFolderSize(context.externalCacheDir ?: File(""))
    }

    fun clearCache(context: Context) {
        deleteRecursive(context.cacheDir)
        deleteRecursive(context.externalCacheDir)
    }

    // --- CATEGORY: TEMP EXPORTS ---
    fun getTempExportsDir(context: Context): File {
        // Assuming exports are stored in getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        // Adjust path if your app stores them elsewhere
        return context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: File(context.filesDir, "movies")
    }

    fun getTempExportsSize(context: Context): Long {
        return getFolderSize(getTempExportsDir(context))
    }

    fun clearTempExports(context: Context) {
        // CAREFUL: Only delete temp files, maybe irrelevant ones?
        // For "Storage Management", deleting all exports might be what the user wants if they are
        // "Temp"
        // Or strictly cache files.
        // Let's assume we delete everything in the specific "Temp" folder if it exists.
        val tempDir = File(getTempExportsDir(context), "temp")
        if (tempDir.exists()) {
            deleteRecursive(tempDir)
        }
    }

    // --- CATEGORY: DOWNLOADED ASSETS ---
    fun getAssetsDir(context: Context): File {
        // Unifying with RemoteAssetManager expectation if needed, or keeping it clean
        val dir = File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDownloadsSize(context: Context): Long {
        return getFolderSize(getAssetsDir(context))
    }

    fun clearDownloads(context: Context) {
        deleteRecursive(getAssetsDir(context))
    }

    // --- CATEGORY: PROJECTS ---
    fun getProjectsDir(context: Context): File {
        val dir = File(context.filesDir, "projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getProjectsSize(context: Context): Long {
        return getFolderSize(getProjectsDir(context))
    }

    // --- CATEGORY: IMPORTED MEDIA (Sync with Timeline Activity) ---
    fun getImportedMediaDir(context: Context): File {
        // V4: We use a specific folder in external files for persistence and easy access
        val dir = File(context.getExternalFilesDir(null), "media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getImportedMediaSize(context: Context): Long {
        return getFolderSize(getImportedMediaDir(context))
    }

    fun clearImportedMedia(context: Context) {
        deleteRecursive(getImportedMediaDir(context))
    }

    /** Deletes a file robustly from both disk and MediaStore if applicable. */
    fun deleteFile(context: Context, file: File): Boolean {
        var deleted = false
        try {
            if (file.exists()) {
                deleted = file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Try deleting via MediaStore content resolver for scoped storage on Android 10+
        try {
            val resolver = context.contentResolver
            val uri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val numDeleted = resolver.delete(
                uri,
                "${android.provider.MediaStore.Video.Media.DATA} = ?",
                arrayOf(file.absolutePath)
            )
            if (numDeleted > 0) {
                deleted = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Also check if the file is an image or audio
        try {
            val resolver = context.contentResolver
            resolver.delete(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.Images.Media.DATA} = ?",
                arrayOf(file.absolutePath)
            )
            resolver.delete(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.Audio.Media.DATA} = ?",
                arrayOf(file.absolutePath)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return deleted || !file.exists()
    }

    // Helper to delete recursively
    private fun deleteRecursive(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursive(child)
                }
            }
        }
        file.delete()
    }
}
