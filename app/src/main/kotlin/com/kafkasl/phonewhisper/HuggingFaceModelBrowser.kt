package com.kafkasl.phonewhisper

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Discovers sherpa-onnx offline ASR models from the HuggingFace Hub.
 *
 * Unlike the old version (Whisper-only), this lists every sherpa-onnx model from
 * the publisher, classifies each by [ModelArch] from its name, and keeps only the
 * app-loadable offline ASR ones that actually have usable files. The Hub list API
 * doesn't return file sizes, so size is fetched lazily per-model via [fetchSizeMb].
 */
object HuggingFaceModelBrowser {

    private const val TAG = "HFModelBrowser"
    private const val AUTHOR = "csukuangfj"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** ISO codes we recognise inside repo names for language inference. */
    private val LANG_CODES = setOf(
        "en", "zh", "de", "es", "fr", "it", "ru", "ja", "ko", "yue", "vi", "nl",
        "pt", "uk", "pl", "hr", "be", "ar", "hi", "fa", "tr", "id", "th",
    )

    data class HFModel(
        val repoId: String,        // "csukuangfj/sherpa-onnx-whisper-base"
        val archive: String,       // repo name after the slash (== model dir name)
        val displayName: String,
        val arch: ModelArch,
        val languages: String,     // human string, e.g. "en, de, es, fr" / "multilingual"
        val downloads: Int,
        val sizeMb: Int,           // 0 = not yet known (fetched lazily)
    )

    sealed class BrowseResult {
        data class Success(val models: List<HFModel>) : BrowseResult()
        data class Error(val message: String) : BrowseResult()
    }

    /** Fetch the full catalog in one request. Blocking — run off the main thread.
     *  Sizes are left at 0; call [fetchSizeMb] lazily to fill them in. */
    fun fetchModels(): BrowseResult {
        return try {
            val url = "https://huggingface.co/api/models" +
                "?author=$AUTHOR&search=sherpa-onnx&limit=1000&full=true"
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return BrowseResult.Error("HTTP ${response.code}")
                val body = response.body?.string() ?: return BrowseResult.Error("Empty response")
                BrowseResult.Success(parseModels(JSONArray(body)))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}")
            BrowseResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            BrowseResult.Error("Parse error: ${e.message}")
        }
    }

    private fun parseModels(array: JSONArray): List<HFModel> {
        val result = mutableListOf<HFModel>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val repoId = obj.optString("id")
            if (repoId.isBlank() || !repoId.startsWith("$AUTHOR/")) continue
            val name = repoId.substringAfter("/")
            val arch = ModelArch.fromRepoName(name)
            if (!arch.isSupported) continue
            if (!hasUsableFiles(obj.optJSONArray("siblings"))) continue // skip empty placeholders

            result.add(
                HFModel(
                    repoId = repoId,
                    archive = name,
                    displayName = name.removePrefix("sherpa-onnx-"),
                    arch = arch,
                    languages = inferLanguages(name, arch),
                    downloads = obj.optInt("downloads", 0),
                    sizeMb = 0,
                )
            )
        }
        // Default order: most-downloaded first, then name.
        return result.sortedWith(compareByDescending<HFModel> { it.downloads }.thenBy { it.displayName })
    }

    /** True if the repo has at least one top-level onnx/ort model plus a tokens file. */
    private fun hasUsableFiles(siblings: JSONArray?): Boolean {
        if (siblings == null) return false
        var hasModel = false
        var hasTokens = false
        for (i in 0 until siblings.length()) {
            val n = siblings.getJSONObject(i).optString("rfilename")
            if (n.contains('/')) continue
            if (n.endsWith(".onnx") || n.endsWith(".ort")) hasModel = true
            if (n.endsWith("tokens.txt")) hasTokens = true
        }
        return hasModel && hasTokens
    }

    private fun inferLanguages(name: String, arch: ModelArch): String {
        val n = name.lowercase()
        when (arch) {
            ModelArch.WHISPER -> return if (".en" in n) "English" else "multilingual"
            ModelArch.SENSE_VOICE -> return "zh, en, ja, ko, yue"
            ModelArch.DOLPHIN -> return "multilingual"
            ModelArch.OMNILINGUAL -> return "1600+ languages"
            else -> {}
        }
        val found = n.split('-', '_', '.').filter { it in LANG_CODES }.distinct()
        return if (found.isNotEmpty()) found.joinToString(", ") else ""
    }

    /** Fetch the actual download size (needed int8 files + tokens) for one repo.
     *  Blocking — run off the main thread. Returns 0 if unknown. Mirrors
     *  ModelDownloader's file-selection so the number matches what gets downloaded. */
    fun fetchSizeMb(repoId: String): Int {
        return try {
            val request = Request.Builder()
                .url("https://huggingface.co/api/models/$repoId?blobs=true")
                .header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0
                val body = response.body?.string() ?: return 0
                val siblings = JSONObject(body).optJSONArray("siblings") ?: return 0
                data class F(val name: String, val size: Long)
                val flat = ArrayList<F>()
                for (i in 0 until siblings.length()) {
                    val o = siblings.getJSONObject(i)
                    val n = o.optString("rfilename")
                    if (!n.contains('/')) flat.add(F(n, o.optLong("size", 0L)))
                }
                val tokens = flat.filter { it.name.endsWith("tokens.txt") }
                val onnx = flat.filter { it.name.endsWith(".onnx") || it.name.endsWith(".ort") }
                if (onnx.isEmpty()) return 0
                fun key(n: String) = n.removeSuffix(".onnx").removeSuffix(".ort").removeSuffix(".int8")
                val chosen = onnx.groupBy { key(it.name) }.values.map { g ->
                    g.firstOrNull { "int8" in it.name } ?: g.first()
                }
                ((tokens + chosen).sumOf { it.size } / (1024 * 1024)).toInt()
            }
        } catch (e: Exception) {
            Log.w(TAG, "size fetch failed for $repoId: ${e.message}")
            0
        }
    }

    /** Convert an HFModel into a Model the downloader/loader understand. */
    fun toModel(hf: HFModel): Model = Model(
        name = hf.displayName,
        archive = hf.archive,
        sizeMb = hf.sizeMb,
        quality = hf.languages.ifBlank { hf.arch.label },
        recommended = false,
        hfRepo = hf.repoId,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${hf.archive}.tar.bz2",
        arch = hf.arch,
    )
}
