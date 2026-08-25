package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
    /** Override the download URL. If null, the default sherpa-onnx GitHub release URL is used. */
    val downloadUrl: String? = null,
    /** HuggingFace repo (e.g. "csukuangfj/sherpa-onnx-whisper-base") to fetch individual,
     *  uncompressed files from. If null, defaults to "csukuangfj/<archive>". */
    val hfRepo: String? = null,
)

val MODEL_CATALOG = listOf(
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Best value", recommended = true),
    Model("Whisper Base", "sherpa-onnx-whisper-base.en",
        199, "★★★"),
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Best quality"),
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Fast"),
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private const val TAG = "ModelDownloader"
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model) =
        modelDir(ctx, model).exists()

    /** Download and extract model. Callbacks fire on background thread.
     *
     *  Prefers fetching the individual (uncompressed) files directly from
     *  HuggingFace in parallel — this avoids the pure-Java bz2 decode, which on
     *  Android is the dominant cost, and skips the full-precision files the app
     *  never uses (only int8 variants are loaded). Falls back to the sherpa-onnx
     *  GitHub .tar.bz2 archive when the HF repo can't be listed or is incomplete.
     */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val url = model.downloadUrl ?: "$BASE_URL/${model.archive}.tar.bz2"
        val tmpFile = File(ctx.cacheDir, "${model.archive}.tar.bz2")
        val outDir = File(ctx.filesDir, "models")

        Thread {
            try {
                val repo = model.hfRepo ?: "csukuangfj/${model.archive}"
                val viaHf = try {
                    downloadFromHf(repo, model, outDir, onState)
                } catch (e: Exception) {
                    Log.w(TAG, "HF download failed for ${model.archive}: ${e.message}")
                    modelDir(ctx, model).deleteRecursively()
                    false
                }
                if (!viaHf) {
                    Log.i(TAG, "Falling back to .tar.bz2 for ${model.archive}")
                    downloadFile(url, tmpFile, onState)
                    onState(DownloadState.Extracting)
                    extractTarBz2(tmpFile, outDir)
                }
                onState(DownloadState.Done)
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                tmpFile.delete()
            }
        }.start()
    }

    // --- HuggingFace direct per-file download ---

    private data class HfFile(val name: String, val size: Long)

    /**
     * Download the model's needed files directly from HuggingFace, in parallel.
     * Returns false if the repo can't be listed or lacks the essential files,
     * so the caller can fall back to the archive. Throws on a mid-download error.
     */
    private fun downloadFromHf(
        repo: String, model: Model, outDir: File, onState: (DownloadState) -> Unit
    ): Boolean {
        val files = fetchNeededHfFiles(repo) ?: return false
        val modelDir = File(outDir, model.archive)
        modelDir.deleteRecursively()
        modelDir.mkdirs()

        val totalBytes = files.sumOf { it.size }.coerceAtLeast(1L)
        val done = AtomicLong(0)
        val lastPct = AtomicInteger(-1)
        fun report(delta: Long) {
            val d = done.addAndGet(delta)
            val pct = (d * 100 / totalBytes).toInt()
            // Throttle UI updates to whole-percent changes.
            if (pct != lastPct.getAndSet(pct)) {
                onState(DownloadState.Downloading(d.toFloat() / totalBytes))
            }
        }

        val pool = Executors.newFixedThreadPool(minOf(4, files.size))
        try {
            val futures = files.map { f ->
                pool.submit {
                    // Normalize the tokens filename so detectModelConfig finds it
                    // (some repos ship it size-prefixed, e.g. base-tokens.txt).
                    val base = f.name.substringAfterLast('/')
                    val outName = if (base.endsWith("tokens.txt")) "tokens.txt" else base
                    downloadOneFile(
                        "https://huggingface.co/$repo/resolve/main/${f.name}",
                        File(modelDir, outName),
                        f.size,
                        ::report,
                    )
                }
            }
            futures.forEach { it.get() } // rethrows the first failure
            Log.i(TAG, "Downloaded ${files.size} files from HF for ${model.archive}")
            return true
        } catch (e: Exception) {
            modelDir.deleteRecursively()
            throw IOException("HF file download failed: ${e.cause?.message ?: e.message}")
        } finally {
            pool.shutdown()
        }
    }

    /** List the files needed to run the model (int8 onnx + tokens), or null if the
     *  repo can't be listed or is missing essentials (→ archive fallback). */
    private fun fetchNeededHfFiles(repo: String): List<HfFile>? {
        val req = Request.Builder()
            .url("https://huggingface.co/api/models/$repo?blobs=true")
            .header("Accept", "application/json")
            .build()
        val all = mutableListOf<HfFile>()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val siblings = JSONObject(body).optJSONArray("siblings") ?: return null
            for (i in 0 until siblings.length()) {
                val o = siblings.getJSONObject(i)
                all.add(HfFile(o.optString("rfilename"), o.optLong("size", 0L)))
            }
        }
        return selectNeededFiles(all)
    }

    /** From all repo files, keep tokens.txt plus one onnx/ort per component,
     *  preferring the int8 variant. Skips full-precision duplicates, test_wavs,
     *  and docs. Returns null if essentials are missing. */
    private fun selectNeededFiles(all: List<HfFile>): List<HfFile>? {
        val flat = all.filter { !it.name.contains('/') } // drop test_wavs/ etc.
        val tokens = flat.filter { it.name.endsWith("tokens.txt") }
        val models = flat.filter { it.name.endsWith(".onnx") || it.name.endsWith(".ort") }
        if (tokens.isEmpty() || models.isEmpty()) return null

        fun componentKey(name: String) =
            name.removeSuffix(".onnx").removeSuffix(".ort").removeSuffix(".int8")

        val chosen = models.groupBy { componentKey(it.name) }.values.map { group ->
            group.firstOrNull { it.name.contains("int8") } ?: group.first()
        }
        return tokens + chosen
    }

    /** Download one file, verifying it against [expectedSize] to catch silent
     *  truncation (the CDN closing the connection early on large files reads as a
     *  clean EOF). A truncated onnx would later make onnxruntime abort natively
     *  ("No graph in protobuf"), which Kotlin can't catch — so we must never let a
     *  partial file land. Retries once, then throws (→ archive fallback). */
    private fun downloadOneFile(url: String, dest: File, expectedSize: Long, onDelta: (Long) -> Unit) {
        var lastErr: Exception? = null
        repeat(2) { attempt ->
            var written = 0L
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty response")
                    body.byteStream().use { src ->
                        BufferedOutputStream(FileOutputStream(dest)).use { dst ->
                            val buf = ByteArray(1 shl 16)
                            var n: Int
                            while (src.read(buf).also { n = it } != -1) {
                                dst.write(buf, 0, n)
                                written += n
                                onDelta(n.toLong())
                            }
                        }
                    }
                }
                if (expectedSize > 0 && dest.length() != expectedSize) {
                    throw IOException("truncated ${dest.name}: got ${dest.length()} of $expectedSize bytes")
                }
                return
            } catch (e: Exception) {
                lastErr = e
                onDelta(-written)   // roll back this attempt's progress
                dest.delete()
                Log.w(TAG, "Download attempt ${attempt + 1} for ${dest.name} failed: ${e.message}")
            }
        }
        throw lastErr ?: IOException("download failed: ${dest.name}")
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    private fun downloadFile(
        url: String, dest: File, onState: (DownloadState) -> Unit
    ) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty response")
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { src ->
            FileOutputStream(dest).use { dst ->
                val buf = ByteArray(16384)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    dst.write(buf, 0, n)
                    downloaded += n
                    if (total > 0)
                        onState(DownloadState.Downloading(downloaded.toFloat() / total))
                }
            }
        }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val dest = File(outDir, entry.name)
                require(dest.canonicalPath.startsWith(outDir.canonicalPath)) {
                    "Path traversal: ${entry.name}"
                }
                if (entry.isDirectory) dest.mkdirs()
                else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { tar.copyTo(it) }
                }
            }
        }
    }
}
