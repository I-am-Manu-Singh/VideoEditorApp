
package com.example.videoeditorapp.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NamingUtils {

    /**
     * Generates a new project name like "Proj_A3D9".
     * Checks existing projects to avoid duplicates.
     */
    fun generateNewProjectName(context: Context): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val existingProjects = ProjectManager.listProjects(context)
        val existingNames = existingProjects.map { it.name.lowercase() }
        
        while (true) {
            val suffix = (1..4).map { chars.random() }.joinToString("")
            val candidate = "Proj_$suffix"
            if (!existingNames.contains(candidate.lowercase())) {
                return candidate
            }
        }
    }

    /**
     * Generates an export filename with automatic versioning and resolution.
     * Format: "ProjectName_v1_1080p.mp4"
     */
    fun generateExportFilename(
        context: Context,
        projectName: String,
        resolution: String // e.g., "1080p", "4K"
    ): String {
        val sanitizedProjectName = projectName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, "StudioV4")
        if (!appDir.exists()) appDir.mkdirs()

        var version = 1
        while (true) {
            val fileName = "${sanitizedProjectName}_v${version}_${resolution}.mp4"
            val file = File(appDir, fileName)
            if (!file.exists()) {
                return fileName
            }
            version++
        }
    }
    
    fun getExportDirectory(): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, "StudioV4")
        if (!appDir.exists()) appDir.mkdirs()
        return appDir
    }
}
