package com.example.videoeditorapp.utils

import android.content.Context
import com.example.videoeditorapp.model.timeline.TimelineProject
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

object ProjectManager {
    private const val PROJECTS_DIR = "projects"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private fun getProjectsDir(context: Context): File {
        val dir = File(context.filesDir, PROJECTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** Persists the current project to internal storage as JSON. */
    fun saveProject(context: Context, project: TimelineProject) {
        val dir = getProjectsDir(context)
        val file = File(dir, "${project.id}.json")
        project.lastModified = System.currentTimeMillis()
        try {
            file.writeText(gson.toJson(project))

            // Track last edited project for "Resume" functionality
            context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_project_id", project.id)
                    .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Loads a project by ID. */
    fun loadProject(context: Context, projectId: String): TimelineProject? {
        val dir = getProjectsDir(context)
        val file = File(dir, "$projectId.json")
        if (!file.exists()) return null

        return try {
            gson.fromJson(file.readText(), TimelineProject::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Returns a list of all saved projects for the "My Projects" screen. */
    fun listProjects(context: Context): List<TimelineProject> {
        val dir = getProjectsDir(context)
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()

        return files
                .mapNotNull { file ->
                    try {
                        gson.fromJson(file.readText(), TimelineProject::class.java)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
                .sortedByDescending { it.lastModified }
    }

    fun deleteProject(context: Context, projectId: String): Boolean {
        val dir = getProjectsDir(context)
        val file = File(dir, "$projectId.json")
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
}
