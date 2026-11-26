package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ElevenLabsLiveClient {
    private const val WS_BASE = "https://api.elevenlabs.io/v1/speech-to-text/realtime"
    private const val REST_ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"
    private const val SUBSCRIPTION_ENDPOINT = "https://api.elevenlabs.io/v1/user/subscription"
    private const val MAX_PRE_SETUP_CHUNKS = 150
    private const val FINISH_TIMEOUT_MS = 2200L

    data class SubscriptionInfo(val characterCount: Int, val characterLimit: Int) {
        val remaining: Int get() = (characterLimit - characterCount).coerceAtLeast(0)
        val isLow: Boolean get() = characterLimit > 0 && remaining <= 1000
    }

    fun isQuotaError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("quota") ||
            m.contains("credit") ||
            m.contains("insufficient") ||
            m.contains("payment") ||
            m.contains("http 402") ||
            m.contains("402")
    }

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
        Thread(r, "elevenlabs-live-timer").apply { isDaemon = true }
    }

    sealed class ServerEvent {
        data object SessionStarted : ServerEvent()
        data class Partial(val text: String) : ServerEvent()
        data class Committed(val text: String) : ServerEvent()
        data class Error(val message: String) : ServerEvent()
    }

    fun buildWsUrl(model: String, languageCode: String?): String {
        val builder = WS_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("model_id", model.trim().ifEmpty { AppPrefs.DEFAULT_ELEVENLABS_STREAM_MODEL })
            .addQueryParameter("audio_format", "pcm_16000")
            .addQueryParameter("commit_strategy", "manual")
            .addQueryParameter("no_verbatim", "true")
        val lang = normalizeStreamLanguage(languageCode)
        if (lang != null) builder.addQueryParameter("language_code", lang)
        return builder.build().toString().replaceFirst("https://", "wss://")
    }

    internal fun normalizeStreamLanguage(code: String?): String? {
        val c = code?.trim()?.lowercase().orEmpty()
        return when {
            c.isEmpty() || c == "auto" -> null
            else -> c.substringBefore('-')
        }
    }

    internal fun normalizeRestLanguage(code: String?): String? {
        val c = normalizeStreamLanguage(code) ?: return null
        return when (c) {
            "ca" -> "cat"
            "es" -> "spa"
            "en" -> "eng"
            else -> c
        }
    }

    fun buildAudioChunkJson(pcm: ByteArray, length: Int = pcm.size, commit: Boolean = false): String {
        val encoded = if (length <= 0) {
            ""
        } else {
            val slice = if (length == pcm.size) pcm else pcm.copyOf(length)
            Base64.getEncoder().encodeToString(slice)
        }
        return JSONObject().apply {
            put("message_type", "input_audio_chunk")
            put("audio_base_64", encoded)
            put("sample_rate", 16000)
            put("commit", commit)
        }.toString()
    }

    fun parseServerMessage(json: String): ServerEvent? {
        return try {
            val obj = JSONObject(json)
            val mt = obj.optString("message_type", "")
            when {
                mt == "session_started" -> ServerEvent.SessionStarted
                mt == "partial_transcript" -> ServerEvent.Partial(obj.optString("text", ""))
                mt == "committed_transcript" -> ServerEvent.Committed(obj.optString("text", ""))
                mt == "quota_exceeded" || mt == "insufficient_credits" ||
                    mt == "rate_limited" || mt == "auth_error" ||
                    mt.contains("error", ignoreCase = true) -> {
                    val detail = obj.optString("error").ifBlank { obj.optString("message", mt) }
                    val msg = if (mt == "quota_exceeded" && !detail.contains("quota", ignoreCase = true)) {
                        "quota_exceeded: $detail"
                    } else {
                        detail
                    }
                    ServerEvent.Error(msg)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseSubscriptionResponse(json: String): SubscriptionInfo? = try {
        val obj = JSONObject(json)
        if (obj.has("character_count") && obj.has("character_limit")) {
            SubscriptionInfo(obj.getInt("character_count"), obj.getInt("character_limit"))
        } else null
    } catch (_: Exception) {
        null
    }

    fun fetchSubscription(apiKey: String, callback: (SubscriptionInfo?) -> Unit): Call {
        val request = Request.Builder()
            .url(SUBSCRIPTION_ENDPOINT)
            .header("xi-api-key", apiKey)
            .get()
            .build()
        val call = restClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                val raw = response.body?.string() ?: ""
                callback(if (response.isSuccessful) parseSubscriptionResponse(raw) else null)
            }
        })
        return call
    }

    fun parseRestResponse(json: String): GeminiLiveClient.RestResult = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> {
                val t = obj.optString("text", "").trim()
                if (t.isNotEmpty()) GeminiLiveClient.RestResult(t, null)
                else GeminiLiveClient.RestResult(null, "Empty transcription response")
            }
            obj.has("detail") -> {
                val d = obj.opt("detail")
                val msg = when (d) {
                    is JSONObject -> d.optString("message", d.toString())
                    else -> d?.toString() ?: "API error"
                }
                GeminiLiveClient.RestResult(null, msg)
            }
            else -> GeminiLiveClient.RestResult(null, "Unknown response format")
        }
    } catch (e: Exception) {
        GeminiLiveClient.RestResult(null, e.message ?: "Parse error")
    }

    class LiveSession internal constructor(
        private val listener: GeminiLiveClient.StreamListener
    ) : LiveStreamSession {
        private var ws: WebSocket? = null
        private val pendingChunks = ArrayDeque<String>()
        private val committedSegments = mutableListOf<String>()
        private var interimText = ""
        private var sessionReady = false
        private var finishRequested = false
        private var commitSent = false
        private val terminated = AtomicBoolean(false)
        private var finishTimer: ScheduledFuture<*>? = null

        internal fun attach(webSocket: WebSocket) {
            synchronized(this) { ws = webSocket }
        }

        @Synchronized
        override fun sendPcm(pcm: ByteArray, length: Int) {
            if (terminated.get() || finishRequested || length <= 0) return
            val frame = buildAudioChunkJson(pcm, length, commit = false)
            if (sessionReady) {
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
            if (sessionReady && !commitSent) {
                commitSent = true
                ws?.send(buildAudioChunkJson(ByteArray(0), 0, commit = true))
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
        private fun currentCommittedText(): String = committedSegments.joinToString(" ").trim()

        @Synchronized
        private fun currentFullText(): String {
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
            val ev = parseServerMessage(raw) ?: return
            var readyCb = false
            var updatePair: Pair<String, String>? = null
            var finalizedText: String? = null
            var errorPair: Pair<String, String>? = null

            synchronized(this) {
                if (terminated.get()) return
                when (ev) {
                    is ServerEvent.SessionStarted -> {
                        sessionReady = true
                        while (pendingChunks.isNotEmpty()) {
                            ws?.send(pendingChunks.removeFirst())
                        }
                        if (finishRequested && !commitSent) {
                            commitSent = true
                            ws?.send(buildAudioChunkJson(ByteArray(0), 0, commit = true))
                            scheduleFinishTimeout()
                        }
                        readyCb = true
                    }
                    is ServerEvent.Partial -> {
                        interimText = ev.text
                        updatePair = currentCommittedText() to interimText
                    }
                    is ServerEvent.Committed -> {
                        val clean = ev.text.trim()
                        if (clean.isNotEmpty()) committedSegments.add(clean)
                        interimText = ""
                        updatePair = currentCommittedText() to ""
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

            if (readyCb) listener.onReady()
            updatePair?.let { (c, i) -> listener.onTranscriptUpdate(c, i) }
            finalizedText?.let { listener.onFinalized(it) }
            errorPair?.let { (msg, partial) -> listener.onError(msg, partial) }
        }

        internal fun onFailure(reason: String) {
            if (!terminated.compareAndSet(false, true)) return
            val partial: String
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
            val partial: String
            synchronized(this) {
                finishTimer?.cancel(false)
                finishTimer = null
                partial = currentFullText()
                ws = null
                if (finishRequested) finishText = partial
                else errorMsg = reason.ifBlank { "Connection closed ($code)" }
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
        languageCode: String?,
        listener: GeminiLiveClient.StreamListener
    ): LiveStreamSession {
        val session = LiveSession(listener)
        val request = Request.Builder()
            .url(buildWsUrl(model, languageCode))
            .header("xi-api-key", apiKey)
            .build()
        val webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                session.onServerText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                session.onServerText(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val codePart = response?.code?.let { "HTTP $it " } ?: ""
                val msg = "$codePart${t.message ?: response?.message ?: "Network error"}".trim()
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
        callback: (GeminiLiveClient.RestResult) -> Unit
    ): Call {
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model_id", model.trim().ifEmpty { AppPrefs.DEFAULT_ELEVENLABS_RETRY_MODEL })
            .addFormDataPart("file", "audio.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))
        normalizeRestLanguage(languageCode)?.let {
            bodyBuilder.addFormDataPart("language_code", it)
        }

        val request = Request.Builder()
            .url(REST_ENDPOINT)
            .header("xi-api-key", apiKey)
            .post(bodyBuilder.build())
            .build()

        val call = restClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                callback(GeminiLiveClient.RestResult(null, e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful && raw.isBlank()) {
                    callback(GeminiLiveClient.RestResult(null, "HTTP ${response.code}"))
                    return
                }
                callback(parseRestResponse(raw))
            }
        })
        return call
    }
}
