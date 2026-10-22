package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient()

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    fun transcribe(wavData: ByteArray, target: CloudTarget, callback: (Result) -> Unit) {
        val request = when (target.provider) {
            CloudProvider.OPENAI -> openAiRequest(wavData, target)
            CloudProvider.OPENROUTER -> openRouterRequest(wavData, target)
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) =
                callback(parseResponse(response.body?.string() ?: ""))
        })
    }

    private fun openAiRequest(wavData: ByteArray, target: CloudTarget): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", target.model)
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            .build()

        return Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${target.apiKey}")
            .post(body)
            .build()
    }

    /** OpenRouter's STT endpoint is not OpenAI-compatible: it takes JSON with base64 audio, not multipart. */
    private fun openRouterRequest(wavData: ByteArray, target: CloudTarget): Request =
        Request.Builder()
            .url("${OpenRouter.BASE_URL}/audio/transcriptions")
            .header("Authorization", "Bearer ${target.apiKey}")
            .post(openRouterBody(wavData, target.model).toRequestBody("application/json".toMediaType()))
            .build()

    fun openRouterBody(wavData: ByteArray, model: String): String = JSONObject().apply {
        put("model", model)
        put("input_audio", JSONObject().apply {
            put("data", Base64.getEncoder().encodeToString(wavData))
            put("format", "wav")
        })
    }.toString()
}
