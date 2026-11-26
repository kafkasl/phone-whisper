package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface LiveStreamSession {
    fun sendPcm(pcm: ByteArray, length: Int = pcm.size)
    fun finish()
    fun cancel()
}

object GeminiLiveClient {
    private const val WS_ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private const val REST_BASE =
        "https://generativelanguage.googleapis.com/v1beta/models"

    private const val MAX_PRE_SETUP_CHUNKS = 150
    private const val FINISH_TIMEOUT_MS = 2200L

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val restClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gemini-live-timer").apply { isDaemon = true }
    }

    interface StreamListener {
        fun onReady() {}
        fun onTranscriptUpdate(committed: String, interim: String)
        fun onFinalized(fullText: String)
        fun onError(message: String, partialCommitted: String)
    }

    data class RestResult(val text: String?, val error: String?)

    sealed class ServerEvent {
        data object SetupComplete : ServerEvent()
        data class Interim(val text: String) : ServerEvent()
        data class FinalSegment(val text: String) : ServerEvent()
        data object GenerationComplete : ServerEvent()
        data class Error(val message: String) : ServerEvent()
    }

    fun normalizeModel(model: String): String {
        val m = model.trim()
        return if (m.startsWith("models/")) m else "models/$m"
    }

    fun buildSetupJson(model: String, languageBcp47: String?): String {
        val transcriptionCfg = JSONObject()
        if (!languageBcp47.isNullOrBlank()) {
            transcriptionCfg.put("languageCodes", JSONArray().put(languageBcp47))
        }
        val setup = JSONObject().apply {
            put("model", normalizeModel(model))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("TEXT"))
            })
            put("inputAudioTranscription", transcriptionCfg)
        }
        return JSONObject().put("setup", setup).toString()
    }

    fun buildAudioChunkJson(pcm: ByteArray, length: Int = pcm.size): String {
        val slice = if (length == pcm.size) pcm else pcm.copyOf(length)
        val encoded = Base64.getEncoder().encodeToString(slice)
        val audio = JSONObject().apply {
            put("mimeType", "audio/pcm;rate=16000")
            put("data", encoded)
        }
        return JSONObject()
            .put("realtimeInput", JSONObject().put("audio", audio))
            .toString()
    }

    fun buildAudioStreamEndJson(): String =
        JSONObject()
            .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
            .toString()

    fun parseServerMessage(json: String): List<ServerEvent> {
        val events = mutableListOf<ServerEvent>()
        try {
            val obj = JSONObject(json)
            if (obj.has("setupComplete")) {
                events.add(ServerEvent.SetupComplete)
            }
            if (obj.has("error")) {
                val err = obj.optJSONObject("error")
                val msg = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Server error"
                events.add(ServerEvent.Error(msg))
            }
            val sc = obj.optJSONObject("serverContent")
            if (sc != null) {
                val interim = sc.optJSONObject("interimInputTranscription")?.optString("text")
                if (!interim.isNullOrEmpty()) {
                    events.add(ServerEvent.Interim(interim))
                }
                val finalTr = sc.optJSONObject("inputTranscription")?.optString("text")
                if (!finalTr.isNullOrEmpty()) {
                    events.add(ServerEvent.FinalSegment(finalTr))
                }
                if (sc.optBoolean("generationComplete", false)) {
                    events.add(ServerEvent.GenerationComplete)
                }
            }
        } catch (_: Exception) {}
        return events
    }

    fun parseRestResponse(json: String): RestResult = try {
        val obj = JSONObject(json)
        when {
            obj.has("candidates") -> {
                val candidates = obj.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0)
                        .optJSONObject("content")
                        ?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val t = parts.getJSONObject(i).optString("text", "")
                            if (t.isNotEmpty()) sb.append(t)
                        }
                    }
                    val text = sb.toString().trim()
                    if (text.isNotEmpty()) RestResult(text, null)
                    else RestResult(null, "Empty transcription response")
                } else {
                    RestResult(null, "No candidates in response")
                }
            }
            obj.has("error") -> RestResult(null, obj.getJSONObject("error").optString("message", "API error"))
            else -> RestResult(null, "Unknown response format")
        }
    } catch (e: Exception) {
        RestResult(null, e.message ?: "Parse error")
    }

    class LiveSession internal constructor(
        private val model: String,
        private val languageBcp47: String?,
        private val listener: StreamListener
    ) : LiveStreamSession {
        private var ws: WebSocket? = null
        private val pendingChunks = ArrayDeque<String>()
        private val committedSegments = mutableListOf<String>()
        private var interimText: String = ""
        private var setupComplete = false
        private var finishRequested = false
        private var streamEndSent = false
        private val terminated = AtomicBoolean(false)
        private var finishTimer: ScheduledFuture<*>? = null

        internal fun attach(webSocket: WebSocket) {
            synchronized(this) { ws = webSocket }
        }

        internal fun onOpen(webSocket: WebSocket) {
            if (terminated.get()) {
                webSocket.close(1000, "cancelled")
                return
            }
            webSocket.send(buildSetupJson(model, languageBcp47))
        }

        @Synchronized
        override fun sendPcm(pcm: ByteArray, length: Int) {
            if (terminated.get() || finishRequested || length <= 0) return
            val frame = buildAudioChunkJson(pcm, length)
            if (setupComplete) {
                ws?.send(frame)
            } else {
                if (pendingChunks.size >= MAX_PRE_SETUP_CHUNKS) pendingChunks.removeFirst()
                pendingChunks.addLast(frame)
            }
        }

        @Synchronized
        override fun finish() {
            if (terminated.get() || finishRequested) return
            finishRequested = true
            if (setupComplete && !streamEndSent) {
                streamEndSent = true
                ws?.send(buildAudioStreamEndJson())
            }
            scheduleFinishTimeout()
        }

        override fun cancel() {
            if (!terminated.compareAndSet(false, true)) return
            synchronized(this) {
                finishTimer?.cancel(false)
                finishTimer = null
                pendingChunks.clear()
                ws?.close(1000, "cancelled")
                ws = null
            }
        }

        @Synchronized
        fun currentCommittedText(): String = committedSegments.joinToString(" ").trim()

        @Synchronized
        fun currentFullText(): String {
            val c = currentCommittedText()
            val i = interimText.trim()
            return when {
                c.isEmpty() -> i
                i.isEmpty() -> c
                else -> "$c $i"
            }
        }

        internal fun onServerText(raw: String) {
            if (terminated.get()) return
            val events = parseServerMessage(raw)
            var readyCallback = false
            var updatePair: Pair<String, String>? = null
            var finalizedText: String? = null
            var errorPair: Pair<String, String>? = null

            synchronized(this) {
                if (terminated.get()) return
                for (ev in events) {
                    when (ev) {
                        is ServerEvent.SetupComplete -> {
                            setupComplete = true
                            while (pendingChunks.isNotEmpty()) {
                                ws?.send(pendingChunks.removeFirst())
                            }
                            if (finishRequested && !streamEndSent) {
                                streamEndSent = true
                                ws?.send(buildAudioStreamEndJson())
                                scheduleFinishTimeout()
                            }
                            readyCallback = true
                        }
                        is ServerEvent.Interim -> {
                            interimText = ev.text
                            updatePair = currentCommittedText() to interimText
                        }
                        is ServerEvent.FinalSegment -> {
                            val clean = ev.text.trim()
                            if (clean.isNotEmpty()) committedSegments.add(clean)
                            interimText = ""
                            updatePair = currentCommittedText() to ""
                        }
                        is ServerEvent.GenerationComplete -> {
                            if (finishRequested && terminated.compareAndSet(false, true)) {
                                finishTimer?.cancel(false)
                                finishTimer = null
                                finalizedText = currentFullText()
                                ws?.close(1000, "done")
                                ws = null
                            }
                        }
                        is ServerEvent.Error -> {
                            if (terminated.compareAndSet(false, true)) {
                                finishTimer?.cancel(false)
                                finishTimer = null
                                errorPair = ev.message to currentFullText()
                                ws?.close(1000, "error")
                                ws = null
                            }
                        }
                    }
                }
            }

            if (readyCallback) listener.onReady()
            updatePair?.let { (c, i) -> listener.onTranscriptUpdate(c, i) }
            finalizedText?.let { listener.onFinalized(it) }
            errorPair?.let { (msg, partial) -> listener.onError(msg, partial) }
        }

        internal fun onFailure(reason: String) {
            var partial = ""
            if (!terminated.compareAndSet(false, true)) return
            synchronized(this) {
                finishTimer?.cancel(false)
                finishTimer = null
                partial = currentFullText()
                ws = null
            }
            listener.onError(reason, partial)
        }

        internal fun onClosed(code: Int, reason: String) {
            if (!terminated.compareAndSet(false, true)) return
            var finishText: String? = null
            var errorMsg: String? = null
            var partial = ""
            synchronized(this) {
                finishTimer?.cancel(false)
                finishTimer = null
                partial = currentFullText()
                ws = null
                if (finishRequested) {
                    finishText = partial
                } else {
                    errorMsg = reason.ifBlank { "Connection closed ($code)" }
                }
            }
            if (finishText != null) listener.onFinalized(finishText!!)
            else if (errorMsg != null) listener.onError(errorMsg!!, partial)
        }

        private fun scheduleFinishTimeout() {
            finishTimer?.cancel(false)
            finishTimer = scheduler.schedule({
                if (!terminated.compareAndSet(false, true)) return@schedule
                val finalStr: String
                synchronized(this) {
                    finalStr = currentFullText()
                    ws?.close(1000, "timeout_done")
                    ws = null
                }
                listener.onFinalized(finalStr)
            }, FINISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun startStream(
        apiKey: String,
        model: String,
        languageBcp47: String?,
        listener: StreamListener
    ): LiveSession {
        val session = LiveSession(model, languageBcp47, listener)
        val request = Request.Builder()
            .url("$WS_ENDPOINT?key=$apiKey")
            .build()
        val webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                session.onOpen(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                session.onServerText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                session.onServerText(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = t.message ?: response?.message ?: "Network error"
                session.onFailure(msg)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session.onClosed(code, reason)
            }
        })
        session.attach(webSocket)
        return session
    }

    fun transcribeWav(
        wavBytes: ByteArray,
        apiKey: String,
        model: String,
        languageCode: String,
        callback: (RestResult) -> Unit
    ): Call {
        val langHint = when (languageCode.lowercase()) {
            "en" -> " in English"
            "es" -> " in Spanish"
            "ca" -> " in Catalan"
            else -> ""
        }
        val prompt = "Transcribe this audio verbatim$langHint. Output only the transcript text with no commentary."
        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            put(JSONObject().put("inlineData", JSONObject().apply {
                put("mimeType", "audio/wav")
                put("data", Base64.getEncoder().encodeToString(wavBytes))
            }))
        }
        val bodyJson = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", parts))
        )
        val cleanModel = model.trim().removePrefix("models/")
        val request = Request.Builder()
            .url("$REST_BASE/$cleanModel:generateContent?key=$apiKey")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = restClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                callback(RestResult(null, e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful && raw.isBlank()) {
                    callback(RestResult(null, "HTTP ${response.code}"))
                    return
                }
                callback(parseRestResponse(raw))
            }
        })
        return call
    }
}
