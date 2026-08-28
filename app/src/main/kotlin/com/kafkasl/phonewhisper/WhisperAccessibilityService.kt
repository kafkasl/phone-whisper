package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.UUID
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
        private const val RING_DP = 128
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var equalizerView: EqualizerView? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private var activeTraceId: String = ""
    private var recordingStartedAtMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                cancelRecording("screen_off")
            }
        }
    }

    // Local transcription engine (loaded lazily)
    private var localTranscriber: LocalTranscriber? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        registerScreenReceiver()
        showOverlay()
        // Try to load local model in background
        thread { initLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        unregisterScreenReceiver()
        cancelRecording("service_destroy")
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
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        val edgeOffset = (ringSize - buttonSize) / 2

        val busySpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val equalizer = EqualizerView(this).apply {
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            addView(equalizer, FrameLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt(), Gravity.CENTER))
            addView(busySpinner, FrameLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt(), Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin + edgeOffset
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
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
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin + edgeOffset else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
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

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = busySpinner
        equalizerView = equalizer
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
        applyVisualState()
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        button = null
        spinner = null
        equalizerView = null
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

    private fun applyVisualState() {
        handler.post {
            when (state) {
                State.IDLE -> showIdleVisual()
                State.RECORDING -> showRecordingVisual()
                State.TRANSCRIBING -> showBusyVisual()
            }
        }
    }

    private fun showIdleVisual() {
        equalizerView?.visibility = View.GONE
        equalizerView?.reset()
        spinner?.visibility = View.GONE
        button?.setImageResource(R.drawable.ic_mic)
        button?.background = circle(COLOR_IDLE)
        button?.scaleX = 1f
        button?.scaleY = 1f
        button?.alpha = 1f
    }

    private fun showRecordingVisual() {
        spinner?.visibility = View.GONE
        equalizerView?.visibility = View.VISIBLE
        button?.setImageDrawable(null)
        button?.background = circle(COLOR_RECORDING)
        button?.alpha = 1f
    }

    private fun showBusyVisual() {
        equalizerView?.visibility = View.GONE
        equalizerView?.reset()
        button?.setImageDrawable(null)
        button?.background = circle(COLOR_BUSY)
        button?.scaleX = 1f
        button?.scaleY = 1f
        spinner?.visibility = View.VISIBLE
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

    private fun showFeedback(text: String, durationMs: Long = 2000) {
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
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    private fun updateAudioLevel(buffer: ByteArray, byteCount: Int) {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < byteCount) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += sample * sample.toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return

        val rms = kotlin.math.sqrt(sum / samples) / 32768.0
        val level = ((rms * 8.5).coerceIn(0.02, 1.0)).toFloat()
        handler.post {
            if (state == State.RECORDING) {
                equalizerView?.setLevel(level)
                val scale = 1f + level * 1.40f
                button?.scaleX = scale
                button?.scaleY = scale
            }
        }
    }

    private fun registerScreenReceiver() {
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenReceiverRegistered = true
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver can already be gone if Android tears down the service process.
        }
        screenReceiverRegistered = false
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
        val traceId = newTraceId()
        activeTraceId = traceId
        trace(traceId, "record_start_requested")
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            trace(traceId, "record_start_rejected", "missing_audio_permission")
            toast("Grant audio permission in Phone Whisper app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        trace(traceId, "audio_buffer_size", "bytes=$bufSize")
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: SecurityException) {
            trace(traceId, "record_start_security_error", e.message ?: "denied")
            toast("Audio permission denied"); return
        }

        pcmStream = ByteArrayOutputStream()
        audioRecord!!.startRecording()
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        state = State.RECORDING
        applyVisualState()
        trace(traceId, "record_started")

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    pcmStream?.write(buf, 0, n)
                    updateAudioLevel(buf, n)
                }
            }
        }
    }

    private fun stopAndTranscribe() {
        val traceId = activeTraceId.ifBlank { newTraceId().also { activeTraceId = it } }
        val recordDurationMs = if (recordingStartedAtMs > 0) SystemClock.elapsedRealtime() - recordingStartedAtMs else 0L
        trace(traceId, "record_stop_requested", "recordDurationMs=$recordDurationMs")
        state = State.TRANSCRIBING
        stopPulse()
        applyVisualState()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null
        trace(traceId, "record_stopped", "pcmBytes=${pcm.size} recordDurationMs=$recordDurationMs")

        if (pcm.isEmpty()) { reset("No audio captured"); return }

        val useLocal = prefs().getBoolean("use_local", true)
        val local = localTranscriber
        trace(traceId, "transcription_route", "useLocal=$useLocal hasLocal=${local != null}")

        if (useLocal && local != null) {
            transcribeLocal(pcm, local, traceId)
        } else {
            transcribeApi(pcm, traceId)
        }
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber, traceId: String) {
        thread {
            try {
                trace(traceId, "local_prepare_start", "pcmBytes=${pcm.size}")
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }
                trace(traceId, "local_prepare_end", "samples=${samples.size}")

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                trace(traceId, "local_transcribe_end", "elapsedMs=$ms audioSeconds=${samples.size / SAMPLE_RATE}")

                handleTranscriptionResult(text, traceId)
            } catch (e: Exception) {
                trace(traceId, "local_transcribe_failed", e.message ?: "unknown")
                Log.e(TAG, "trace=$traceId Local transcription failed", e)
                handler.post {
                    toast("Local error: ${e.message}")
                    state = State.IDLE
                    applyVisualState()
                }
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray, traceId: String) {
        val wavStarted = SystemClock.elapsedRealtime()
        trace(traceId, "wav_encode_start", "pcmBytes=${pcm.size}")
        val wav = WavWriter.encode(pcm)
        trace(traceId, "wav_encode_end", "elapsedMs=${SystemClock.elapsedRealtime() - wavStarted} wavBytes=${wav.size}")
        val apiKey = prefs().getString("api_key", "") ?: ""
        val transcriptionBaseUrl = prefs().getString("transcription_base_url", "") ?: ""
        if (apiKey.isBlank() && transcriptionBaseUrl.isBlank()) {
            trace(traceId, "api_transcribe_rejected", "missing_api_key_and_url")
            reset("Set API key or transcription URL in Phone Whisper app")
            return
        }

        trace(traceId, "api_transcribe_start", "url=${TranscriberClient.transcriptionUrl(transcriptionBaseUrl)} wavBytes=${wav.size} hasToken=${apiKey.isNotBlank()}")
        TranscriberClient.transcribe(wav, apiKey, transcriptionBaseUrl, traceId) { result ->
            trace(traceId, "api_transcribe_callback", "status=${result.statusCode} elapsedMs=${result.elapsedMs} hasText=${!result.text.isNullOrBlank()} error=${result.error ?: ""}")
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text, traceId)
            } else {
                handler.post {
                    toast("Error: ${result.error ?: "empty transcript"}")
                    state = State.IDLE
                    applyVisualState()
                }
            }
        }
    }

    private fun cancelRecording(reason: String) {
        if (state != State.RECORDING) return

        val traceId = activeTraceId.ifBlank { newTraceId().also { activeTraceId = it } }
        val recordDurationMs = if (recordingStartedAtMs > 0) SystemClock.elapsedRealtime() - recordingStartedAtMs else 0L
        trace(traceId, "record_cancelled", "reason=$reason recordDurationMs=$recordDurationMs")

        state = State.IDLE
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        audioRecord?.release()
        audioRecord = null
        pcmStream = null
        recordingStartedAtMs = 0L
        stopPulse()
        removeOverlay()
        showOverlay()
    }

    private fun handleTranscriptionResult(text: String?, traceId: String = activeTraceId) {
        trace(traceId, "transcription_result_received", "chars=${text?.length ?: 0}")
        if (text.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                applyVisualState()
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = prefs().getString("api_key", "") ?: ""

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    trace(traceId, "postprocess_skipped", "missing_api_key")
                    injectText(text, traceId = traceId)
                    state = State.IDLE
                    applyVisualState()
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            trace(traceId, "postprocess_start", "promptChars=${prompt.length}")
            
            PostProcessor.process(text, prompt, apiKey) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        trace(traceId, "postprocess_success", "chars=${result.text.length}")
                        injectText(result.text, traceId = traceId)
                    } else {
                        trace(traceId, "postprocess_failed", result.error ?: "empty")
                        injectText(text, feedback = "Cleanup failed - raw text used", feedbackDurationMs = 3000, traceId = traceId)
                    }
                    state = State.IDLE
                    applyVisualState()
                }
            }
        } else {
            handler.post {
                injectText(text, traceId = traceId)
                state = State.IDLE
                applyVisualState()
            }
        }
    }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        applyVisualState()
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = null,
        feedbackDurationMs: Long = 2000,
        traceId: String = activeTraceId
    ) {
        val copyToClipboard = prefs().getBoolean("copy_transcript_to_clipboard", false)
        trace(traceId, "inject_start", "chars=${text.length} copyToClipboard=$copyToClipboard")
        if (copyToClipboard) {
            val clip = ClipData.newPlainText("phonewhisper", text)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            trace(traceId, "clipboard_set", "chars=${text.length}")
        }

        val candidates = findInjectionCandidates()
        trace(traceId, "inject_candidates", "count=${candidates.size}")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text, allowPaste = copyToClipboard, traceId = traceId)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        val finalFeedback = feedback ?: when {
            copyToClipboard -> "Copied to clipboard"
            !injected -> "Could not insert"
            else -> null
        }
        finalFeedback?.let { showFeedback(it, feedbackDurationMs) }
        trace(traceId, "inject_end", "injected=$injected copyToClipboard=$copyToClipboard")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
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
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(
        node: AccessibilityNodeInfo,
        text: String,
        allowPaste: Boolean,
        traceId: String
    ): Boolean {
        logNode("Trying node", node, traceId)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        if (allowPaste) {
            findCustomPasteAction(node)?.let { action ->
                val ok = node.performAction(action.id)
                trace(traceId, "inject_custom_paste", "label=${action.label} id=${action.id} ok=$ok")
                if (ok) return true
            }

            val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            trace(traceId, "inject_action_paste", "ok=$pasteOk")
            if (pasteOk) return true
        }

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val resolved = editableText(node)
            val current = resolved.text
            resolved.ignoredReason?.let {
                trace(traceId, "inject_existing_text_ignored", "reason=$it")
            }
            val hasSelection = node.textSelectionStart >= 0 &&
                node.textSelectionEnd >= 0 &&
                node.textSelectionStart <= current.length &&
                node.textSelectionEnd <= current.length
            val updated = if (hasSelection && current.isNotEmpty()) {
                val replacementStart = minOf(node.textSelectionStart, node.textSelectionEnd)
                val replacementEnd = maxOf(node.textSelectionStart, node.textSelectionEnd)
                current.replaceRange(replacementStart, replacementEnd, text)
            } else {
                text
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            trace(traceId, "inject_action_set_text", "ok=$setTextOk currentChars=${current.length} hadSelection=$hasSelection")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun editableText(node: AccessibilityNodeInfo): InjectionText.ResolvedText =
        InjectionText.resolveEditableText(
            rawText = node.text?.toString().orEmpty(),
            hintText = node.hintText?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            packageName = node.packageName?.toString().orEmpty(),
            isFocused = node.isFocused,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd
        )

    private fun logNode(prefix: String, node: AccessibilityNodeInfo, traceId: String) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "trace=$traceId $prefix package=${node.packageName} class=${node.className} viewId=${node.viewIdResourceName} focused=${node.isFocused} editable=${node.isEditable} selection=${node.textSelectionStart}:${node.textSelectionEnd} text=${node.text} hint=${node.hintText} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    private fun newTraceId(): String = UUID.randomUUID().toString().take(8)

    private fun trace(traceId: String, stage: String, details: String = "") {
        Log.i(TAG, "trace=$traceId stage=$stage $details")
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
