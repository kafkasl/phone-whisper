package com.kafkasl.phonewhisper

import android.os.SystemClock
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object TranscriberClient {
    data class Result(
        val text: String?,
        val error: String?,
        val statusCode: Int? = null,
        val elapsedMs: Long? = null
    )

    private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    private const val TRANSCRIPTIONS_PATH = "/audio/transcriptions"
    private const val TAG = "PhoneWhisper"

    private val client = OkHttpClient.Builder()
        .eventListenerFactory { call ->
            TimingEventListener(call.request().tag(String::class.java) ?: "unknown")
        }
        .build()

    fun transcriptionUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        return when {
            base.endsWith(TRANSCRIPTIONS_PATH) -> base
            base.endsWith("/v1") -> "$base$TRANSCRIPTIONS_PATH"
            else -> "$base/v1$TRANSCRIPTIONS_PATH"
        }
    }

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> {
                val error = obj.get("error")
                val message = if (error is JSONObject) {
                    error.optString("message", error.toString())
                } else {
                    error.toString()
                }
                Result(null, message)
            }
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        baseUrl: String = "",
        requestId: String = "unknown",
        callback: (Result) -> Unit
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        log(requestId, "client_build_start", "wavBytes=${wavData.size} hasToken=${apiKey.isNotBlank()} baseUrl=${baseUrl.ifBlank { DEFAULT_BASE_URL }}")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            .build()

        val requestBuilder = try {
            Request.Builder()
                .url(transcriptionUrl(baseUrl))
                .tag(String::class.java, requestId)
                .post(body)
        } catch (e: IllegalArgumentException) {
            log(requestId, "client_invalid_url", e.message ?: "invalid")
            callback(Result(null, "Invalid transcription URL: ${e.message}"))
            return
        }

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()
        log(requestId, "client_enqueue", "url=${request.url}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                log(requestId, "client_failure", "elapsedMs=$elapsedMs error=${e.message}")
                callback(Result(null, e.message, elapsedMs = elapsedMs))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    val raw = it.body?.string() ?: ""
                    log(requestId, "client_response_body", "elapsedMs=$elapsedMs status=${it.code} bytes=${raw.length}")
                    val parsed = parseResponse(raw)
                    callback(parsed.copy(statusCode = it.code, elapsedMs = elapsedMs))
                }
            }
        })
    }

    private fun log(requestId: String, stage: String, message: String = "") {
        Log.i(TAG, "trace=$requestId stage=$stage $message")
    }

    private class TimingEventListener(private val requestId: String) : EventListener() {
        private val startedAt = SystemClock.elapsedRealtime()

        override fun dnsStart(call: Call, domainName: String) {
            log("dns_start", "elapsedMs=${elapsed()} host=$domainName")
        }

        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            log("connect_start", "elapsedMs=${elapsed()} address=${inetSocketAddress.hostString}:${inetSocketAddress.port}")
        }

        override fun secureConnectStart(call: Call) {
            log("tls_start", "elapsedMs=${elapsed()}")
        }

        override fun requestHeadersStart(call: Call) {
            log("request_headers_start", "elapsedMs=${elapsed()}")
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            log("request_body_end", "elapsedMs=${elapsed()} bytes=$byteCount")
        }

        override fun responseHeadersStart(call: Call) {
            log("response_headers_start", "elapsedMs=${elapsed()}")
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            log("response_body_end", "elapsedMs=${elapsed()} bytes=$byteCount")
        }

        override fun callFailed(call: Call, ioe: IOException) {
            log("call_failed", "elapsedMs=${elapsed()} error=${ioe.message}")
        }

        private fun elapsed() = SystemClock.elapsedRealtime() - startedAt
        private fun log(stage: String, message: String = "") {
            Log.i(TAG, "trace=$requestId stage=http_$stage $message")
        }
    }
}
