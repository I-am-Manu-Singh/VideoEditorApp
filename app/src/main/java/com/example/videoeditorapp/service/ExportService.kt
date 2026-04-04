package com.example.videoeditorapp.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.example.videoeditorapp.ExportActivity
import com.example.videoeditorapp.R
import java.io.File

class ExportService : Service() {
    private var currentSessionId: Long = -1L
    private var projectName: String = ""
    companion object {
        const val CHANNEL_ID = "export_channel"
        const val NOTIF_ID = 101
        const val FINAL_NOTIF_ID = 102

        const val ACTION_START = "START_EXPORT"
        const val ACTION_CANCEL = "CANCEL_EXPORT"

        const val EXTRA_CMD = "CMD"
        const val EXTRA_DURATION = "DURATION"
        const val EXTRA_OUTPUT_PATH = "OUTPUT_PATH"

        const val ACTION_EXPORT_DONE = "com.example.videoeditorapp.EXPORT_DONE"
        const val EXTRA_SUCCESS = "SUCCESS"
        const val EXTRA_PROJECT_NAME = "PROJECT_NAME"
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startExport(intent)
            ACTION_CANCEL -> cancelExport(intent)
        }
        return START_NOT_STICKY
    }

    // -------------------- EXPORT --------------------

    private var exportStartTime: Long = 0L

    private fun startExport(intent: Intent) {
        val durationMs = intent.getLongExtra(EXTRA_DURATION, 1L).coerceAtLeast(1L)
        val outputPath =
                intent.getStringExtra(EXTRA_OUTPUT_PATH)
                        ?: run {
                            Log.e("ExportService", "Missing output path")
                            return
                        }
        val cmdArray = intent.getStringArrayExtra(EXTRA_CMD)
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "Project"

        Log.d("ExportService", "Starting Export: $projectName")
        Log.d("ExportService", "Output: $outputPath")
        Log.d("ExportService", "Project Duration: $durationMs ms")

        ExportState.isRunning = true
        ExportState.progress = 0
        exportStartTime = System.currentTimeMillis()

        acquireWakeLock()
        try {
            startForeground(NOTIF_ID, buildProgressNotification(0, projectName, "Started..."))
        } catch (e: Exception) {
            Log.e("ExportService", "Error starting foreground service: ${e.message}")
        }

        val callback: (com.antonkarpenko.ffmpegkit.Session) -> Unit = { session ->
            val success = ReturnCode.isSuccess(session.returnCode)
            Log.d(
                    "ExportService",
                    "FFmpeg Session Finished. ID: ${session.sessionId}, Success: $success, Code: ${session.returnCode}"
            )

            ExportState.isRunning = false
            ExportState.progress = 100

            if (success) {
                Log.i("ExportService", "Export SUCCESSFUL: $outputPath")
                notifyExportDone(outputPath, true, projectName)
                showFinalNotification(outputPath, projectName)
            } else {
                val logs = session.allLogsAsString
                Log.e("ExportService", "!!! FFmpeg FAILED !!!")
                Log.e("ExportService", "Full Logs: $logs")
                Log.e("ExportService", "Return Code: ${session.returnCode}")
                Log.e("ExportService", "Fail Stack Trace: ${session.failStackTrace}")

                notifyExportDone(outputPath, false, projectName)
                showFailureNotification(projectName)
            }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        val progressCallback: (com.antonkarpenko.ffmpegkit.Statistics) -> Unit = { stats ->
            if (ExportState.isRunning) {
                val percent = ((stats.time * 100) / durationMs).toInt().coerceIn(0, 99)
                if (ExportState.progress != percent) {
                    ExportState.progress = percent
                    Log.d(
                            "ExportService",
                            "Progress: $percent% (Time: ${stats.time}ms, Size: ${stats.size} bytes)"
                    )

                    val elapsedMs = System.currentTimeMillis() - exportStartTime
                    val statusText = calculateETA(percent, elapsedMs)

                    getSystemService(NotificationManager::class.java)
                            .notify(
                                    NOTIF_ID,
                                    buildProgressNotification(percent, projectName, statusText)
                            )
                }
            }
        }

        if (cmdArray != null) {
            val file = File(outputPath)
            val parent = file.parentFile
            Log.d("ExportService", "Output file parent exists: ${parent?.exists()}")
            Log.d("ExportService", "Output file parent writable: ${parent?.canWrite()}")
            Log.d(
                    "ExportService",
                    "External storage state: ${android.os.Environment.getExternalStorageState()}"
            )

            Log.d("ExportService", "--- FFmpeg Arguments ---")
            cmdArray.forEachIndexed { index, arg ->
                Log.d("ExportService", "Arg #$index: \"$arg\"")
            }
            Log.d("ExportService", "------------------------")

            val cmd =
                    cmdArray.joinToString(" ") { arg ->
                        if (arg.contains(" ") ||
                                        arg.contains("[") ||
                                        arg.contains(";") ||
                                        arg.contains("'")
                        ) {
                            if (arg.startsWith("\"") && arg.endsWith("\"")) arg
                            else "\"${arg.replace("\"", "\\\"")}\""
                        } else arg
                    }
            Log.d("ExportService", "Executing FFmpeg Command (Length: ${cmd.length}): $cmd")
            FFmpegKit.executeAsync(
                            cmd,
                            callback,
                            { entry ->
                                val level =
                                        when (entry.level) {
                                            com.antonkarpenko.ffmpegkit.Level.AV_LOG_ERROR ->
                                                    Log.ERROR
                                            com.antonkarpenko.ffmpegkit.Level.AV_LOG_WARNING ->
                                                    Log.WARN
                                            com.antonkarpenko.ffmpegkit.Level.AV_LOG_INFO ->
                                                    Log.INFO
                                            else -> Log.DEBUG
                                        }
                                Log.println(level, "FFmpegRaw", entry.message)
                            },
                            progressCallback
                    )
                    .also { session ->
                        currentSessionId = session.sessionId
                        Log.d("ExportService", "Session Started with ID: $currentSessionId")
                    }
        } else {
            Log.e("ExportService", "CRITICAL: Command array is NULL")
            ExportState.isRunning = false
            stopSelf()
        }
    }

    private fun calculateETA(percent: Int, elapsedMs: Long): String {
        if (percent <= 0) return "Estimating..."

        val totalEstimatedTimeMs = (elapsedMs * 100) / percent
        val remainingMs = totalEstimatedTimeMs - elapsedMs

        val elapsedSec = elapsedMs / 1000
        val remainingSec = remainingMs / 1000

        return "Elapsed: ${formatSec(elapsedSec)} | ETA: ${formatSec(remainingSec)}"
    }

    private fun formatSec(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }

    private fun buildProgressNotification(
            progress: Int,
            projectName: String,
            status: String
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_app_logo)
                .setContentTitle("Exporting: $projectName")
                .setContentText(status)
                .setSubText("$progress%")
                .setProgress(100, progress, false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
    }

    // -------------------- CANCEL --------------------

    private fun cancelExport(intent: Intent) {
        if (currentSessionId != -1L) {
            FFmpegKit.cancel(currentSessionId)
            currentSessionId = -1L
        }

        ExportState.isRunning = false
        ExportState.progress = 0

        val path = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        if (!path.isNullOrEmpty()) {
            File(path).delete()
        }

        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)

        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "Project"
        showCancelNotification(projectName)

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------- NOTIFICATIONS --------------------

    private fun notifyExportDone(path: String, success: Boolean, projectName: String) {
        sendBroadcast(
                Intent(ACTION_EXPORT_DONE).apply {
                    setPackage(packageName) // 🚀 EXPLICIT intent for restricted receivers
                    putExtra(EXTRA_OUTPUT_PATH, path)
                    putExtra(EXTRA_SUCCESS, success)
                    putExtra(EXTRA_PROJECT_NAME, projectName)
                }
        )
    }

    private fun showFinalNotification(path: String, projectName: String) {
        val openIntent =
                Intent(this, ExportActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_OUTPUT_PATH, path)
                    putExtra(EXTRA_PROJECT_NAME, projectName)
                }

        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        path.hashCode(),
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        val notification =
                NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_app_logo)
                        .setContentTitle("Export Completed")
                        .setContentText("Project: $projectName")
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()

        getSystemService(NotificationManager::class.java).notify(FINAL_NOTIF_ID, notification)
    }

    private fun showFailureNotification(projectName: String) {
        val notification =
                NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_app_logo)
                        .setContentTitle("Export Failed")
                        .setContentText("Project: $projectName")
                        .setStyle(
                                NotificationCompat.BigTextStyle()
                                        .bigText(
                                                "An error occurred during export. Please try again."
                                        )
                        )
                        .setAutoCancel(true)
                        .build()

        getSystemService(NotificationManager::class.java).notify(FINAL_NOTIF_ID + 2, notification)
    }

    private fun showCancelNotification(projectName: String) {
        val notification =
                NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_app_logo)
                        .setContentTitle("Export Cancelled")
                        .setContentText("Project: $projectName")
                        .setAutoCancel(true)
                        .build()

        getSystemService(NotificationManager::class.java).notify(FINAL_NOTIF_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                            CHANNEL_ID,
                            "Video Export",
                            NotificationManager.IMPORTANCE_LOW
                    )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            wakeLock =
                    powerManager.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK,
                            "VideoEditorApp:ExportService"
                    )
            wakeLock?.setReferenceCounted(false)
        }
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hour timeout just in case
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }
}
