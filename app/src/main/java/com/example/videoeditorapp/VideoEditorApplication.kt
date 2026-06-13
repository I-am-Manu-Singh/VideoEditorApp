package com.example.videoeditorapp

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class VideoEditorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val mode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
