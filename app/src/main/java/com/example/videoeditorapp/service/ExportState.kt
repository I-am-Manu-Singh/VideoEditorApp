package com.example.videoeditorapp.service

object ExportState {
    @Volatile var isRunning = false
    @Volatile var progress = 0
}