package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 3200 // 100 ms of 16 kHz mono 16-bit PCM

        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val RING_DP = 56
        private const val CANCEL_DP = 36
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val LONG_PRESS_MS = 280L
        private const val RADIAL_RADIUS_DP = 86f
        private const val RADIAL_HIT_DP = 42f

        private const val COLOR_IDLE = 0xDE1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xE6EF4444.toInt()
        private const val COLOR_BUSY = 0xDE6B6B6B.toInt()
        private const val COLOR_ERROR = 0xE6B42318.toInt()
        private const val COLOR_PRIMARY = 0xFF1A73E8.toInt()
        private const val COLOR_PRIMARY_LIGHT = 0xFF8AB4F8.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
        private const val COLOR_BAR_BG = 0xF218191C.toInt()
        private const val COLOR_BAR_STROKE = 0x26FFFFFF
        private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_PARTIAL = 0x80E8EAED.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF9AA0A6.toInt()
        private const val COLOR_DOT_OFFLINE = 0xFFF59E0B.toInt()
        private const val COLOR_DOT_ERROR = 0xFFF28B82.toInt()
    }

    private enum class State { IDLE, RECORDING, POLISHING, ERROR_RETRY }

    private var state = State.IDLE
    private val sessionGen = AtomicLong(0L)
    private val recordingActive = AtomicBoolean(false)

    // Overlay views & layout params
    private var dockView: LinearLayout? = null
    private var dockParams: WindowManager.LayoutParams? = null
    private var micButton: ImageView? = null
    private var micBadge: TextView? = null
    private var spinner: ProgressBar? = null
    private var holdRing: View? = null
    private var cancelButton: View? = null

    private var liveBarView: LinearLayout? = null
    private var liveBarParams: WindowManager.LayoutParams? = null
    private var liveDot: View? = null
    private var liveStatusText: TextView? = null
    private var livePresetChip: TextView? = null
    private var liveModeText: TextView? = null
    private var liveTimerText: TextView? = null
    private var liveTranscriptScroll: ScrollView? = null
    private var liveTranscriptText: TextView? = null
    private var liveRecordActionsRow: LinearLayout? = null
    private var liveActionsRow: LinearLayout? = null
    private var usePartialBtn: TextView? = null

    private var radialView: RadialMenuView? = null
    private var radialOpen = false

    private var feedbackView: LinearLayout? = null
    private var feedbackParams: WindowManager.LayoutParams? = null
    private var feedbackText: TextView? = null
    private var feedbackUndoBtn: TextView? = null
    private var feedbackFlagBtn: TextView? = null

    // Active dictation session state
    private var activePreset: ShortcutPreset = ShortcutStore.defaults().first()
    private var activeClipId: String = ""
    private var activeContext: ScreenContext? = null
    private var audioRecord: AudioRecord? = null
    private var wavRecorder: PcmWavFileRecorder? = null
    private var liveSession: LiveStreamSession? = null
    private var activeRestCall: Call? = null
    private var activeCleanupCall: Call? = null
    private var streamFailedDuringRecording = false
    private var streamQuotaExceeded = false
    private var latestCommittedText = ""
    private var latestInterimText = ""
    private var lastLiveDraftText = ""
    private var failedClip: SavedClip? = null
    private var lastRawForUndo: String? = null
    private var lastSavedClipId: String? = null
    private var recordingStartMs = 0L

    // Rolling recent dictation history (in-memory per app package)
    private var lastHistoryPkg = ""
    private val recentDictations = ArrayDeque<String>()

    // Local transcription engine (lazy)
    private var localTranscriber: LocalTranscriber? = null

    private val handler = Handler(Looper.getMainLooper())
    private val timerTicker = object : Runnable {
        override fun run() {
            if (state == State.RECORDING) {
                val sec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt().coerceAtLeast(0)
                liveTimerText?.text = formatDuration(sec)
                handler.postDelayed(this, 500L)
            }
        }
    }
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
            setFeedbackTouchable(false)
        }?.start()
    }

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlays()
        thread { initLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        cancelActiveSession(silent = true)
        instance = null
        removeOverlays()
        super.onDestroy()
    }

    private fun initLocalModel() {
        val modelName = prefs().getString(AppPrefs.KEY_LOCAL_MODEL, "") ?: ""
        val chosen = if (modelName.isBlank()) {
            LocalTranscriber.availableModels(this).firstOrNull()
        } else {
            modelName
        }
        localTranscriber = if (chosen != null) LocalTranscriber.create(this, chosen) else null
        Log.i(TAG, if (localTranscriber != null) "Local model ready: $chosen" else "No local model loaded")
    }

    fun reloadModel() { thread { initLocalModel() } }

    // --- Overlays ---

    private fun showOverlays() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createDockOverlay(wm)
        createLiveBarOverlay(wm)
        createFeedbackOverlay(wm)
    }

    private fun createDockOverlay(wm: WindowManager) {
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val holdRingView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke((2.5f * dp).toInt(), COLOR_PRIMARY_LIGHT)
            }
            visibility = View.GONE
        }

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val badge = TextView(this).apply {
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding((4 * dp).toInt(), (1 * dp).toInt(), (4 * dp).toInt(), (1 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * dp
                setColor(COLOR_PRIMARY)
                setStroke((1.5f * dp).toInt(), 0xFF121212.toInt())
            }
            visibility = View.GONE
        }

        val micWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ringSize, ringSize)
            addView(holdRingView, FrameLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt(), Gravity.CENTER))
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ))
        }

        val cancelSize = (CANCEL_DP * dp).toInt()
        val cancelView = CancelCrossView(this).apply {
            layoutParams = LinearLayout.LayoutParams(cancelSize, cancelSize).apply {
                topMargin = (8 * dp).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
            setOnClickListener { onCancelClicked() }
        }

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(micWrap)
            addView(cancelView)
        }

        val edgeLeft = prefs().getBoolean(AppPrefs.KEY_OVERLAY_EDGE_LEFT, false)
        val savedY = prefs().getInt(AppPrefs.KEY_OVERLAY_Y, screenH / 2 - ringSize / 2)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (edgeLeft) margin else screenW - ringSize - margin
            y = savedY.coerceIn(statusBarHeight() + margin, (screenH - ringSize * 2).coerceAtLeast(margin))
        }

        attachMicGestures(micWrap, dock, params, wm)

        wm.addView(dock, params)
        dockView = dock
        dockParams = params
        micButton = img
        micBadge = badge
        spinner = ring
        holdRing = holdRingView
        cancelButton = cancelView
    }

    private fun attachMicGestures(
        micWrap: FrameLayout,
        dock: LinearLayout,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        val ringSize = (RING_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var gestureMode = 0 // 0=none, 1=pending, 2=dragging, 3=radial

        val holdRunnable = Runnable {
            if (gestureMode == 1 && state == State.IDLE) {
                val longPresets = ShortcutStore.activeLongPressPresets(prefs())
                if (longPresets.isNotEmpty()) {
                    gestureMode = 3
                    micWrap.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    openRadialMenu(params, longPresets)
                }
            }
        }

        micWrap.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    gestureMode = 1
                    if (state == State.IDLE) {
                        holdRing?.visibility = View.VISIBLE
                        handler.postDelayed(holdRunnable, LONG_PRESS_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (gestureMode == 1 && abs(dx) + abs(dy) >= TAP_THRESHOLD_DP * dp) {
                        handler.removeCallbacks(holdRunnable)
                        holdRing?.visibility = View.GONE
                        gestureMode = 2
                    }
                    if (gestureMode == 2) {
                        params.x = (startX + dx.toInt()).coerceIn(margin, screenW - ringSize - margin)
                        params.y = (startY + dy.toInt()).coerceIn(statusBarHeight(), screenH - ringSize - margin)
                        wm.updateViewLayout(dock, params)
                    } else if (gestureMode == 3) {
                        val changed = radialView?.updatePointer(ev.rawX, ev.rawY) == true
                        if (changed) {
                            micWrap.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRunnable)
                    holdRing?.visibility = View.GONE
                    val finished = gestureMode
                    gestureMode = 0

                    when (finished) {
                        1 -> if (ev.actionMasked == MotionEvent.ACTION_UP) onMicTap()
                        2 -> {
                            val snapLeft = (params.x + ringSize / 2) < screenW / 2
                            params.x = if (snapLeft) margin else screenW - ringSize - margin
                            wm.updateViewLayout(dock, params)
                            prefs().edit()
                                .putBoolean(AppPrefs.KEY_OVERLAY_EDGE_LEFT, snapLeft)
                                .putInt(AppPrefs.KEY_OVERLAY_Y, params.y)
                                .apply()
                        }
                        3 -> {
                            val picked = if (ev.actionMasked == MotionEvent.ACTION_UP) {
                                radialView?.hoveredPreset
                            } else null
                            closeRadialMenu()
                            if (picked != null) {
                                startRecording(picked)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openRadialMenu(bubbleParams: WindowManager.LayoutParams, presets: List<ShortcutPreset>) {
        if (radialOpen) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ringSize = (RING_DP * dp).toInt()
        val centerX = bubbleParams.x + ringSize / 2f
        val centerY = bubbleParams.y + ringSize / 2f
        val fanLeft = centerX > screenW / 2f

        val view = RadialMenuView(this, centerX, centerY, fanLeft, presets)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        radialView = view
        radialOpen = true
    }

    private fun closeRadialMenu() {
        if (!radialOpen) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        radialView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        radialView = null
        radialOpen = false
    }

    private fun createLiveBarOverlay(wm: WindowManager) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * dp
                setColor(COLOR_BAR_BG)
                setStroke((1 * dp).toInt(), COLOR_BAR_STROKE)
            }
            visibility = View.GONE
        }

        // Header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((7 * dp).toInt(), (7 * dp).toInt()).apply {
                marginEnd = (7 * dp).toInt()
            }
            background = circle(COLOR_RECORDING)
        }
        val statusTv = TextView(this).apply {
            textSize = 11.5f
            setTextColor(COLOR_TEXT_MUTED)
            text = "Streaming"
        }
        val chipTv = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY_LIGHT)
            setPadding((6 * dp).toInt(), (1 * dp).toInt(), (6 * dp).toInt(), (1 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 5 * dp
                setColor(0x288AB4F8)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (7 * dp).toInt() }
        }
        val modeTv = TextView(this).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (7 * dp).toInt()
                marginEnd = (8 * dp).toInt()
            }
        }
        val timerTv = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_RING)
            text = "0:00"
        }

        header.addView(dot)
        header.addView(statusTv)
        header.addView(chipTv)
        header.addView(modeTv)
        header.addView(timerTv)
        bar.addView(header)

        // Scrollable transcript text (capped to ~92dp height)
        val transcriptTv = TextView(this).apply {
            textSize = 14f
            setLineSpacing(2 * dp, 1f)
            setTextColor(COLOR_TEXT_PRIMARY)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() }
            isVerticalScrollBarEnabled = false
            addView(transcriptTv)
        }
        bar.addView(scroll)

        // Recording action row (visible while RECORDING so finishing is unmistakable)
        val recordActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
            visibility = View.GONE
        }
        val recCancelBtn = actionPill("Cancel", 0x00000000, COLOR_TEXT_MUTED) { onCancelClicked() }
        val recDoneBtn = actionPill("Done", COLOR_PRIMARY, COLOR_TEXT_PRIMARY, bold = true) {
            if (state == State.RECORDING) stopRecordingAndFinalize()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = (8 * dp).toInt()
        }
        recordActionsRow.addView(recCancelBtn)
        recordActionsRow.addView(recDoneBtn)
        bar.addView(recordActionsRow)

        // Recovery action row (visible only in ERROR_RETRY)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
            visibility = View.GONE
        }

        val discardBtn = actionPill("Discard", 0x00000000, COLOR_TEXT_MUTED) { onDiscardFailedClip() }
        val partialBtn = actionPill("Use partial", 0x1EFFFFFF, COLOR_RING) { onUsePartialClip() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = (8 * dp).toInt()
        }
        val retryBtn = actionPill("Retry", COLOR_PRIMARY, COLOR_TEXT_PRIMARY, bold = true) { onRetryFailedClip() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = (8 * dp).toInt()
        }
        actionsRow.addView(discardBtn)
        actionsRow.addView(partialBtn)
        actionsRow.addView(retryBtn)
        bar.addView(actionsRow)

        val hMargin = (10 * dp).toInt()
        val params = WindowManager.LayoutParams(
            screenW - hMargin * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = hMargin
            y = statusBarHeight() + (6 * dp).toInt()
        }

        wm.addView(bar, params)
        liveBarView = bar
        liveBarParams = params
        liveDot = dot
        liveStatusText = statusTv
        livePresetChip = chipTv
        liveModeText = modeTv
        liveTimerText = timerTv
        liveTranscriptScroll = scroll
        liveTranscriptText = transcriptTv
        liveRecordActionsRow = recordActionsRow
        liveActionsRow = actionsRow
        usePartialBtn = partialBtn
    }

    private fun actionPill(
        label: String,
        bgColor: Int,
        textColor: Int,
        bold: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        if (bgColor != 0) {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 99 * dp
                setColor(bgColor)
            }
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

    private fun createFeedbackOverlay(wm: WindowManager) {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((13 * dp).toInt(), (8 * dp).toInt(), (13 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * dp
                setColor(COLOR_BAR_BG)
                setStroke((1 * dp).toInt(), COLOR_BAR_STROKE)
            }
            alpha = 0f
            visibility = View.GONE
        }

        val tv = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT_PRIMARY)
        }
        val undo = TextView(this).apply {
            text = "Use raw"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY_LIGHT)
            setPadding((12 * dp).toInt(), 0, 0, 0)
            visibility = View.GONE
            setOnClickListener { onUndoPolishClicked() }
        }
        val flag = TextView(this).apply {
            text = "Flag"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_DOT_OFFLINE)
            setPadding((12 * dp).toInt(), 0, 0, 0)
            visibility = View.GONE
            setOnClickListener { onFlagLastClipClicked() }
        }
        pill.addView(tv)
        pill.addView(undo)
        pill.addView(flag)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + (12 * dp).toInt()
        }

        wm.addView(pill, params)
        feedbackView = pill
        feedbackParams = params
        feedbackText = tv
        feedbackUndoBtn = undo
        feedbackFlagBtn = flag
    }

    private fun removeOverlays() {
        closeRadialMenu()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dockView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        liveBarView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        feedbackView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dockView = null
        liveBarView = null
        feedbackView = null
    }

    private fun setLiveBarTouchable(touchable: Boolean) {
        val bar = liveBarView ?: return
        val params = liveBarParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        params.flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(bar, params) } catch (_: Exception) {}
    }

    private fun setFeedbackTouchable(touchable: Boolean) {
        val view = feedbackView ?: return
        val params = feedbackParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        params.flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    private fun showFeedback(
        text: String,
        allowUndoRaw: Boolean = false,
        allowFlag: Boolean = false,
        durationMs: Long = 2200L
    ) {
        handler.post {
            val view = feedbackView ?: return@post
            feedbackText?.text = text
            feedbackUndoBtn?.visibility = if (allowUndoRaw) View.VISIBLE else View.GONE
            feedbackFlagBtn?.visibility = if (allowFlag) View.VISIBLE else View.GONE
            val interactive = allowUndoRaw || allowFlag
            setFeedbackTouchable(interactive)
            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, if (interactive) 4500L else durationMs)
        }
    }

    private fun updateLiveBar(
        visible: Boolean,
        dotColor: Int = COLOR_RECORDING,
        status: String = "Streaming",
        committed: String = latestCommittedText,
        interim: String = latestInterimText,
        showActions: Boolean = false,
        showUsePartial: Boolean = false
    ) {
        handler.post {
            val bar = liveBarView ?: return@post
            if (!visible) {
                bar.visibility = View.GONE
                setLiveBarTouchable(false)
                return@post
            }
            liveDot?.background = circle(dotColor)
            liveStatusText?.text = status
            livePresetChip?.text = activePreset.badge
            liveModeText?.text = buildString {
                append(if (activePreset.screenContext) "Context" else "No context")
                append(" · ")
                append(if (activePreset.postProcess) "Polish" else "Raw")
            }

            val ssb = SpannableStringBuilder()
            val c = committed.trim()
            val i = interim.trim()
            if (c.isEmpty() && i.isEmpty()) {
                val placeholder = if (state == State.RECORDING) "Speak now..." else ""
                ssb.append(placeholder)
                ssb.setSpan(ForegroundColorSpan(COLOR_TEXT_MUTED), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                if (c.isNotEmpty()) {
                    ssb.append(c)
                    ssb.setSpan(ForegroundColorSpan(COLOR_TEXT_PRIMARY), 0, c.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (i.isNotEmpty()) {
                    if (c.isNotEmpty()) ssb.append(" ")
                    val start = ssb.length
                    ssb.append(i)
                    ssb.setSpan(ForegroundColorSpan(COLOR_TEXT_PARTIAL), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            liveTranscriptText?.text = ssb
            liveTranscriptScroll?.let { sv ->
                val maxH = (92 * dp).toInt()
                sv.post {
                    if (sv.height > maxH) {
                        sv.layoutParams.height = maxH
                        sv.requestLayout()
                    }
                    sv.fullScroll(View.FOCUS_DOWN)
                }
            }

            val showRecordActions = state == State.RECORDING && !showActions
            liveRecordActionsRow?.visibility = if (showRecordActions) View.VISIBLE else View.GONE
            liveActionsRow?.visibility = if (showActions) View.VISIBLE else View.GONE
            usePartialBtn?.visibility = if (showActions && showUsePartial) View.VISIBLE else View.GONE
            setLiveBarTouchable(showActions || showRecordActions)
            bar.visibility = View.VISIBLE
        }
    }

    private fun updateDockVisuals() {
        handler.post {
            when (state) {
                State.IDLE -> {
                    stopPulse()
                    micButton?.background = circle(COLOR_IDLE)
                    spinner?.visibility = View.GONE
                    cancelButton?.visibility = View.GONE
                    micBadge?.visibility = View.GONE
                }
                State.RECORDING -> {
                    micButton?.background = circle(COLOR_RECORDING)
                    spinner?.visibility = View.GONE
                    cancelButton?.visibility = View.VISIBLE
                    if (!activePreset.isDefault) {
                        micBadge?.text = activePreset.badge
                        micBadge?.visibility = View.VISIBLE
                    } else {
                        micBadge?.visibility = View.GONE
                    }
                    startPulse()
                }
                State.POLISHING -> {
                    stopPulse()
                    micButton?.background = circle(COLOR_BUSY)
                    spinner?.visibility = View.VISIBLE
                    cancelButton?.visibility = View.VISIBLE
                    if (!activePreset.isDefault) {
                        micBadge?.text = activePreset.badge
                        micBadge?.visibility = View.VISIBLE
                    } else {
                        micBadge?.visibility = View.GONE
                    }
                }
                State.ERROR_RETRY -> {
                    stopPulse()
                    micButton?.background = circle(COLOR_ERROR)
                    spinner?.visibility = View.GONE
                    cancelButton?.visibility = View.GONE
                    micBadge?.visibility = View.GONE
                }
            }
        }
    }

    private fun startPulse() {
        micButton?.let { btn ->
            btn.animate().alpha(0.45f).setDuration(500).withEndAction {
                btn.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        micButton?.animate()?.cancel()
        micButton?.alpha = 1f
    }

    // --- State Machine ---

    private fun onMicTap() {
        when (state) {
            State.IDLE -> startRecording(ShortcutStore.defaultPreset(prefs()))
            State.RECORDING -> stopRecordingAndFinalize()
            State.POLISHING -> onCancelClicked()
            State.ERROR_RETRY -> onRetryFailedClip()
        }
    }

    private fun onCancelClicked() {
        if (state == State.IDLE) return
        cancelActiveSession(silent = false)
    }

    private fun cancelActiveSession(silent: Boolean) {
        sessionGen.incrementAndGet()
        recordingActive.set(false)
        handler.removeCallbacks(timerTicker)

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        liveSession?.cancel()
        liveSession = null

        activeRestCall?.cancel()
        activeRestCall = null
        activeCleanupCall?.cancel()
        activeCleanupCall = null

        wavRecorder?.abortAndDelete()
        wavRecorder = null

        failedClip = null
        latestCommittedText = ""
        latestInterimText = ""
        state = State.IDLE
        updateDockVisuals()
        updateLiveBar(visible = false)
        if (!silent) showFeedback("Dictation cancelled", allowUndoRaw = false, durationMs = 1500L)
    }

    private fun startRecording(preset: ShortcutPreset) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            toast("Grant audio permission in Phone Whisper")
            return
        }

        val useLocal = prefs().getBoolean(AppPrefs.KEY_USE_LOCAL, false)
        val useElevenLabs = AppPrefs.isElevenLabs(prefs())
        val sttKey = AppPrefs.activeSttApiKey(prefs())
        if (!useLocal && sttKey.isBlank()) {
            toast(if (useElevenLabs) "Set ElevenLabs API key in Phone Whisper" else "Set Gemini API key in Phone Whisper")
            return
        }

        val gen = sessionGen.incrementAndGet()
        activePreset = preset
        activeClipId = RecordingStore.newClipId()
        streamFailedDuringRecording = false
        streamQuotaExceeded = false
        latestCommittedText = ""
        latestInterimText = ""
        lastLiveDraftText = ""
        failedClip = null
        lastRawForUndo = null
        lastSavedClipId = null

        // Capture Accessibility context snapshot before recording starts (only if enabled on preset)
        activeContext = if (preset.screenContext) {
            val focused = findFocusedEditableNode()
            val pkg = focused?.packageName?.toString()
                ?: rootInActiveWindow?.packageName?.toString()
                ?: ""
            val hist = if (pkg.isNotEmpty() && pkg == lastHistoryPkg) recentDictations.toList() else emptyList()
            val ctx = ScreenContextCapture.capture(this, focused, hist)
            try { focused?.recycle() } catch (_: Exception) {}
            ctx
        } else {
            null
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, CHUNK_BYTES * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) {
            toast("Audio permission denied")
            return
        }

        val wavFile = RecordingStore.createWavFile(this, activeClipId)
        val diskRecorder = try {
            PcmWavFileRecorder(wavFile, SAMPLE_RATE)
        } catch (e: Exception) {
            recorder.release()
            toast("Cannot open storage: ${e.message}")
            return
        }

        audioRecord = recorder
        wavRecorder = diskRecorder
        recordingStartMs = System.currentTimeMillis()
        recordingActive.set(true)
        state = State.RECORDING

        updateDockVisuals()
        liveTimerText?.text = "0:00"
        handler.removeCallbacks(timerTicker)
        handler.post(timerTicker)

        // Start cloud live WebSocket stream when not in local mode
        if (!useLocal) {
            updateLiveBar(
                visible = true,
                dotColor = COLOR_RECORDING,
                status = "Streaming",
                committed = "",
                interim = ""
            )
            val streamListener = object : GeminiLiveClient.StreamListener {
                override fun onTranscriptUpdate(committed: String, interim: String) {
                    if (gen != sessionGen.get()) return
                    latestCommittedText = committed
                    latestInterimText = interim
                    val draft = listOf(committed.trim(), interim.trim())
                        .filter { it.isNotEmpty() }.joinToString(" ")
                    if (draft.isNotEmpty()) lastLiveDraftText = draft
                    if (state == State.RECORDING) {
                        updateLiveBar(
                            visible = true,
                            dotColor = if (streamFailedDuringRecording) COLOR_DOT_OFFLINE else COLOR_RECORDING,
                            status = if (streamFailedDuringRecording) "Offline · Saving locally" else "Streaming",
                            committed = committed,
                            interim = interim
                        )
                    }
                }

                override fun onFinalized(fullText: String) {
                    if (gen != sessionGen.get()) return
                    handler.post {
                        if (gen != sessionGen.get()) return@post
                        val finalRaw = fullText.trim()
                        if (finalRaw.isEmpty()) {
                            val wavPath = wavRecorder?.wavFile?.absolutePath
                            if (wavPath != null && File(wavPath).length() > 1000) {
                                transcribeSavedWavFallback(gen, wavPath, preset, "")
                            } else {
                                resetToIdle("No speech detected")
                            }
                        } else {
                            latestCommittedText = finalRaw
                            latestInterimText = ""
                            onStage1Complete(gen, finalRaw, preset, wavRecorder?.wavFile?.absolutePath)
                        }
                    }
                }

                override fun onError(message: String, partialCommitted: String) {
                    if (gen != sessionGen.get()) return
                    Log.w(TAG, "Live stream error: $message")
                    val quota = ElevenLabsLiveClient.isQuotaError(message)
                    handler.post {
                        if (gen != sessionGen.get()) return@post
                        if (partialCommitted.isNotBlank()) latestCommittedText = partialCommitted
                        streamFailedDuringRecording = true
                        if (quota) streamQuotaExceeded = true
                        if (state == State.RECORDING) {
                            updateLiveBar(
                                visible = true,
                                dotColor = COLOR_DOT_OFFLINE,
                                status = if (quota) "Out of credits · Saving locally" else "Offline · Saving locally",
                                committed = latestCommittedText,
                                interim = if (quota) "[will switch to local model on stop]" else "[recording locally — tap stop to retry]"
                            )
                        } else if (state == State.POLISHING) {
                            val wavPath = wavRecorder?.wavFile?.absolutePath
                            if (wavPath != null && File(wavPath).exists()) {
                                if (quota) {
                                    handleOutOfCreditsFallback(gen, wavPath, preset, latestCommittedText, message)
                                } else {
                                    enterOfflineRetryState(wavPath, preset, latestCommittedText, message)
                                }
                            } else {
                                resetToIdle("Error: $message")
                            }
                        }
                    }
                }
            }
            liveSession = if (useElevenLabs) {
                ElevenLabsLiveClient.startStream(
                    apiKey = sttKey,
                    model = AppPrefs.elevenLabsStreamModel(prefs()),
                    languageCode = preset.languageCode,
                    listener = streamListener
                )
            } else {
                GeminiLiveClient.startStream(
                    apiKey = sttKey,
                    model = AppPrefs.streamingModel(prefs()),
                    languageBcp47 = preset.toBcp47(),
                    listener = streamListener
                )
            }
        } else {
            updateLiveBar(
                visible = true,
                dotColor = COLOR_RECORDING,
                status = "Recording (local)",
                committed = "",
                interim = "Local transcription runs on stop..."
            )
        }

        recorder.startRecording()
        thread(name = "pw-audio-capture") {
            val buf = ByteArray(CHUNK_BYTES)
            while (recordingActive.get() && gen == sessionGen.get()) {
                val n = try { recorder.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n > 0) {
                    diskRecorder.write(buf, 0, n)
                    if (!useLocal && !streamFailedDuringRecording) {
                        liveSession?.sendPcm(buf, n)
                    }
                } else if (n < 0) {
                    break
                }
            }
        }
    }

    private fun stopRecordingAndFinalize() {
        val gen = sessionGen.get()
        recordingActive.set(false)
        handler.removeCallbacks(timerTicker)

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        val diskRec = wavRecorder
        val bytesWritten = try { diskRec?.finish() ?: 0L } catch (_: Exception) { 0L }
        val wavFile = diskRec?.wavFile

        if (bytesWritten <= 0L || wavFile == null || !wavFile.exists()) {
            diskRec?.abortAndDelete()
            wavRecorder = null
            liveSession?.cancel()
            liveSession = null
            resetToIdle("No audio captured")
            return
        }

        state = State.POLISHING
        updateDockVisuals()

        val useLocal = prefs().getBoolean(AppPrefs.KEY_USE_LOCAL, false)
        val local = localTranscriber
        if (useLocal && local != null) {
            updateLiveBar(
                visible = true,
                dotColor = COLOR_PRIMARY_LIGHT,
                status = "Transcribing locally",
                committed = "",
                interim = "Processing..."
            )
            transcribeLocalFromWav(gen, wavFile, local, activePreset)
            return
        }

        if (streamQuotaExceeded) {
            liveSession?.cancel()
            liveSession = null
            handleOutOfCreditsFallback(gen, wavFile.absolutePath, activePreset, latestCommittedText, "Out of credits")
        } else if (streamFailedDuringRecording || liveSession == null) {
            liveSession?.cancel()
            liveSession = null
            // Attempt immediate REST transcription of saved WAV; falls into ERROR_RETRY if still offline
            transcribeSavedWavFallback(gen, wavFile.absolutePath, activePreset, latestCommittedText)
        } else {
            updateLiveBar(
                visible = true,
                dotColor = COLOR_PRIMARY_LIGHT,
                status = "Finalizing",
                committed = latestCommittedText,
                interim = latestInterimText
            )
            liveSession?.finish()
        }
    }

    private fun transcribeLocalFromWav(
        gen: Long,
        wavFile: File,
        transcriber: LocalTranscriber,
        preset: ShortcutPreset
    ) {
        thread(name = "pw-local-decode") {
            try {
                val allBytes = wavFile.readBytes()
                if (allBytes.size <= 44) {
                    handler.post { if (gen == sessionGen.get()) resetToIdle("No audio captured") }
                    return@thread
                }
                val pcmLen = allBytes.size - 44
                val samples = FloatArray(pcmLen / 2)
                for (i in samples.indices) {
                    val idx = 44 + i * 2
                    val lo = allBytes[idx].toInt() and 0xFF
                    val hi = allBytes[idx + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                handler.post {
                    if (gen != sessionGen.get()) return@post
                    if (text.isBlank()) {
                        resetToIdle("No speech detected")
                    } else {
                        onStage1Complete(gen, text.trim(), preset, wavFile.absolutePath)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                handler.post {
                    if (gen != sessionGen.get()) return@post
                    resetToIdle("Local error: ${e.message}")
                }
            }
        }
    }

    private fun transcribeSavedWavFallback(
        gen: Long,
        wavPath: String,
        preset: ShortcutPreset,
        partialSoFar: String
    ) {
        val useElevenLabs = AppPrefs.isElevenLabs(prefs())
        val apiKey = AppPrefs.activeSttApiKey(prefs())
        if (apiKey.isBlank()) {
            val missing = if (useElevenLabs) "ElevenLabs API key missing" else "Gemini API key missing"
            enterOfflineRetryState(wavPath, preset, partialSoFar, missing)
            return
        }
        val wavFile = File(wavPath)
        if (!wavFile.exists()) {
            resetToIdle("Saved audio missing")
            return
        }

        updateLiveBar(
            visible = true,
            dotColor = COLOR_PRIMARY_LIGHT,
            status = "Uploading saved audio",
            committed = partialSoFar,
            interim = if (useElevenLabs) "Retrying via ElevenLabs..." else "Retrying via Gemini..."
        )

        thread(name = "pw-wav-retry") {
            val bytes = try { wavFile.readBytes() } catch (e: Exception) {
                handler.post {
                    if (gen == sessionGen.get()) enterOfflineRetryState(wavPath, preset, partialSoFar, "Read failed")
                }
                return@thread
            }
            if (gen != sessionGen.get()) return@thread
            val cb: (GeminiLiveClient.RestResult) -> Unit = { res ->
                handler.post {
                    if (gen != sessionGen.get()) return@post
                    activeRestCall = null
                    if (!res.text.isNullOrBlank()) {
                        onStage1Complete(gen, res.text.trim(), preset, wavPath)
                    } else if (ElevenLabsLiveClient.isQuotaError(res.error)) {
                        handleOutOfCreditsFallback(gen, wavPath, preset, partialSoFar, res.error ?: "Out of credits")
                    } else {
                        enterOfflineRetryState(wavPath, preset, partialSoFar, res.error ?: "Connection failed")
                    }
                }
            }
            activeRestCall = if (useElevenLabs) {
                ElevenLabsLiveClient.transcribeWav(
                    wavBytes = bytes,
                    apiKey = apiKey,
                    model = AppPrefs.elevenLabsRetryModel(prefs()),
                    languageCode = preset.languageCode,
                    callback = cb
                )
            } else {
                GeminiLiveClient.transcribeWav(
                    wavBytes = bytes,
                    apiKey = apiKey,
                    model = AppPrefs.retryModel(prefs()),
                    languageCode = preset.languageCode,
                    callback = cb
                )
            }
        }
    }

    private fun onStage1Complete(gen: Long, rawText: String, preset: ShortcutPreset, wavPath: String?) {
        liveSession = null
        val durationSec = wavRecorder?.durationSeconds()
            ?: ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt().coerceAtLeast(1)

        if (!preset.postProcess) {
            saveClipRecord(wavPath, durationSec, preset, rawText, rawText, RecordingStore.STATUS_COMPLETED)
            finishAndInject(rawText, rawTextForUndo = null, badge = preset.badge, polished = false)
            return
        }

        val cleanupKey = AppPrefs.effectiveCleanupApiKey(prefs())
        if (cleanupKey.isBlank()) {
            saveClipRecord(wavPath, durationSec, preset, rawText, rawText, RecordingStore.STATUS_COMPLETED)
            finishAndInject(rawText, rawTextForUndo = null, badge = preset.badge, polished = false)
            return
        }

        updateLiveBar(
            visible = true,
            dotColor = COLOR_PRIMARY_LIGHT,
            status = "Polishing",
            committed = rawText,
            interim = ""
        )

        activeCleanupCall = PostProcessor.process(
            rawText = rawText,
            prompt = AppPrefs.cleanupPrompt(prefs()),
            apiKey = cleanupKey,
            baseUrl = AppPrefs.cleanupBaseUrl(prefs()),
            model = AppPrefs.cleanupModel(prefs()),
            context = activeContext,
            languageCode = preset.languageCode
        ) { result ->
            handler.post {
                if (gen != sessionGen.get()) return@post
                activeCleanupCall = null
                val finalText = result.text?.takeIf { it.isNotBlank() }
                if (finalText != null) {
                    saveClipRecord(wavPath, durationSec, preset, rawText, finalText, RecordingStore.STATUS_COMPLETED)
                    val undoAvailable = finalText != rawText
                    finishAndInject(
                        text = finalText,
                        rawTextForUndo = if (undoAvailable) rawText else null,
                        badge = preset.badge,
                        polished = undoAvailable
                    )
                } else {
                    Log.w(TAG, "Cleanup fallback to raw: ${result.error}")
                    saveClipRecord(wavPath, durationSec, preset, rawText, rawText, RecordingStore.STATUS_COMPLETED)
                    finishAndInject(
                        text = rawText,
                        rawTextForUndo = null,
                        badge = preset.badge,
                        polished = false,
                        customFeedback = "${preset.badge} · Inserted raw (cleanup skipped)"
                    )
                }
            }
        }
    }

    private fun finishAndInject(
        text: String,
        rawTextForUndo: String?,
        badge: String,
        polished: Boolean,
        customFeedback: String? = null
    ) {
        lastRawForUndo = rawTextForUndo
        recordHistory(text)
        wavRecorder = null
        failedClip = null
        state = State.IDLE
        updateDockVisuals()
        updateLiveBar(visible = false)

        val msg = customFeedback ?: if (polished) "$badge · Polished & inserted" else "$badge · Inserted"
        injectText(text, feedback = msg, allowUndoRaw = rawTextForUndo != null, allowFlag = lastSavedClipId != null)
    }

    private fun handleOutOfCreditsFallback(
        gen: Long,
        wavPath: String,
        preset: ShortcutPreset,
        partialSoFar: String,
        errorReason: String
    ) {
        val wavFile = File(wavPath)
        val installedModels = LocalTranscriber.availableModels(this)
        if (localTranscriber == null && installedModels.isEmpty()) {
            enterOfflineRetryState(
                wavPath, preset, partialSoFar,
                "Out of credits — download a local model or switch provider in Settings",
                headerLabel = "Out of credits · Audio saved"
            )
            return
        }

        // Persist local mode until the user manually re-enables cloud in Settings
        prefs().edit().putBoolean(AppPrefs.KEY_USE_LOCAL, true).apply()
        toast("Out of credits — switched to local model")
        updateLiveBar(
            visible = true,
            dotColor = COLOR_DOT_OFFLINE,
            status = "Out of credits · Using local model",
            committed = partialSoFar,
            interim = "Transcribing locally..."
        )

        val readyLocal = localTranscriber
        if (readyLocal != null) {
            transcribeLocalFromWav(gen, wavFile, readyLocal, preset)
        } else {
            thread(name = "pw-local-fallback-load") {
                val chosen = prefs().getString(AppPrefs.KEY_LOCAL_MODEL, "")
                    ?.takeIf { it.isNotBlank() && it in installedModels }
                    ?: installedModels.first()
                prefs().edit().putString(AppPrefs.KEY_LOCAL_MODEL, chosen).apply()
                val loaded = LocalTranscriber.create(this, chosen)
                localTranscriber = loaded
                handler.post {
                    if (gen != sessionGen.get()) return@post
                    if (loaded != null) {
                        transcribeLocalFromWav(gen, wavFile, loaded, preset)
                    } else {
                        enterOfflineRetryState(
                            wavPath, preset, partialSoFar, errorReason,
                            headerLabel = "Out of credits · Audio saved"
                        )
                    }
                }
            }
        }
    }

    private fun enterOfflineRetryState(
        wavPath: String,
        preset: ShortcutPreset,
        partialText: String,
        errorReason: String,
        headerLabel: String = "Offline · Audio saved"
    ) {
        val durationSec = wavRecorder?.durationSeconds()
            ?: ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt().coerceAtLeast(1)
        val clip = SavedClip(
            id = activeClipId.ifBlank { RecordingStore.newClipId() },
            wavPath = wavPath,
            createdAtMs = System.currentTimeMillis(),
            durationSec = durationSec,
            presetId = preset.id,
            presetBadge = preset.badge,
            languageCode = preset.languageCode,
            partialText = partialText,
            finalText = null,
            status = RecordingStore.STATUS_FAILED,
            liveDraftText = lastLiveDraftText
        )
        RecordingStore.upsertClip(this, clip)
        failedClip = clip
        state = State.ERROR_RETRY
        updateDockVisuals()
        liveTimerText?.text = formatDuration(durationSec)
        updateLiveBar(
            visible = true,
            dotColor = COLOR_DOT_ERROR,
            status = headerLabel,
            committed = partialText,
            interim = if (partialText.isBlank()) errorReason else "[remaining audio saved — tap Retry]",
            showActions = true,
            showUsePartial = partialText.isNotBlank()
        )
    }

    private fun onRetryFailedClip() {
        val clip = failedClip ?: return
        val gen = sessionGen.incrementAndGet()
        val preset = ShortcutStore.findById(prefs(), clip.presetId)
        activePreset = preset
        activeClipId = clip.id
        state = State.POLISHING
        updateDockVisuals()
        transcribeSavedWavFallback(gen, clip.wavPath, preset, clip.partialText)
    }

    private fun onUsePartialClip() {
        val clip = failedClip ?: return
        val text = clip.partialText.trim()
        if (text.isEmpty()) return
        state = State.IDLE
        failedClip = null
        wavRecorder = null
        updateDockVisuals()
        updateLiveBar(visible = false)
        injectText(text, feedback = "${clip.presetBadge} · Partial inserted (audio kept)", allowUndoRaw = false)
    }

    private fun onDiscardFailedClip() {
        val clip = failedClip
        if (clip != null) RecordingStore.deleteClip(this, clip.id)
        failedClip = null
        wavRecorder = null
        state = State.IDLE
        updateDockVisuals()
        updateLiveBar(visible = false)
        showFeedback("Saved clip discarded", allowUndoRaw = false, durationMs = 1500L)
    }

    private fun onUndoPolishClicked() {
        val raw = lastRawForUndo ?: return
        lastRawForUndo = null
        handler.removeCallbacks(hideFeedback)
        feedbackView?.visibility = View.GONE
        setFeedbackTouchable(false)
        val clip = ClipData.newPlainText("phonewhisper", raw)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        showFeedback("Raw transcript copied to clipboard", allowUndoRaw = false, durationMs = 2200L)
    }

    private fun onFlagLastClipClicked() {
        val id = lastSavedClipId ?: return
        RecordingStore.setFlagged(this, id, true)
        lastSavedClipId = null
        showFeedback("Flagged for review", allowUndoRaw = false, allowFlag = false, durationMs = 1800L)
    }

    private fun saveClipRecord(
        wavPath: String?,
        durationSec: Int,
        preset: ShortcutPreset,
        partialText: String,
        finalText: String?,
        status: String
    ) {
        if (wavPath.isNullOrBlank()) return
        val clip = SavedClip(
            id = activeClipId.ifBlank { RecordingStore.newClipId() },
            wavPath = wavPath,
            createdAtMs = System.currentTimeMillis(),
            durationSec = durationSec,
            presetId = preset.id,
            presetBadge = preset.badge,
            languageCode = preset.languageCode,
            partialText = partialText,
            finalText = finalText,
            status = status,
            liveDraftText = lastLiveDraftText
        )
        RecordingStore.upsertClip(this, clip)
        lastSavedClipId = clip.id
    }

    private fun recordHistory(text: String) {
        val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != lastHistoryPkg) {
            recentDictations.clear()
            lastHistoryPkg = pkg
        }
        recentDictations.addLast(text.take(300))
        while (recentDictations.size > 3) recentDictations.removeFirst()
    }

    private fun resetToIdle(msg: String) {
        wavRecorder = null
        liveSession = null
        state = State.IDLE
        updateDockVisuals()
        updateLiveBar(visible = false)
        showFeedback(msg, allowUndoRaw = false, durationMs = 2000L)
    }

    // --- Text Injection (Standard Android Editable Fields, Termux removed) ---

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            root.recycle()
            if (focus != null) return focus
        }
        return null
    }

    private fun injectText(
        text: String,
        feedback: String? = "Inserted",
        allowUndoRaw: Boolean = false,
        allowFlag: Boolean = false
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, allowUndoRaw = allowUndoRaw, allowFlag = allowFlag) }

        val candidates = findInjectionCandidates()
        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }
        Log.i(TAG, if (injected) "Text injected" else "Clipboard fallback only")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { root ->
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }
        windows?.filter { it.isActive || it.isFocused }?.forEach { window ->
            val root = window.root ?: return@forEach
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }
        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            if (node.performAction(action.id)) return true
        }

        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val lo = minOf(start, end)
            val hi = maxOf(start, end)
            val updated = current.replaceRange(lo, hi, text)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        }
        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    // --- Custom Views for Radial Menu & Cancel Icon ---

    private inner class CancelCrossView(ctx: Context) : View(ctx) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61C1C1E.toInt() }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x2EFFFFFF
            strokeWidth = 1.2f * dp
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE8EAED.toInt()
            strokeWidth = 2f * dp
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - dp
            canvas.drawCircle(cx, cy, r, bgPaint)
            canvas.drawCircle(cx, cy, r, strokePaint)
            val arm = 5.5f * dp
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint)
            canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, crossPaint)
        }
    }

    private data class ShortcutRowRect(val preset: ShortcutPreset, val rect: RectF, val anchorX: Float, val anchorY: Float)

    private inner class RadialMenuView(
        ctx: Context,
        private val originX: Float,
        private val originY: Float,
        private val fanLeft: Boolean,
        private val presets: List<ShortcutPreset>
    ) : View(ctx) {

        private val rows: List<ShortcutRowRect>
        var hoveredPreset: ShortcutPreset? = null
            private set

        private val scrimPaint = Paint().apply { color = 0x52000000 }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * dp
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val originDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_PRIMARY_LIGHT }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textAlign = Paint.Align.CENTER
            textSize = 12.5f * dp
            typeface = Typeface.DEFAULT_BOLD
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 15.5f * dp
            typeface = Typeface.DEFAULT_BOLD
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_MUTED
            textSize = 12f * dp
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            textSize = 12f * dp
            textAlign = Paint.Align.CENTER
        }

        private val rowW = minOf(232f * dp, screenW - 96f * dp)
        private val rowH = 56f * dp
        private val rowGap = 10f * dp
        private val pillLeft: Float
        private val pillRight: Float
        private val stackTop: Float
        private val stackBottom: Float

        init {
            val gapFromMic = 36f * dp
            if (fanLeft) {
                pillRight = (originX - gapFromMic).coerceAtMost(screenW - 74f * dp)
                pillLeft = (pillRight - rowW).coerceAtLeast(12f * dp)
            } else {
                pillLeft = (originX + gapFromMic).coerceAtLeast(74f * dp)
                pillRight = (pillLeft + rowW).coerceAtMost(screenW - 12f * dp)
            }

            val totalH = presets.size * rowH + (presets.size - 1).coerceAtLeast(0) * rowGap
            val minTop = statusBarHeight() + 42f * dp
            val maxBottom = screenH - 48f * dp
            val rawTop = originY - totalH / 2f
            stackTop = rawTop.coerceIn(minTop, (maxBottom - totalH).coerceAtLeast(minTop))
            stackBottom = stackTop + totalH

            rows = presets.mapIndexed { idx, preset ->
                val top = stackTop + idx * (rowH + rowGap)
                val rect = RectF(pillLeft, top, pillRight, top + rowH)
                val anchorX = if (fanLeft) rect.right else rect.left
                val anchorY = rect.centerY()
                ShortcutRowRect(preset, rect, anchorX, anchorY)
            }
        }

        fun updatePointer(rawX: Float, rawY: Float): Boolean {
            // Require sliding away from the mic button toward the pill column so releasing on mic cancels
            val distFromOrigin = hypot((rawX - originX).toDouble(), (rawY - originY).toDouble()).toFloat()
            val movedTowardPills = if (fanLeft) rawX < originX - 24f * dp else rawX > originX + 24f * dp
            var best: ShortcutPreset? = null
            if (distFromOrigin >= 28f * dp && movedTowardPills) {
                val slop = rowGap / 2f + 4f * dp
                for (r in rows) {
                    if (rawY >= r.rect.top - slop && rawY <= r.rect.bottom + slop &&
                        rawX >= r.rect.left - 24f * dp && rawX <= r.rect.right + 28f * dp
                    ) {
                        best = r.preset
                        break
                    }
                }
            }
            if (best != hoveredPreset) {
                hoveredPreset = best
                invalidate()
                return true
            }
            return false
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

            val hovered = hoveredPreset

            // Draw connector lines from mic center to each pill row (inactive first, active on top)
            for (r in rows) {
                val active = r.preset == hovered
                if (!active) {
                    linePaint.color = 0x38FFFFFF
                    linePaint.strokeWidth = 1.5f * dp
                    canvas.drawLine(originX, originY, r.anchorX, r.anchorY, linePaint)
                }
            }
            rows.firstOrNull { it.preset == hovered }?.let { r ->
                linePaint.color = COLOR_PRIMARY_LIGHT
                linePaint.strokeWidth = 3f * dp
                canvas.drawLine(originX, originY, r.anchorX, r.anchorY, linePaint)
            }
            canvas.drawCircle(originX, originY, 5f * dp, originDotPaint)

            // Subtle top hint centered above the pill stack
            val hintText = if (hovered != null) "Release to start ${hovered.name}" else "Slide onto a row · Release on mic to cancel"
            canvas.drawText(hintText, (pillLeft + pillRight) / 2f, stackTop - 12f * dp, hintPaint)

            // Draw wide horizontal pills (badge + name + mode kept cleanly on the left side away from right-edge thumb)
            for (r in rows) {
                val active = r.preset == hovered
                pillPaint.color = if (active) COLOR_PRIMARY else 0xF51C1C1E.toInt()
                pillBorderPaint.color = if (active) 0xFFD2E3FC.toInt() else 0x33FFFFFF
                canvas.drawRoundRect(r.rect, 15f * dp, 15f * dp, pillPaint)
                canvas.drawRoundRect(r.rect, 15f * dp, 15f * dp, pillBorderPaint)

                // Badge chip on left
                val badgeW = 46f * dp
                val badgeH = 28f * dp
                val badgeLeft = r.rect.left + 12f * dp
                val badgeTop = r.rect.centerY() - badgeH / 2f
                val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH)
                badgeBgPaint.color = if (active) 0x38000000 else 0x268AB4F8
                canvas.drawRoundRect(badgeRect, 8f * dp, 8f * dp, badgeBgPaint)
                badgeTextPaint.color = if (active) COLOR_TEXT_PRIMARY else COLOR_PRIMARY_LIGHT
                val badgeY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
                canvas.drawText(r.preset.badge, badgeRect.centerX(), badgeY, badgeTextPaint)

                // Title + compact mode subtitle
                val textX = badgeRect.right + 12f * dp
                val modeStr = buildString {
                    append(if (r.preset.postProcess) "Polish" else "Raw")
                    append(" · ")
                    append(if (r.preset.screenContext) "Context" else "No context")
                }
                subPaint.color = if (active) 0xE6FFFFFF.toInt() else COLOR_TEXT_MUTED
                canvas.drawText(r.preset.name, textX, r.rect.centerY() - 3f * dp, titlePaint)
                canvas.drawText(modeStr, textX, r.rect.centerY() + 14f * dp, subPaint)
            }
        }
    }

    // --- Helpers ---

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (28 * dp).toInt()
    }

    private fun formatDuration(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    private fun prefs() = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
