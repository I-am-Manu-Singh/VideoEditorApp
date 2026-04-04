package com.example.videoeditorapp

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.videoeditorapp.databinding.ActivityExportDetailsBinding
import com.example.videoeditorapp.service.ExportService
import com.example.videoeditorapp.utils.setupEditorEdgeToEdge
import java.io.File

class ExportActivity : AppCompatActivity() {
    private lateinit var binding: ActivityExportDetailsBinding
    private var outputPath: String? = null
    private var savedIsPlaying: Boolean = true

    private var savedPlaybackPosition: Long = 0L
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityExportDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge()
        binding.btnBackContainer.setOnClickListener {
            finish()
        }

        if (savedInstanceState != null) {
            savedPlaybackPosition = savedInstanceState.getLong("playback_pos", 0L)
            savedIsPlaying = savedInstanceState.getBoolean("is_playing", true)
        }

        handleIntent(intent)
    }
    private fun applyStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = ContextCompat.getColor(this, R.color.background_primary)

        val isDarkMode =
                (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES

        WindowInsetsControllerCompat(window, window.decorView).apply {
            // Light theme → dark icons
            // Dark theme → light icons
            isAppearanceLightStatusBars = !isDarkMode
        }
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        exoPlayer?.let {
            outState.putLong("playback_pos", it.currentPosition)
            outState.putBoolean("is_playing", it.isPlaying)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        outputPath = intent?.getStringExtra(ExportService.EXTRA_OUTPUT_PATH)

        if (outputPath.isNullOrEmpty()) {
            Toast.makeText(this, "No exported file", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(outputPath!!)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "File missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindVideo(file)
        bindActions(file)
        setupClickListeners(file)

        // Get project name from intent
        val projectName =
                intent?.getStringExtra(ExportService.EXTRA_PROJECT_NAME)
                        ?: file.nameWithoutExtension
        bindDetails(file, projectName)
    }

    private var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    private fun bindVideo(file: File) {
        val uri = android.net.Uri.fromFile(file)

        exoPlayer =
                androidx.media3.exoplayer.ExoPlayer.Builder(this).build().apply {
                    binding.playerViewPreview.player = this
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
                    prepare()
                    seekTo(savedPlaybackPosition)
                    playWhenReady = savedIsPlaying

                    addListener(
                            object : androidx.media3.common.Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state == androidx.media3.common.Player.STATE_READY) {
                                        binding.seekVideoProgress.max = duration.toInt()
                                        binding.seekVideoProgress.removeCallbacks(progressRunnable)
                                        binding.seekVideoProgress.post(progressRunnable)
                                    } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                                        binding.imgPlayOverlay.visibility =
                                                android.view.View.VISIBLE
                                        binding.seekVideoProgress.removeCallbacks(progressRunnable)
                                    }
                                }

                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    binding.imgPlayOverlay.visibility =
                                            if (isPlaying) android.view.View.GONE
                                            else android.view.View.VISIBLE
                                }
                            }
                    )
                }

        binding.previewContainer.setOnClickListener {
            togglePlayback()
        }

        binding.imgPlayOverlay.setOnClickListener {
            togglePlayback()
        }

        binding.seekVideoProgress.setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: android.widget.SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        if (fromUser) {
                            exoPlayer?.seekTo(progress.toLong())
                            binding.tvCurrentTime.text = formatTime(progress)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                        exoPlayer?.pause()
                    }

                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                        exoPlayer?.play()
                    }
                }
        )
    }

    private fun togglePlayback() {
        exoPlayer?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                if (it.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    it.seekTo(0)
                }
                it.play()
            }
        }
    }


    private fun setupClickListeners(file: File) {
        binding.btnDel.setOnClickListener {
            val dlg = com.example.videoeditorapp.databinding.DialogBaseBinding.inflate(layoutInflater)

            dlg.tvTitle.text = "Delete Video?"
            dlg.tvMessage.text =
                "Are you sure you want to delete this exported video? This action cannot be undone."

            dlg.btnPrimary.text = "Delete"
            dlg.btnSecondary.text = "Cancel"

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setView(dlg.root)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dlg.btnPrimary.setOnClickListener {
                try {
                    if (file.exists()) {
                        val deleted = file.delete()
                        if (deleted) {
                            Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }

            dlg.btnSecondary.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun bindActions(file: File) {

    binding.btnOpenExternal.setOnClickListener {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
        )
    }

    binding.btnShare.setOnClickListener {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
        )
    }
//
//    binding.btnBackHome.setOnClickListener {
//        startActivity(
//                Intent(this, MainActivity::class.java).apply {
//                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
//                }
//        )
//        finish()
//    }
    }

    private fun bindDetails(file: File, projectName: String) {
    binding.tvFileName.text = projectName
    val sizeStr = formatFileSize(file.length())

    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(file.absolutePath)
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "0"
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "0"
        val rawDur =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                        ?: 0L
        val durStr = formatTime(rawDur.toInt())

        val bitrate =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLong() ?: 0L
        val bitrateKbps = bitrate / 1000
        val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.substringAfter("/") ?: "mp4"

        binding.tvMetaInfo.text = "${w}x${h} • $sizeStr • $durStr"

        // Populate Technical Panel
        binding.tvFormat.text = mime.uppercase()
        binding.tvBitrate.text = "${bitrateKbps} kbps"
        binding.tvResDetail.text = "${w} x ${h}"
        binding.tvSizeDetail.text = sizeStr

    } catch (e: Exception) {
        binding.tvMetaInfo.text = "Unknown • $sizeStr"
    } finally {
        mmr.release()
    }
    }

    private val progressRunnable =
        object : Runnable {
            override fun run() {
                exoPlayer?.let {
                    if (it.isPlaying) {
                        val pos = it.currentPosition.toInt()
                        binding.seekVideoProgress.progress = pos
                        binding.tvCurrentTime.text = formatTime(pos)
                    }
                }
                binding.seekVideoProgress.postDelayed(this, 500)
            }
        }
    private fun formatTime(ms: Int): String {
    val sec = (ms / 1000) % 60
    val min = (ms / 1000) / 60
    return "%02d:%02d".format(min, sec)
    }

    private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024f * 1024f)
    return "%.2f MB".format(mb)
    }
    override fun onDestroy() {
    super.onDestroy()
    binding.seekVideoProgress.removeCallbacks(progressRunnable)
    exoPlayer?.release()
    }
    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }
}
