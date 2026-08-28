package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    private const val TRANSCRIPTIONS_PATH = "/audio/transcriptions"

    private val client = OkHttpClient()

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
        callback: (Result) -> Unit
    ) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            .build()

        val requestBuilder = try {
            Request.Builder()
                .url(transcriptionUrl(baseUrl))
                .post(body)
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid transcription URL: ${e.message}"))
            return
        }

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) =
                callback(parseResponse(response.body?.string() ?: ""))
        })
    }
}
