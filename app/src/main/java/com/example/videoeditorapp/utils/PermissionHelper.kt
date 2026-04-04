package com.example.videoeditorapp.utils

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.videoeditorapp.R

object PermissionHelper {

    const val REQUEST_NOTIFICATION = 1001
    const val REQUEST_MEDIA_READ = 1002

    /**
     * Checks and requests notification permission for Android 13+. Shows a custom rationale dialog.
     */
    fun checkNotificationPermission(activity: Activity, onGranted: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                            activity,
                            android.Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
            ) {
                onGranted()
            } else {
                // ALWAYS show custom dialog for Studio V4 feel
                showPermissionRationale(
                        activity,
                        "Notification Permission Required",
                        "Studio V4 needs notification permission to show export progress and completion alerts. This helps you track your video exports even when the app is in the background.",
                        android.Manifest.permission.POST_NOTIFICATIONS,
                        REQUEST_NOTIFICATION
                )
            }
        } else {
            onGranted()
        }
    }

    /** Checks and requests media read permissions for Android 13+ via custom dialog. */
    fun checkMediaPermissions(activity: Activity, onGranted: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions =
                    arrayOf(
                            android.Manifest.permission.READ_MEDIA_VIDEO,
                            android.Manifest.permission.READ_MEDIA_IMAGES,
                            android.Manifest.permission.READ_MEDIA_AUDIO
                    )

            val allGranted =
                    permissions.all {
                        ContextCompat.checkSelfPermission(activity, it) ==
                                PackageManager.PERMISSION_GRANTED
                    }

            if (allGranted) {
                onGranted()
            } else {
                showPermissionRationale(
                        activity,
                        "Media Access Required",
                        "Studio V4 needs access to your photos, videos, and audio files to create amazing content. Your privacy is protected - we only access files you explicitly select.",
                        permissions,
                        REQUEST_MEDIA_READ
                )
            }
        } else {
            onGranted()
        }
    }

    private fun showPermissionRationale(
            activity: Activity,
            title: String,
            message: String,
            permission: String,
            requestCode: Int
    ) {
        val dlg =
                com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(
                        activity.layoutInflater
                )
        dlg.tvTitle.text = title
        dlg.tvMessage.text = message
        dlg.btnPrimary.text = "Grant"
        dlg.btnSecondary.text = "Not Now"
        dlg.ivDialogIcon.setImageResource(R.drawable.ic_lock) // Generic permission icon

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity).setView(dlg.root).create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dlg.btnPrimary.setOnClickListener {
            dialog.dismiss()
            ActivityCompat.requestPermissions(activity, arrayOf(permission), requestCode)
        }
        dlg.btnSecondary.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showPermissionRationale(
            activity: Activity,
            title: String,
            message: String,
            permissions: Array<String>,
            requestCode: Int
    ) {
        val dlg =
                com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(
                        activity.layoutInflater
                )
        dlg.tvTitle.text = title
        dlg.tvMessage.text = message
        dlg.btnPrimary.text = "Grant"
        dlg.btnSecondary.text = "Not Now"
        dlg.ivDialogIcon.setImageResource(R.drawable.ic_lock)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity).setView(dlg.root).create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dlg.btnPrimary.setOnClickListener {
            dialog.dismiss()
            ActivityCompat.requestPermissions(activity, permissions, requestCode)
        }
        dlg.btnSecondary.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /**
     * Handles permission request results. Call this from onRequestPermissionsResult in your
     * Activity.
     */
    fun handlePermissionResult(
            requestCode: Int,
            grantResults: IntArray,
            onNotificationGranted: () -> Unit = {},
            onMediaGranted: () -> Unit = {},
            onDenied: (String) -> Unit = {}
    ) {
        when (requestCode) {
            REQUEST_NOTIFICATION -> {
                if (grantResults.isNotEmpty() &&
                                grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    onNotificationGranted()
                } else {
                    onDenied(
                            "Notification permission denied. Export progress won't be visible in notifications."
                    )
                }
            }
            REQUEST_MEDIA_READ -> {
                if (grantResults.isNotEmpty() &&
                                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                ) {
                    onMediaGranted()
                } else {
                    onDenied(
                            "Media access denied. You won't be able to import videos, images, or audio."
                    )
                }
            }
        }
    }
}
