package com.kafkasl.phonewhisper

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A single file of a model that is published as loose files instead of a tar.bz2 archive. */
data class ModelFile(val name: String, val size: Long, val sha256: String)

/**
 * [archive] is the install dir name under files/models/. Models without [files] are fetched as
 * `<archive>.tar.bz2` from the sherpa-onnx release; models with [files] download each file from
 * [baseUrl] and verify its SHA-256.
 */
data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
    val baseUrl: String? = null,
    val files: List<ModelFile> = emptyList(),
)

val MODEL_CATALOG = listOf(
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Best value", recommended = true),
    Model("Whisper Base", "sherpa-onnx-whisper-base.en",
        199, "★★★"),
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Best quality"),
    Model("Parakeet Ultra", "sherpa-onnx-parakeet-ultra-int8",
        600, "★★★★★ Most accurate",
        baseUrl = "https://huggingface.co/mldecode/parakeet-ultra-onnx-int8/resolve/3282a6e32885b431c1543d58c7710e6e3412eac0",
        files = listOf(
            ModelFile("encoder.int8.onnx", 611_879_344, "181382735a719c75076d13658dc4418de4b566aef39935ca0f8f55da16928f4e"),
            ModelFile("decoder.int8.onnx", 12_257_685, "0ba8ace2de04bb2d9a6b20ed2d67138c23df4a385f268438df0ff200502de77a"),
            ModelFile("joiner.int8.onnx", 5_281_747, "20ae4350c2484ba607d94f08ef25ae3ead762d8aaf70753758ffcc504e255ebb"),
            ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        )),
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
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model) =
        modelDir(ctx, model).exists()

    /** Download and extract model. Callbacks fire on background thread. */
    fun download(ctx: Context, model: Model, onUpdate: (DownloadState) -> Unit) {
        // Only report download progress when the whole percentage changes, not per 16 KB chunk.
        var lastPercent = -1
        val onState: (DownloadState) -> Unit = { state ->
            val percent = (state as? DownloadState.Downloading)?.let { (it.progress * 100).toInt() }
            if (percent == null || percent != lastPercent) {
                percent?.let { lastPercent = it }
                onUpdate(state)
            }
        }
        Thread {
            try {
                if (model.files.isEmpty()) downloadArchive(ctx, model, onState)
                else downloadFiles(ctx, model, onState)
                onState(DownloadState.Done)
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            }
        }.start()
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    private fun downloadArchive(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val url = "$BASE_URL/${model.archive}.tar.bz2"
        val tmpFile = File(ctx.cacheDir, "${model.archive}.tar.bz2")
        try {
            downloadFile(url, tmpFile) { done, total ->
                if (total > 0) onState(DownloadState.Downloading(done.toFloat() / total))
            }
            onState(DownloadState.Extracting)
            extractTarBz2(tmpFile, File(ctx.filesDir, "models"))
        } finally {
            tmpFile.delete()
        }
    }

    /** Download loose files into a staging dir (outside models/) and move it into place once all hashes match. */
    private fun downloadFiles(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val baseUrl = requireNotNull(model.baseUrl) { "No download URL for ${model.name}" }
        val staging = File(ctx.filesDir, "model-downloads/${model.archive}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val total = model.files.sumOf { it.size }
            var finished = 0L
            for (file in model.files) {
                val sha = downloadFile("$baseUrl/${file.name}", File(staging, file.name)) { done, _ ->
                    onState(DownloadState.Downloading((finished + done).toFloat() / total))
                }
                if (!sha.equals(file.sha256, ignoreCase = true))
                    throw IOException("Checksum mismatch for ${file.name}")
                finished += file.size
            }
            val target = modelDir(ctx, model)
            target.parentFile?.mkdirs()
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Could not move model into place")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Download [url] to [dest], reporting (bytes so far, content length). Returns the SHA-256 hex of the body. */
    private fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            return body.byteStream().use { src ->
                FileOutputStream(dest).use { dst -> copyWithSha256(src, dst) { onProgress(it, total) } }
            }
        }
    }

    /** Copy [src] to [dst], reporting bytes copied so far. Returns the lowercase SHA-256 hex of the data. */
    fun copyWithSha256(src: InputStream, dst: OutputStream, onProgress: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16384)
        var copied = 0L
        var n: Int
        while (src.read(buf).also { n = it } != -1) {
            dst.write(buf, 0, n)
            digest.update(buf, 0, n)
            copied += n
            onProgress(copied)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
