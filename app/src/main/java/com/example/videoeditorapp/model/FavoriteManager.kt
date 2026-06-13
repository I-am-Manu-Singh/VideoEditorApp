package com.example.videoeditorapp.model

import android.content.Context
import android.content.SharedPreferences

object FavoriteManager {
    private const val PREF_NAME = "favorites_pref"
    private const val KEY_TEMPLATE_FAVORITES = "template_favorites"
    private const val KEY_EXPORT_FAVORITES = "export_favorites" // Renamed from project_favorites
    private const val KEY_TIMELINE_PROJECT_FAVORITES = "timeline_project_favorites"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // --- TEMPLATES ---
    fun isTemplateFavorite(context: Context, templateId: String): Boolean {
        val favorites =
                getPrefs(context).getStringSet(KEY_TEMPLATE_FAVORITES, emptySet()) ?: emptySet()
        return favorites.contains(templateId)
    }


    // --- EXPORTS (Video Files) ---
    fun isExportFavorite(context: Context, exportPath: String): Boolean {
        val favorites =
                getPrefs(context).getStringSet(KEY_EXPORT_FAVORITES, emptySet()) ?: emptySet()
        return favorites.contains(exportPath)
    }

    fun toggleExportFavorite(context: Context, exportPath: String) {
        val prefs = getPrefs(context)
        val favorites =
                prefs.getStringSet(KEY_EXPORT_FAVORITES, emptySet())?.toMutableSet()
                        ?: mutableSetOf()

        if (favorites.contains(exportPath)) {
            favorites.remove(exportPath)
        } else {
            favorites.add(exportPath)
        }

        prefs.edit().putStringSet(KEY_EXPORT_FAVORITES, favorites).apply()
    }

    fun getFavoriteExports(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_EXPORT_FAVORITES, emptySet()) ?: emptySet()
    }

    // --- TIMELINE PROJECTS ---
    fun isTimelineProjectFavorite(context: Context, projectId: String): Boolean {
        val favorites =
                getPrefs(context).getStringSet(KEY_TIMELINE_PROJECT_FAVORITES, emptySet())
                        ?: emptySet()
        return favorites.contains(projectId)
    }

    fun toggleTimelineProjectFavorite(context: Context, projectId: String) {
        val prefs = getPrefs(context)
        val favorites =
                prefs.getStringSet(KEY_TIMELINE_PROJECT_FAVORITES, emptySet())?.toMutableSet()
                        ?: mutableSetOf()

        if (favorites.contains(projectId)) {
            favorites.remove(projectId)
        } else {
            favorites.add(projectId)
        }

        prefs.edit().putStringSet(KEY_TIMELINE_PROJECT_FAVORITES, favorites).apply()
    }

    fun getFavoriteTimelineProjects(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_TIMELINE_PROJECT_FAVORITES, emptySet())
                ?: emptySet()
    }   
    fun removeTimelineProjectFavorite(
    context: Context,
    projectId: String
) {
    val prefs = getPrefs(context)

    val favorites =
        prefs.getStringSet(
            KEY_TIMELINE_PROJECT_FAVORITES,
            emptySet()
        )?.toMutableSet() ?: mutableSetOf()

    favorites.remove(projectId)

    prefs.edit()
        .putStringSet(
            KEY_TIMELINE_PROJECT_FAVORITES,
            favorites
        )
        .apply()
}

fun removeExportFavorite(
    context: Context,
    exportPath: String
) {
    val prefs = getPrefs(context)

    val favorites =
        prefs.getStringSet(
            KEY_EXPORT_FAVORITES,
            emptySet()
        )?.toMutableSet() ?: mutableSetOf()

    favorites.remove(exportPath)

    prefs.edit()
        .putStringSet(
            KEY_EXPORT_FAVORITES,
            favorites
        )
        .apply()
}

    @Deprecated("Use isExportFavorite", ReplaceWith("isExportFavorite(context, projectPath)"))
    fun isProjectFavorite(context: Context, projectPath: String) =
            isExportFavorite(context, projectPath)

    @Deprecated(
            "Use toggleExportFavorite",
            ReplaceWith("toggleExportFavorite(context, projectPath)")
    )
    fun toggleProjectFavorite(context: Context, projectPath: String) =
            toggleExportFavorite(context, projectPath)

    @Deprecated("Use getFavoriteExports", ReplaceWith("getFavoriteExports(context)"))
    fun getFavoriteProjects(context: Context) = getFavoriteExports(context)
    
   
}
