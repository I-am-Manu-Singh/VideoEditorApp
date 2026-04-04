package com.example.videoeditorapp.model

import android.content.Context
import android.content.SharedPreferences

object UsageManager {
    private const val PREF_NAME = "usage_pref"
    private const val KEY_RECENT_TEMPLATES = "recent_templates"
    private const val MAX_RECENT = 10

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun recordUsage(context: Context, templateId: String) {
        val prefs = getPrefs(context)
        val recentList = prefs.getString(KEY_RECENT_TEMPLATES, "") ?: ""
        val items = recentList.split(",").filter { it.isNotEmpty() }.toMutableList()

        // Remove if already exists to move it to the front
        items.remove(templateId)
        items.add(0, templateId)

        // Trim to max
        val trimmed = if (items.size > MAX_RECENT) items.take(MAX_RECENT) else items

        prefs.edit().putString(KEY_RECENT_TEMPLATES, trimmed.joinToString(",")).apply()
    }

    fun getRecentTemplates(context: Context): List<String> {
        val recentList = getPrefs(context).getString(KEY_RECENT_TEMPLATES, "") ?: ""
        return recentList.split(",").filter { it.isNotEmpty() }
    }
}
