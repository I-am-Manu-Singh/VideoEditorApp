package com.example.videoeditorapp.model.timeline

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/** Handles professional project persistence for the Video Editor. */
object ProjectPersistenceManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private const val PROJECTS_DIR = "projects"
    private const val AUTO_SAVE_FILE = "autosave_project.json"

    /** List all saved projects, excluding the autosave file. */
    fun listProjects(context: Context): List<TimelineProject> {
        return try {
            val dir = File(context.filesDir, PROJECTS_DIR)
            if (!dir.exists()) return emptyList()

            dir
                    .listFiles { _, name -> name.endsWith(".json") && name != AUTO_SAVE_FILE }
                    ?.mapNotNull { file ->
                        val json = file.readText()
                        gson.fromJson(json, TimelineProject::class.java)
                    }
                    ?.sortedByDescending { it.lastModified }
                    ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Saves a project with its unique ID as filename. */
    fun saveProject(context: Context, project: TimelineProject) {
        val fileName = "${project.id}.json"
        try {
            val dir = File(context.filesDir, PROJECTS_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, fileName)
            val json = gson.toJson(project)
            file.writeText(json)

            project.lastModified = System.currentTimeMillis()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Loads a specific project by ID. */
    fun loadProject(context: Context, projectId: String): TimelineProject? {
        return try {
            val file = File(File(context.filesDir, PROJECTS_DIR), "$projectId.json")
            if (!file.exists()) return null

            val json = file.readText()
            gson.fromJson(json, TimelineProject::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Deletes a project by ID. */
    fun deleteProject(context: Context, projectId: String) {
        val file = File(File(context.filesDir, PROJECTS_DIR), "$projectId.json")
        if (file.exists()) file.delete()
    }

    /** Saves a project to the auto-save slot. */
    fun saveAutoSave(context: Context, project: TimelineProject) {
        try {
            val dir = File(context.filesDir, PROJECTS_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, AUTO_SAVE_FILE)
            val json = gson.toJson(project)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Loads the auto-saved project if it exists. */
    fun loadAutoSave(context: Context): TimelineProject? {
        return try {
            val file = File(File(context.filesDir, PROJECTS_DIR), AUTO_SAVE_FILE)
            if (!file.exists()) return null

            val json = file.readText()
            gson.fromJson(json, TimelineProject::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Deletes the auto-save file. */
    fun clearAutoSave(context: Context) {
        val file = File(File(context.filesDir, PROJECTS_DIR), AUTO_SAVE_FILE)
        if (file.exists()) file.delete()
    }
}
