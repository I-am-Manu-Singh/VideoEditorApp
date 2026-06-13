package com.example.videoeditorapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.videoeditorapp.utils.ProjectManager
import com.example.videoeditorapp.model.timeline.TimelineProject
import com.example.videoeditorapp.model.timeline.TrackType
import com.example.videoeditorapp.model.timeline.ClipType
import android.graphics.Bitmap


import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Build
import com.example.videoeditorapp.utils.AppDialog
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
import com.example.videoeditorapp.model.FavoriteManager
import java.io.File

class ExportActivity : AppCompatActivity() {

    private var isExporting = false
    private var project: TimelineProject? = null
    private var lastFrameTime = 0L

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.getIntExtra("PROGRESS", 0) ?: 0
            val log = intent?.getStringExtra("LOG")
            val status = intent?.getStringExtra("STATUS") ?: "ESTIMATING_TIME..."
            val isFinished = intent?.getBooleanExtra("FINISHED", false) ?: false
            val statsTime = intent?.getLongExtra("TIME_MS", 0L) ?: 0L

            updateProgress(progress, status)
            log?.let { addLog(it) }

            val now = System.currentTimeMillis()
            if (now - lastFrameTime > 250L && statsTime > 0L) {
                lastFrameTime = now
                extractFrameAtTime(statsTime)
            }

            if (isFinished) {
                isExporting = false
                switchToCompletedState()
                
                val file = File(outputPath ?: "")
                if (file.exists() && file.length() > 0L) {
                    bindVideo(file)
                    val projectNameExtra = intent?.getStringExtra(ExportService.EXTRA_PROJECT_NAME) ?: file.nameWithoutExtension
                    bindDetails(file, projectNameExtra)
                    bindActions(file)
                    setupClickListeners(file)
                }
            }
        }
    }

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

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            progressReceiver,
            android.content.IntentFilter("ACTION_EXPORT_PROGRESS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
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

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            progressReceiver,
            android.content.IntentFilter("ACTION_EXPORT_PROGRESS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun handleIntent(intent: Intent?) {
        outputPath = intent?.getStringExtra(ExportService.EXTRA_OUTPUT_PATH)
        isExporting = intent?.getBooleanExtra("EXTRA_IS_EXPORTING", false) ?: false
        val projectId = intent?.getStringExtra("EXTRA_PROJECT_ID")

        if (isExporting) {
            switchToExportingState()
            if (projectId != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    project = ProjectManager.loadProject(this@ExportActivity, projectId)
                }
            }
            return
        }

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
                                        updateProgressText()
                                        binding.seekVideoProgress.removeCallbacks(progressRunnable)
                                        binding.seekVideoProgress.post(progressRunnable)
                                    } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                                        binding.imgPlayOverlay?.visibility =
                                                android.view.View.VISIBLE
                                        binding.seekVideoProgress.removeCallbacks(progressRunnable)
                                        binding.seekVideoProgress.progress = duration.toInt()
                                        updateProgressText()
                                    }
                                }

                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    binding.imgPlayOverlay?.visibility =
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
                            updateProgressText()
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
        binding.actionDelete?.tvLabel?.text = "DELETE"
        binding.actionDelete?.ivIcon?.setImageResource(R.drawable.ic_delete)
        binding.actionDelete?.ivIcon?.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF5252"))
        binding.actionDelete?.root?.setOnClickListener {
            AppDialog.showDelete(
                context = this,
                title = "Delete Video",
                message = "Are you sure you want to delete this exported video?\n\nThis action cannot be undone.",
                iconRes = R.drawable.ic_delete,
                onDelete = {
                    try {
                        if (com.example.videoeditorapp.utils.StorageManager.deleteFile(this, file)) {
                            FavoriteManager.removeExportFavorite(this, file.absolutePath)
                            Toast.makeText(
                                this,
                                "Video deleted",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        } else {
                            AppDialog.showInfo(
                                context = this,
                                title = "Delete Failed",
                                message = "Failed to delete file."
                            )
                        }
                    } catch (e: Exception) {
                        AppDialog.showInfo(
                            context = this,
                            title = "Delete Failed",
                            message = e.message ?: "Delete failed."
                        )
                    }
                }
            )
        }
    }

    private fun bindActions(file: File) {
        binding.actionGallery?.tvLabel?.text = "GALLERY"
        binding.actionGallery?.ivIcon?.setImageResource(R.drawable.ic_play)
        binding.actionGallery?.root?.setOnClickListener {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
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

    private fun updateProgressText() {
        val player = exoPlayer ?: return
        val pos = player.currentPosition.toInt()
        val dur = player.duration.toInt().coerceAtLeast(0)
        binding.tvCurrentTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
    }

    private val progressRunnable =
        object : Runnable {
            override fun run() {
                exoPlayer?.let {
                    val pos = it.currentPosition.toInt()
                    binding.seekVideoProgress.progress = pos
                    updateProgressText()
                }
                binding.seekVideoProgress.postDelayed(this, 250)
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
    
    private fun switchToExportingState() {
        binding.tvToolbarTitle?.text = "Exporting Video"
        
        // Hide success/completed UI elements
        binding.cardSuccessBadge?.visibility = View.GONE
        binding.cardTechSpecs?.visibility = View.GONE
        binding.cardUnifiedActions?.visibility = View.GONE
        binding.previewContainer?.visibility = View.VISIBLE
        binding.playerViewPreview?.visibility = View.GONE
        binding.imgPlayOverlay?.visibility = View.GONE
        binding.playerControls?.visibility = View.GONE
        
        // Show exporting progress elements
        binding.ivExportPreview?.visibility = View.VISIBLE
        binding.layoutExportingProgress?.visibility = View.VISIBLE
        
        binding.progressCircular?.progress = 0
        binding.tvProgressPercent?.text = "0%"
        binding.tvTimeRemaining?.text = "INITIALIZING..."
        binding.tvEngineLogs?.text = "[SYSTEM] Starting export session..."
        
        // Set up cancel button
        binding.btnCancelExport?.setOnClickListener {
            val cancelIntent = Intent(this, ExportService::class.java).apply {
                action = ExportService.ACTION_CANCEL
                putExtra(ExportService.EXTRA_OUTPUT_PATH, outputPath)
                putExtra(ExportService.EXTRA_PROJECT_NAME, intent?.getStringExtra(ExportService.EXTRA_PROJECT_NAME) ?: "Project")
            }
            startService(cancelIntent)
            finish()
        }
    }

    private fun switchToCompletedState() {
        binding.tvToolbarTitle?.text = "Export Details"
        
        // Hide exporting progress elements
        binding.layoutExportingProgress?.visibility = View.GONE
        binding.ivExportPreview?.visibility = View.GONE
        
        // Show success/completed UI elements
        binding.cardSuccessBadge?.visibility = View.VISIBLE
        binding.cardTechSpecs?.visibility = View.VISIBLE
        binding.cardUnifiedActions?.visibility = View.VISIBLE
        binding.playerViewPreview?.visibility = View.VISIBLE
        binding.playerControls?.visibility = View.VISIBLE
        
        // Populate share labels
        binding.shareTikTok?.tvLabel?.text = "TIKTOK"
        binding.shareInstagram?.tvLabel?.text = "INSTAGRAM"
        binding.shareWhatsApp?.tvLabel?.text = "WHATSAPP"
        binding.shareYouTube?.tvLabel?.text = "YOUTUBE"
        
        // Set up share listeners for target platforms
        val file = File(outputPath ?: "")
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareClick = View.OnClickListener {
                startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
            binding.shareTikTok?.root?.setOnClickListener(shareClick)
            binding.shareInstagram?.root?.setOnClickListener(shareClick)
            binding.shareWhatsApp?.root?.setOnClickListener(shareClick)
            binding.shareYouTube?.root?.setOnClickListener(shareClick)
        }
    }

    private fun updateProgress(progress: Int, statusText: String) {
        binding.progressCircular?.progress = progress
        binding.tvProgressPercent?.text = "$progress%"
        binding.tvTimeRemaining?.text = statusText
    }

    private fun addLog(message: String) {
        binding.tvEngineLogs?.append("\n[ENGINE] $message")
        binding.logScrollView?.post {
            binding.logScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun extractFrameAtTime(timeMs: Long) {
        val proj = project ?: return
        val videoTracks = proj.tracks.filter { it.type == TrackType.VIDEO }
        val primaryClips = videoTracks.flatMap { it.clips }.sortedBy { it.startTimeMs }
        val currentClip = primaryClips.find { timeMs >= it.startTimeMs && timeMs < it.startTimeMs + it.durationMs } ?: return
        
        val clipOffsetMs = timeMs - currentClip.startTimeMs
        val sourceTimeMs = currentClip.sourceStartTimeMs + clipOffsetMs
        
        val resolvedPath = com.example.videoeditorapp.utils.AssetUtils.getCachedAssetPath(this, currentClip.filePath) ?: currentClip.filePath
        if (resolvedPath.startsWith("http")) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(resolvedPath)
                val timeUs = sourceTimeMs * 1000L
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        binding.ivExportPreview?.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
        }
    }

    override fun onDestroy() {
    super.onDestroy()
    try {
        unregisterReceiver(progressReceiver)
    } catch (e: Exception) {}
    binding.seekVideoProgress.removeCallbacks(progressRunnable)
    exoPlayer?.release()
    }
    private fun setupEdgeToEdge() {
        setupEditorEdgeToEdge(binding.appBarLayout, null)
    }
}
