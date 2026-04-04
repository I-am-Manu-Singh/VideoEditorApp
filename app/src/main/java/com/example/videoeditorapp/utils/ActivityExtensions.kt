package com.example.videoeditorapp.utils

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Applies Edge-to-Edge layout to the activity, handling status bar transparency and setting
 * appropriate paddings/margins for system bars.
 */
fun AppCompatActivity.setupEditorEdgeToEdge(toolbar: View? = null, previewContainer: View? = null) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.show(WindowInsetsCompat.Type.statusBars())

    // 1. Handle Toolbar padding (Top Inset)
    toolbar?.let { tb ->
        ViewCompat.setOnApplyWindowInsetsListener(tb) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            // Preserve existing horizontal padding
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // 2. Handle Root/Preview margins (Top/Bottom Insets)
    // If previewContainer is provided, we apply margins to IT or the ROOT depending on need.
    // Usually we update the root view to have top/bottom margins so content isn't obscured.
    // BUT for immersive editors, we might only want top margin on the toolbar and bottom on the
    // controls.
    // For consistency with TimelineTemplateEditorActivity, we'll apply margins to the provided view
    // (usually binding.root)
    previewContainer?.let { container ->
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                // We DON'T set topMargin here because the Toolbar handles it with topPadding
                // Setting it on the root would cause double-shifting
                bottomMargin = systemBars.bottom
            }
            insets
        }
    }
}

/**
 * Requests POST_NOTIFICATIONS permission for Android 13+ devices. Should be called before starting
 * an export or in onCreate of editor activities.
 */
fun AppCompatActivity.checkAndRequestNotificationPermission(requestCode: Int = 1001) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    requestCode
            )
        }
    }
}

fun Activity.applyNavigationDrawerInsets(navigationView: View) {
    ViewCompat.setOnApplyWindowInsetsListener(navigationView) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/**
 * Copies a Uri content to a persistent file in internal storage, but first checks if an identical
 * file (same name and size) already exists to avoid redundant storage usage.
 */
fun Activity.copyUriToUtilsStorage(uri: Uri, name: String): String? {
    val folder = File(filesDir, "imported_media")
    if (!folder.exists()) folder.mkdirs()

    // 1. Resolve original metadata (name/size) to check for existence
    var originalSize = -1L
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (cursor.moveToFirst() && sizeIndex != -1) {
            originalSize = cursor.getLong(sizeIndex)
        }
    }

    // 2. Check if we already have a file with this name and size in our folder
    // We search for files matching *_name and checking their length
    val existingFile =
            folder.listFiles()?.find {
                it.name.endsWith("_$name") && (originalSize == -1L || it.length() == originalSize)
            }

    if (existingFile != null) {
        Log.d("StorageOpt", "Reusing existing file: ${existingFile.name}")
        return existingFile.absolutePath
    }

    // 3. If not found, create a new unique file
    val fileName = "${System.currentTimeMillis()}_$name"
    val file = File(folder, fileName)

    return try {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        Log.d("StorageOpt", "Created new copy: ${file.name}")
        file.absolutePath
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}

/**
 * Sets up the resize handle for the preview pane using a Guideline. Supports both Portrait and
 * Landscape orientations.
 *
 * @param resizeHandle The handle view that the user drags.
 * @param rootView The root view (ConstraintLayout) that contains the guidelines.
 * @param portraitGuidelineId The resource ID of the guideline used in Portrait mode.
 * @param landscapeGuidelineId The resource ID of the guideline used in Landscape mode.
 */
fun Activity.setupSharedPreviewResize(
        resizeHandle: View?,
        rootView: View,
        portraitGuidelineId: Int,
        landscapeGuidelineId: Int
) {
    if (resizeHandle == null) return

    resizeHandle.setOnTouchListener(null)
    resizeHandle.visibility = View.VISIBLE

    val isLandscape =
            rootView.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val guidelineId = if (isLandscape) landscapeGuidelineId else portraitGuidelineId
    val guideline = rootView.findViewById<androidx.constraintlayout.widget.Guideline>(guidelineId)

    if (guideline != null) {
        resizeHandle.isClickable = true
        resizeHandle.isFocusable = true
        attachSharedResizeGesture(resizeHandle, rootView, guideline, isLandscape)
    }
}

private fun attachSharedResizeGesture(
        handle: View,
        rootView: View,
        guideline: androidx.constraintlayout.widget.Guideline,
        isLandscape: Boolean
) {
    handle.setOnTouchListener(
            object : View.OnTouchListener {
                private var lastX = 0f
                private var lastY = 0f
                private var isDragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastX = event.rawX
                            lastY = event.rawY
                            isDragging = true
                            v.performClick()
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isDragging) {
                                val dx = event.rawX - lastX
                                val dy = event.rawY - lastY
                                lastX = event.rawX
                                lastY = event.rawY

                                val parentSize =
                                        if (isLandscape) rootView.width else rootView.height
                                if (parentSize > 0) {
                                    val lp =
                                            guideline.layoutParams as
                                                    androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                                    val delta = if (isLandscape) dx else dy
                                    val change = delta / parentSize.toFloat()
                                    val newPercent = (lp.guidePercent + change).coerceIn(0.2f, 0.8f)
                                    lp.guidePercent = newPercent
                                    guideline.layoutParams = lp

                                    // Force layout update
                                    rootView.invalidate()
                                    rootView.requestLayout()
                                }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            isDragging = false
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            return true
                        }
                    }
                    return false
                }
            }
    )
}
