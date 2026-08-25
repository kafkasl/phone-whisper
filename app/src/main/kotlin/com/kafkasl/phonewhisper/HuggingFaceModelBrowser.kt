package com.kafkasl.phonewhisper

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches multilingual sherpa-onnx Whisper models from the HuggingFace Hub API.
 *
 * Only models under [MAX_SIZE_MB] are included — anything larger is not practical
 * on a phone (slow to load, high RAM usage).
 *
 * The sherpa-onnx naming convention is:
 *   sherpa-onnx-whisper-{size}          ← multilingual
 *   sherpa-onnx-whisper-{size}.en       ← English-only
 *
 * We want the multilingual ones (no ".en" suffix).
 */
object HuggingFaceModelBrowser {

    private const val TAG = "HFModelBrowser"

    // Max model size we'll show. "medium" is ~780 MB which is borderline;
    // "large" variants are 1.5 GB+ and are unusable on most phones.
    private const val MAX_SIZE_MB = 600

    // HuggingFace API: list all repos from the sherpa-onnx organisation
    // that contain whisper models, sorted by downloads.
    private const val HF_API_URL =
        "https://huggingface.co/api/models" +
        "?author=csukuangfj" +
        "&search=sherpa-onnx-whisper" +
        "&sort=downloads" +
        "&direction=-1" +
        "&limit=50"

    // Known approximate sizes in MB for each Whisper variant.
    // Used when HF doesn't return a size (some repos omit it).
    private val KNOWN_SIZES = mapOf(
        "tiny"   to 75,
        "base"   to 142,
        "small"  to 466,
        "medium" to 764,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class HFModel(
        val repoId: String,       // e.g. "csukuangfj/sherpa-onnx-whisper-base"
        val displayName: String,  // e.g. "Whisper Base (multilingual)"
        val sizeMb: Int,
        val languages: String,    // "multilingual" or "English only"
        val isMultilingual: Boolean,
        val archive: String,      // archive name used by ModelDownloader
    )

    sealed class BrowseResult {
        data class Success(val models: List<HFModel>) : BrowseResult()
        data class Error(val message: String) : BrowseResult()
    }

    /** Fetch and return sherpa-onnx Whisper models. Blocking — run off main thread.
     *  @param showLargeModels if true, models above [MAX_SIZE_MB] are included too. */
    fun fetchModels(showLargeModels: Boolean = false): BrowseResult {
        return try {
            val request = Request.Builder().url(HF_API_URL)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return BrowseResult.Error("HTTP ${response.code}")
            }

            val body = response.body?.string() ?: return BrowseResult.Error("Empty response")
            val models = parseModels(JSONArray(body), showLargeModels)
            BrowseResult.Success(models)
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}")
            BrowseResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            BrowseResult.Error("Parse error: ${e.message}")
        }
    }

    private fun parseModels(array: JSONArray, showLargeModels: Boolean): List<HFModel> {
        val result = mutableListOf<HFModel>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val repoId = obj.optString("id") ?: continue

            // Only sherpa-onnx-whisper-* repos
            val repoName = repoId.substringAfter("/")
            if (!repoName.startsWith("sherpa-onnx-whisper-")) continue

            // Derive the archive name (same as repo name)
            val archive = repoName

            // Detect variant: "tiny", "base", "small", "medium"
            val variant = KNOWN_SIZES.keys.firstOrNull { repoName.contains(it) } ?: continue

            // Is this multilingual (no ".en" suffix)?
            val isMultilingual = !repoName.endsWith(".en")

            // Estimate size
            val sizeMb = estimateSize(obj, variant)

            // Filter out models too large for phones (unless user opted in)
            if (!showLargeModels && sizeMb > MAX_SIZE_MB) continue

            val languages = if (isMultilingual) "multilingual · 99+ languages" else "English only"
            val variantLabel = variant.replaceFirstChar { it.uppercase() }
            val langLabel = if (isMultilingual) "multilingual" else "English"
            val displayName = "Whisper $variantLabel ($langLabel)"

            result.add(
                HFModel(
                    repoId = repoId,
                    displayName = displayName,
                    sizeMb = sizeMb,
                    languages = languages,
                    isMultilingual = isMultilingual,
                    archive = archive,
                )
            )
        }

        // Sort: multilingual first, then by size ascending
        return result.sortedWith(compareByDescending<HFModel> { it.isMultilingual }.thenBy { it.sizeMb })
    }

    private fun estimateSize(obj: JSONObject, variant: String): Int {
        // Try to sum up sibling files sizes from the HF API response
        val siblings = obj.optJSONArray("siblings")
        if (siblings != null) {
            var totalBytes = 0L
            for (j in 0 until siblings.length()) {
                val file = siblings.getJSONObject(j)
                totalBytes += file.optLong("size", 0L)
            }
            if (totalBytes > 0) return (totalBytes / (1024 * 1024)).toInt()
        }
        // Fall back to known approximate sizes
        return KNOWN_SIZES[variant] ?: 200
    }

    /** Convert an HFModel into a Model so it works with the existing ModelDownloader. */
    fun toModel(hf: HFModel): Model = Model(
        name = hf.displayName,
        archive = hf.archive,
        sizeMb = hf.sizeMb,
        quality = hf.languages,
        recommended = false,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${hf.archive}.tar.bz2",
    )
}
