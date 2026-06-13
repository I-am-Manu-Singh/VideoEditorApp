package com.example.videoeditorapp.utils

import android.content.Context
import android.util.TypedValue

object ViewUtils {

    fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        dp.toFloat(),
                        context.resources.displayMetrics
                )
                .toInt()
    }

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val s = totalSeconds % 60
        val m = (totalSeconds / 60) % 60
        val h = totalSeconds / 3600

        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    fun getRealPathFromURI(context: Context, contentUri: android.net.Uri): String? {
        val proj = arrayOf(android.provider.MediaStore.Images.Media.DATA)
        context.contentResolver.query(contentUri, proj, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            return cursor.getString(columnIndex)
        }
        return contentUri.path
    }
}
