package com.example.videoeditorapp.ui.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.Guideline
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.videoeditorapp.R
import com.example.videoeditorapp.ui.preview.OverlayManipulationView
import com.example.videoeditorapp.ui.timeline.TimelineView
import com.example.videoeditorapp.utils.ViewUtils
import com.google.android.material.card.MaterialCardView

@UnstableApi
class EditorPreviewLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    val playerView: PlayerView
    val floatingPlaybackHub: MaterialCardView
    val btnPlayPause: ImageView
    val btnFrameBack: ImageView
    val btnFrameFwd: ImageView
    val btnStop: ImageView
    val btnPrevClip: ImageView
    val btnNextClip: ImageView
    val tvTimeCode: TextView
    val tvDuration: TextView
    
    val layoutEmptyPreview: LinearLayout
    val tvEmptyHint: TextView
    val interactiveOverlayLayer: FrameLayout
    val overlayManipulationView: OverlayManipulationView
    val progressBar: ProgressBar
    val timelineView: TimelineView
    val previewResizeHandle: View
    val clipActionsNav: HorizontalScrollView
    val bottomNavTabs: HorizontalScrollView
    val bottomNavWrapper: LinearLayout
    
    // Action Buttons
    val btnSelect: MaterialCardView
    val btnSplit: MaterialCardView
    val btnTrim: MaterialCardView
    val btnDeleteClip: MaterialCardView
    val btnSpeed: MaterialCardView
    val btnSpeedRamp: MaterialCardView
    val btnVolume: MaterialCardView
    val btnKeyframe: MaterialCardView
    val btnMasking: MaterialCardView
    val btnLut: MaterialCardView
    val btnCropClip: MaterialCardView
    val btnCopyClip: MaterialCardView
    val btnPasteClip: MaterialCardView
    val btnMoveToFront: MaterialCardView
    val btnMoveToBack: MaterialCardView

    private val previewContainer: ConstraintLayout
    val splitGuideline: Guideline

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.bg_deep_black))

        // 1. Preview Container
        previewContainer = ConstraintLayout(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(0, 0)
            setBackgroundColor(Color.BLACK)
        }
        addView(previewContainer)

        playerView = PlayerView(context).apply {
            id = View.generateViewId()
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        addView(playerView)

        // Time Info
        val timeContainer = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = ViewUtils.dpToPx(context, 12)
            val padV = ViewUtils.dpToPx(context, 8)
            setPadding(padH, padV, padH, padV)
        }
        tvTimeCode = TextView(context).apply {
            id = View.generateViewId()
            text = "00:00:00"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
        }
        tvDuration = TextView(context).apply {
            id = View.generateViewId()
            text = " / 00:00:00"
            setTextColor(ContextCompat.getColor(context, R.color.text_medium_emphasis))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = ViewUtils.dpToPx(context, 4)
            }
        }
        timeContainer.addView(tvTimeCode)
        timeContainer.addView(tvDuration)
        addView(timeContainer)

        floatingPlaybackHub = MaterialCardView(context).apply {
            id = View.generateViewId()
            cardElevation = ViewUtils.dpToPx(context, 4).toFloat()
            radius = ViewUtils.dpToPx(context, 24).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC121212"))
            strokeWidth = ViewUtils.dpToPx(context, 1)
            strokeColor = Color.parseColor("#33FFFFFF")
        }
        val hubContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val padH = ViewUtils.dpToPx(context, 12)
            val padV = ViewUtils.dpToPx(context, 4)
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER
        }
        btnPrevClip = createHubIcon(R.drawable.ic_chevron_left, "#FFFFFF", 28)
        btnFrameBack = createHubIcon(R.drawable.ic_chevron_left, "#FFFFFF")
        btnPlayPause = createHubIcon(R.drawable.ic_play, "#00D2D3", 44)
        btnFrameFwd = createHubIcon(R.drawable.ic_chevron_right, "#FFFFFF")
        btnNextClip = createHubIcon(R.drawable.ic_chevron_right, "#FFFFFF", 28)
        btnStop = createHubIcon(R.drawable.ic_stop, "#FF5252")
        
        hubContent.addView(btnPrevClip)
        hubContent.addView(btnFrameBack)
        hubContent.addView(btnPlayPause)
        hubContent.addView(btnFrameFwd)
        hubContent.addView(btnNextClip)
        hubContent.addView(View(context).apply { 
            layoutParams = LinearLayout.LayoutParams(ViewUtils.dpToPx(context, 1), ViewUtils.dpToPx(context, 16)).apply {
                marginEnd = ViewUtils.dpToPx(context, 12)
                marginStart = ViewUtils.dpToPx(context, 12)
            }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        })
        hubContent.addView(btnStop)
        floatingPlaybackHub.addView(hubContent)
        addView(floatingPlaybackHub)

        layoutEmptyPreview = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.VISIBLE
        }
        val camIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_video_camera)
            setColorFilter(ContextCompat.getColor(context, R.color.brand_accent))
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(ViewUtils.dpToPx(context, 64), ViewUtils.dpToPx(context, 64))
        }
        tvEmptyHint = TextView(context).apply {
            id = View.generateViewId() // Required for constraint connections
            text = "Add media to start"
            textSize = 12f
            alpha = 0.6f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))

            // Set LayoutParams to wrap content
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Connect start to parent start and end to parent end
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }

        layoutEmptyPreview.addView(camIcon)
        layoutEmptyPreview.addView(tvEmptyHint)
        addView(layoutEmptyPreview)

        interactiveOverlayLayer = FrameLayout(context).apply { id = View.generateViewId() }
        overlayManipulationView = OverlayManipulationView(context).apply { 
            id = View.generateViewId()
            visibility = View.GONE
        }
        interactiveOverlayLayer.addView(overlayManipulationView)
        addView(interactiveOverlayLayer)

        progressBar = ProgressBar(context).apply { 
            id = View.generateViewId()
            visibility = View.GONE
        }
        addView(progressBar)

        // 2. Split Guideline
        splitGuideline = Guideline(context).apply { id = View.generateViewId() }
        addView(splitGuideline)

        // Change from View(context) to ImageView(context)
        previewResizeHandle = ImageView(context).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.bg_resize_handle_ribs)

            // 1. Changed from FIT_CENTER to CENTER to prevent upscaling
            scaleType = ImageView.ScaleType.CENTER

            elevation = ViewUtils.dpToPx(context, 10).toFloat()
        }
        addView(previewResizeHandle)

        // 4. Timeline
        timelineView = TimelineView(context).apply { id = View.generateViewId() }
        addView(timelineView)

        // 5. Bottom Navigation
        bottomNavWrapper = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            elevation = ViewUtils.dpToPx(context, 16).toFloat()
            background = ContextCompat.getDrawable(
                context,
                R.drawable.bg_bottom_bar_rounded
            )
            backgroundTintList =
                ContextCompat.getColorStateList(
                    context,
                    android.R.color.black
                )?.withAlpha(235)
        }

        clipActionsNav = HorizontalScrollView(context).apply {
            id = View.generateViewId()
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
        }

        val actionsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL

            val padH = ViewUtils.dpToPx(context, 8)
            val padV = ViewUtils.dpToPx(context, 2)

            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER_VERTICAL
        }
        btnSelect      = createActionBtn(R.drawable.ic_cursor, "#00D2D3") // Cyan
        btnSplit       = createActionBtn(R.drawable.ic_content_cut, "#FFB300") // Amber
        btnTrim        = createActionBtn(R.drawable.ic_adjust, "#8BC34A") // Green
        btnDeleteClip  = createActionBtn(R.drawable.ic_delete, "#FF5252") // Red
        btnSpeed       = createActionBtn(R.drawable.ic_speed, "#42A5F5") // Blue
        btnSpeedRamp   = createActionBtn(R.drawable.ic_fast_forward, "#7E57C2") // Purple
        btnVolume      = createActionBtn(R.drawable.ic_volume_up, "#26C6DA") // Teal
        btnKeyframe    = createActionBtn(R.drawable.ic_keyframe, "#FFD700") // Gold
        btnMasking     = createActionBtn(R.drawable.ic_mask, "#EC407A") // Pink
        btnLut         = createActionBtn(R.drawable.ic_lut, "#FF7043") // Orange
        btnCropClip    = createActionBtn(R.drawable.ic_crop, "#66BB6A") // Green
        btnCopyClip    = createActionBtn(R.drawable.ic_content_copy, "#BDBDBD") // Gray
        btnPasteClip   = createActionBtn(R.drawable.ic_paste, "#81C784") // Light Green
        btnMoveToFront = createActionBtn(R.drawable.ic_layers, "#AB47BC") // Violet
        btnMoveToBack = createActionBtn(R.drawable.ic_layers, "#CB25BC")

        actionsLayout.addView(btnSelect)
        actionsLayout.addView(btnSplit)
        actionsLayout.addView(btnTrim)
        actionsLayout.addView(btnDeleteClip)
        actionsLayout.addView(btnSpeed)
        actionsLayout.addView(btnSpeedRamp)
        actionsLayout.addView(btnVolume)
        actionsLayout.addView(btnKeyframe)
        actionsLayout.addView(btnMasking)
        actionsLayout.addView(btnLut)
        actionsLayout.addView(btnCropClip)
        actionsLayout.addView(btnCopyClip)
        actionsLayout.addView(btnPasteClip)
        actionsLayout.addView(btnMoveToFront)
        actionsLayout.addView(btnMoveToBack)
        
        clipActionsNav.addView(actionsLayout)
        bottomNavWrapper.addView(clipActionsNav)
        
        bottomNavTabs = HorizontalScrollView(context).apply { id = View.generateViewId(); visibility = View.GONE }
        bottomNavWrapper.addView(bottomNavTabs)
        
        addView(bottomNavWrapper)

        setupInternalResize()
        applyConstraints()
    }

    private fun setupInternalResize() {
        previewResizeHandle.setOnTouchListener(object : OnTouchListener {
            private var lastX = 0f
            private var lastY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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

                            val parentSize = if (isLandscape) width else height
                            if (parentSize > 0) {
                                val lp = splitGuideline.layoutParams as LayoutParams
                                val delta = if (isLandscape) dx else dy
                                val change = delta / parentSize.toFloat()
                                lp.guidePercent = (lp.guidePercent + change).coerceIn(0.2f, 0.8f)
                                splitGuideline.layoutParams = lp
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                    }
                }
                return true
            }
        })
    }

    private fun createHubIcon(res: Int, color: String, size: Int = 32): ImageView {
        return ImageView(context).apply {
            setImageResource(res)
            setColorFilter(Color.parseColor(color))
            val pad = ViewUtils.dpToPx(context, size / 5)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewUtils.dpToPx(context, size), ViewUtils.dpToPx(context, size))
        }
    }

    private fun createActionBtn(res: Int, color: String): MaterialCardView {

        val card = MaterialCardView(context).apply {

            layoutParams = LinearLayout.LayoutParams(
                ViewUtils.dpToPx(context, 56),
                ViewUtils.dpToPx(context, 48)
            ).apply {
                setMargins(
                    ViewUtils.dpToPx(context, 3),
                    ViewUtils.dpToPx(context, 4),
                    ViewUtils.dpToPx(context, 3),
                    ViewUtils.dpToPx(context, 4)
                )
            }

            radius = ViewUtils.dpToPx(context, 12).toFloat()
            cardElevation = 0f

            setCardBackgroundColor(Color.TRANSPARENT)

            strokeWidth = ViewUtils.dpToPx(context, 1)
            strokeColor = Color.parseColor("#22FFFFFF")

            isClickable = true
            isFocusable = true

            tag = color
        }

        val img = ImageView(context).apply {

            setImageResource(res)

            setColorFilter(Color.parseColor(color))

            layoutParams = FrameLayout.LayoutParams(

                ViewUtils.dpToPx(context, 22),

                ViewUtils.dpToPx(context, 22)

            ).apply {

                gravity = Gravity.CENTER

                bottomMargin = ViewUtils.dpToPx(context, 2)

            }

        }

        card.addView(img)

        return card
    }
    fun applyConstraints() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val set = ConstraintSet()
        set.clone(this)

        // Clear changing views to remove old orientation paths and avoid layout ghosting
        set.clear(previewContainer.id)
        set.clear(previewResizeHandle.id)
        set.clear(timelineView.id)
        set.clear(bottomNavWrapper.id)

        // Time Container
        val timeContainerId = tvTimeCode.parent.let { (it as View).id }
        set.connect(timeContainerId, ConstraintSet.TOP, previewContainer.id, ConstraintSet.TOP)
        set.connect(timeContainerId, ConstraintSet.START, previewContainer.id, ConstraintSet.START)
        set.constrainWidth(timeContainerId, ConstraintSet.WRAP_CONTENT)
        set.constrainHeight(timeContainerId, ConstraintSet.WRAP_CONTENT)

        // Preview Container Internal
        set.connect(playerView.id, ConstraintSet.TOP, previewContainer.id, ConstraintSet.TOP)
        set.connect(playerView.id, ConstraintSet.BOTTOM, previewContainer.id, ConstraintSet.BOTTOM)
        set.connect(playerView.id, ConstraintSet.START, previewContainer.id, ConstraintSet.START)
        set.connect(playerView.id, ConstraintSet.END, previewContainer.id, ConstraintSet.END)
        set.constrainWidth(playerView.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(playerView.id, ConstraintSet.MATCH_CONSTRAINT)

        // Floating Playback Hub
        set.connect(floatingPlaybackHub.id, ConstraintSet.BOTTOM, previewContainer.id, ConstraintSet.BOTTOM, ViewUtils.dpToPx(context, 8))
        set.connect(floatingPlaybackHub.id, ConstraintSet.START, previewContainer.id, ConstraintSet.START)
        set.connect(floatingPlaybackHub.id, ConstraintSet.END, previewContainer.id, ConstraintSet.END)
        set.constrainWidth(floatingPlaybackHub.id, ConstraintSet.WRAP_CONTENT)
        set.constrainHeight(floatingPlaybackHub.id, ConstraintSet.WRAP_CONTENT)

        set.connect(layoutEmptyPreview.id, ConstraintSet.TOP, previewContainer.id, ConstraintSet.TOP)
        set.connect(layoutEmptyPreview.id, ConstraintSet.BOTTOM, previewContainer.id, ConstraintSet.BOTTOM)
        set.connect(layoutEmptyPreview.id, ConstraintSet.START, previewContainer.id, ConstraintSet.START)
        set.connect(layoutEmptyPreview.id, ConstraintSet.END, previewContainer.id, ConstraintSet.END)
        set.constrainWidth(layoutEmptyPreview.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(layoutEmptyPreview.id, ConstraintSet.MATCH_CONSTRAINT)

        set.connect(interactiveOverlayLayer.id, ConstraintSet.TOP, playerView.id, ConstraintSet.TOP)
        set.connect(interactiveOverlayLayer.id, ConstraintSet.BOTTOM, playerView.id, ConstraintSet.BOTTOM)
        set.connect(interactiveOverlayLayer.id, ConstraintSet.START, playerView.id, ConstraintSet.START)
        set.connect(interactiveOverlayLayer.id, ConstraintSet.END, playerView.id, ConstraintSet.END)
        set.constrainWidth(interactiveOverlayLayer.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(interactiveOverlayLayer.id, ConstraintSet.MATCH_CONSTRAINT)

        set.connect(progressBar.id, ConstraintSet.TOP, previewContainer.id, ConstraintSet.TOP)
        set.connect(progressBar.id, ConstraintSet.BOTTOM, previewContainer.id, ConstraintSet.BOTTOM)
        set.connect(progressBar.id, ConstraintSet.START, previewContainer.id, ConstraintSet.START)
        set.connect(progressBar.id, ConstraintSet.END, previewContainer.id, ConstraintSet.END)
        set.constrainWidth(progressBar.id, ConstraintSet.WRAP_CONTENT)
        set.constrainHeight(progressBar.id, ConstraintSet.WRAP_CONTENT)

        // Base Dimensions for the Handle (Change values to fit your asset sizing)
        val thicknessDp = ViewUtils.dpToPx(context, 12)
        val lengthDp = ViewUtils.dpToPx(context, 84)

        if (isLandscape) {
            set.create(splitGuideline.id, ConstraintSet.VERTICAL_GUIDELINE)
            set.setGuidelinePercent(splitGuideline.id, 0.40f)

            set.connect(previewContainer.id, ConstraintSet.TOP, id, ConstraintSet.TOP)
            set.connect(previewContainer.id, ConstraintSet.START, id, ConstraintSet.START)
            set.connect(previewContainer.id, ConstraintSet.END, splitGuideline.id, ConstraintSet.START)
            set.connect(previewContainer.id, ConstraintSet.BOTTOM, id, ConstraintSet.BOTTOM)
            set.constrainWidth(previewContainer.id, ConstraintSet.MATCH_CONSTRAINT)
            set.constrainHeight(previewContainer.id, ConstraintSet.MATCH_CONSTRAINT)

            // LANDSCAPE HANDLE: Centered horizontally on guideline, static length vertically
            set.connect(previewResizeHandle.id, ConstraintSet.START, splitGuideline.id, ConstraintSet.START)
            set.connect(previewResizeHandle.id, ConstraintSet.END, splitGuideline.id, ConstraintSet.END)
            set.connect(previewResizeHandle.id, ConstraintSet.TOP, id, ConstraintSet.TOP)
            set.connect(previewResizeHandle.id, ConstraintSet.BOTTOM, id, ConstraintSet.BOTTOM)
            set.constrainWidth(previewResizeHandle.id, thicknessDp)
            set.constrainHeight(previewResizeHandle.id, lengthDp)

            set.connect(timelineView.id, ConstraintSet.TOP, id, ConstraintSet.TOP)
            set.connect(timelineView.id, ConstraintSet.START, splitGuideline.id, ConstraintSet.END)
            set.connect(timelineView.id, ConstraintSet.END, id, ConstraintSet.END)
            set.connect(timelineView.id, ConstraintSet.BOTTOM, id, ConstraintSet.BOTTOM)
            set.constrainWidth(timelineView.id, ConstraintSet.MATCH_CONSTRAINT)
            set.constrainHeight(timelineView.id, ConstraintSet.MATCH_CONSTRAINT)

            set.connect(bottomNavWrapper.id, ConstraintSet.START, timelineView.id, ConstraintSet.START)
            set.connect(bottomNavWrapper.id, ConstraintSet.END, timelineView.id, ConstraintSet.END)
            set.connect(bottomNavWrapper.id, ConstraintSet.BOTTOM, id, ConstraintSet.BOTTOM)
            set.constrainWidth(bottomNavWrapper.id, ConstraintSet.MATCH_CONSTRAINT)
            set.constrainHeight(bottomNavWrapper.id, ConstraintSet.WRAP_CONTENT)
        } else {
            set.create(splitGuideline.id, ConstraintSet.HORIZONTAL_GUIDELINE)
            set.setGuidelinePercent(splitGuideline.id, 0.45f)

            set.connect(previewContainer.id, ConstraintSet.TOP, id, ConstraintSet.TOP)
            set.connect(previewContainer.id, ConstraintSet.START, id, ConstraintSet.START)
            set.connect(previewContainer.id, ConstraintSet.END, id, ConstraintSet.END)
            set.connect(previewContainer.id, ConstraintSet.BOTTOM, splitGuideline.id, ConstraintSet.TOP)
            set.constrainWidth(previewContainer.id, ConstraintSet.MATCH_CONSTRAINT)
            set.constrainHeight(previewContainer.id, ConstraintSet.MATCH_CONSTRAINT)

            // PORTRAIT HANDLE: Centered vertically on guideline, static length horizontally
            set.connect(previewResizeHandle.id, ConstraintSet.TOP, splitGuideline.id, ConstraintSet.TOP)
            set.connect(previewResizeHandle.id, ConstraintSet.BOTTOM, splitGuideline.id, ConstraintSet.BOTTOM)
            set.connect(previewResizeHandle.id, ConstraintSet.START, id, ConstraintSet.START)
            set.connect(previewResizeHandle.id, ConstraintSet.END, id, ConstraintSet.END)
            set.constrainWidth(previewResizeHandle.id, lengthDp)
            set.constrainHeight(previewResizeHandle.id, thicknessDp)

            set.connect(timelineView.id, ConstraintSet.TOP, splitGuideline.id, ConstraintSet.BOTTOM)
            set.connect(timelineView.id, ConstraintSet.BOTTOM, id, ConstraintSet.BOTTOM)
            set.connect(timelineView.id, ConstraintSet.START, id, ConstraintSet.START)
            set.connect(timelineView.id, ConstraintSet.END, id, ConstraintSet.END)
            set.constrainWidth(timelineView.id, ConstraintSet.MATCH_CONSTRAINT)
            set.constrainHeight(timelineView.id, ConstraintSet.MATCH_CONSTRAINT)

            set.connect(bottomNavWrapper.id, ConstraintSet.START, id, ConstraintSet.START)
            set.connect(bottomNavWrapper.id, ConstraintSet.END, id, ConstraintSet.END)
            set.connect(bottomNavWrapper.id, ConstraintSet.BOTTOM, id, ConstraintSet.BOTTOM)
            set.constrainWidth(bottomNavWrapper.id, ConstraintSet.MATCH_CONSTRAINT)
            set.constrainHeight(bottomNavWrapper.id, ConstraintSet.WRAP_CONTENT)
        }

        set.applyTo(this)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Cast to ImageView to safely change the image resource
        (previewResizeHandle as? ImageView)?.apply {
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                setImageResource(R.drawable.bg_resize_handle_ribs_landscape)
            } else {
                setImageResource(R.drawable.bg_resize_handle_ribs)
            }
        }

        applyConstraints()
    }
}
