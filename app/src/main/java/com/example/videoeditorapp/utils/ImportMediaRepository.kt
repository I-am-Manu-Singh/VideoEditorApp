package com.example.videoeditorapp.utils

import android.content.Context
import com.example.videoeditorapp.model.timeline.ClipType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.*


data class ImportedMediaItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val type: String,
    val name: String,
    val dateAdded: Long = System.currentTimeMillis(),
    val size: Long = 0L
)

object ImportMediaRepository {
    private const val HISTORY_FILE = "import_history.json"
    private val gson = Gson()
    private var cachedItems: MutableList<ImportedMediaItem>? = null

 fun addHistory(context: Context, item: ImportedMediaItem) {
    val items = getHistory(context).toMutableList()

    if (items.none { it.uri == item.uri }) {
        items.add(0, item)
        saveHistory(context, items)
    }
}

    fun getHistory(context: Context): List<ImportedMediaItem> {
        if (cachedItems != null) return cachedItems!!
        
        val file = File(context.filesDir, HISTORY_FILE)
        if (!file.exists()) return emptyList()
        
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<ImportedMediaItem>>() {}.type
            val items: MutableList<ImportedMediaItem> = gson.fromJson(json, type) ?: mutableListOf()
            cachedItems = items
            items
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveHistory(context: Context, items: List<ImportedMediaItem>) {
        cachedItems = items.toMutableList()
        try {
            val json = gson.toJson(items)
            File(context.filesDir, HISTORY_FILE).writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearHistory(context: Context) {
        cachedItems = mutableListOf()
        File(context.filesDir, HISTORY_FILE).delete()
    }
}
