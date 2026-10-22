package com.kafkasl.phonewhisper

import okhttp3.*
import org.json.JSONObject
import java.io.IOException

object OpenRouter {
    const val BASE_URL = "https://openrouter.ai/api/v1"

    private val client = OkHttpClient()

    /**
     * Model IDs whose output modalities include [outputModality] ("transcription" or "text"), sorted.
     * Entries without architecture info are kept so a schema change doesn't empty the list.
     */
    fun parseModels(json: String, outputModality: String): List<String> {
        val data = JSONObject(json).getJSONArray("data")
        return (0 until data.length()).mapNotNull { i ->
            val model = data.getJSONObject(i)
            val modalities = model.optJSONObject("architecture")?.optJSONArray("output_modalities")
            val matches = modalities == null ||
                (0 until modalities.length()).any { modalities.optString(it) == outputModality }
            model.optString("id").takeIf { matches && it.isNotBlank() }
        }.distinct().sorted()
    }

    /** Fetch available models. The endpoint is public, no API key needed. Callback fires on a background thread. */
    fun listModels(outputModality: String, callback: (List<String>?, String?) -> Unit) {
        val url = "$BASE_URL/models?output_modalities=$outputModality"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(null, e.message ?: "Network error")
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) { callback(null, "HTTP ${response.code}"); return }
                try {
                    callback(parseModels(body, outputModality), null)
                } catch (e: Exception) {
                    callback(null, e.message ?: "Parse error")
                }
            }
        })
    }
}
