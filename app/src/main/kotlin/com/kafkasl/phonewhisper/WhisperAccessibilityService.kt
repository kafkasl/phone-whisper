package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val FEEDBACK_OFFSET_DP = 56

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
    private var button: ImageView? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Local transcription engine (loaded lazily)
    private var localTranscriber: LocalTranscriber? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        // Try to load local model in background
        thread { initLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    private fun initLocalModel() {
        val modelName = prefs().getString("model_name", "") ?: ""
        if (modelName.isBlank()) {
            // Auto-detect first available model
            val models = LocalTranscriber.availableModels(this)
            if (models.isNotEmpty()) {
                Log.i(TAG, "Auto-detected model: ${models.first()}")
                localTranscriber = LocalTranscriber.create(this, models.first())
            }
        } else {
            localTranscriber = LocalTranscriber.create(this, modelName)
        }
        if (localTranscriber != null) {
            Log.i(TAG, "Local transcription ready")
        } else {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (BTN_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - margin
            y = screenH / 2 - size / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        img.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + size / 2 > screenW / 2)
                            screenW - size - margin else margin
                        wm.updateViewLayout(v, params)
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(img, params)
        wm.addView(feedback, feedbackParams)
        button = img
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        button?.let {
            wm.removeView(it)
            button = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, 1200)
        }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        pcmStream = ByteArrayOutputStream()
        audioRecord!!.startRecording()
        state = State.RECORDING
        setAppearance(COLOR_RECORDING)
        startPulse()

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) pcmStream?.write(buf, 0, n)
            }
        }
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopPulse()
        setAppearance(COLOR_BUSY)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }

        val useLocal = prefs().getBoolean("use_local", true)
        val local = localTranscriber

        if (useLocal && local != null) {
            transcribeLocal(pcm, local)
        } else {
            transcribeApi(pcm)
        }
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber) {
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")

                handleTranscriptionResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                handler.post {
                    toast("Local error: ${e.message}")
                    state = State.IDLE
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) { reset("Set API key in Phone Whisper app"); return }

        TranscriberClient.transcribe(wav, apiKey) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text)
            } else {
                handler.post {
                    toast("Error: ${result.error ?: "empty transcript"}")
                    state = State.IDLE
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?) {
        if (text.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                setAppearance(COLOR_IDLE)
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = prefs().getString("api_key", "") ?: ""

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    state = State.IDLE
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            
            PostProcessor.process(text, prompt, apiKey) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        injectText(result.text)
                    } else {
                        toast("Cleanup failed: ${result.error}")
                        // Fallback to original text
                        injectText(text)
                    }
                    state = State.IDLE
                    setAppearance(COLOR_IDLE)
                }
            }
        } else {
            handler.post {
                injectText(text)
                state = State.IDLE
                setAppearance(COLOR_IDLE)
            }
        }
    }

    private fun reset(msg: String) {
        toast(msg); state = State.IDLE; setAppearance(COLOR_IDLE)
    }

    // --- Text injection ---

    private fun injectText(text: String) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        showFeedback("Copied to clipboard")

        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focused != null) {
            focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            focused.recycle()
        }
        root?.recycle()
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
