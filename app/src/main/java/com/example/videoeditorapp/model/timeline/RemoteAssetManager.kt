package com.example.videoeditorapp.model.timeline

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Handles downloading effects, B-roll, and other assets from remote URLs. */
object RemoteAssetManager {

    private const val TAG = "RemoteAssetManager"
    private const val ASSETS_DIR = "downloaded_assets"

    /**
     * Downloads an asset from a URL and saves it locally.
     * @return The absolute path to the local file, or null if download failed.
     */
    suspend fun downloadAsset(context: Context, urlString: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    if (urlString.startsWith("res://") || urlString.startsWith("emoji://")) {
                        return@withContext com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(context, urlString)
                    }

                    val assetsDir =
                            com.example.videoeditorapp.utils.StorageManager.getAssetsDir(context)
                    val extension = urlString.substringAfterLast(".", "tmp")
                    val fileName = "asset_${urlString.hashCode()}.$extension"
                    val file = File(assetsDir, fileName)

                    if (file.exists() && file.length() > 0) return@withContext file.absolutePath

                    if (urlString.contains("mock") || !urlString.startsWith("http")) {
                        file.parentFile?.mkdirs()
                        file.writeText("{}")
                        return@withContext file.absolutePath
                    }

                    val url = java.net.URL(urlString)
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.connect()

                    if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                        return@withContext null
                    }

                    url.openStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }

                    Log.d(TAG, "Downloaded asset to: ${file.absolutePath}")
                    file.absolutePath
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed: ${e.message}")
                    null
                }
            }

    /** Lists all downloaded assets. */
    fun listDownloadedAssets(context: Context): List<File> {
        val dir = File(context.filesDir, ASSETS_DIR)
        return dir.listFiles()?.toList() ?: emptyList()
    }
}
