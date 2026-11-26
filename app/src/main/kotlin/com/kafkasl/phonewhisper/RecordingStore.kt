package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

data class SavedClip(
    val id: String,
    val wavPath: String,
    val createdAtMs: Long,
    val durationSec: Int,
    val presetId: String,
    val presetBadge: String,
    val languageCode: String,
    val partialText: String,
    val finalText: String?,
    val status: String,
    val flagged: Boolean = false,
    val liveDraftText: String = ""
) {
    val isFailed: Boolean get() = status == RecordingStore.STATUS_FAILED
}

/** Streams 16 kHz mono 16-bit PCM directly to a WAV file on disk without holding audio in RAM. */
class PcmWavFileRecorder(
    val wavFile: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private val out: FileOutputStream
    private var pcmBytesWritten: Long = 0L
    private var closed = false

    init {
        wavFile.parentFile?.mkdirs()
        out = FileOutputStream(wavFile)
        out.write(ByteArray(44)) // placeholder WAV header
    }

    @Synchronized
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (closed || length <= 0) return
        out.write(data, offset, length)
        pcmBytesWritten += length
    }

    @Synchronized
    fun durationSeconds(): Int {
        val bytesPerSec = sampleRate * channels * bitsPerSample / 8
        if (bytesPerSec <= 0) return 0
        return (pcmBytesWritten / bytesPerSec).toInt()
    }

    @Synchronized
    fun finish(): Long {
        if (closed) return pcmBytesWritten
        closed = true
        try {
            out.flush()
            out.close()
        } catch (_: Exception) {}

        val dataSize = pcmBytesWritten.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val header = buildHeader(dataSize)
        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
        return pcmBytesWritten
    }

    @Synchronized
    fun abortAndDelete() {
        if (!closed) {
            closed = true
            try { out.close() } catch (_: Exception) {}
        }
        try { wavFile.delete() } catch (_: Exception) {}
    }

    private fun buildHeader(dataSize: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) = s.forEachIndexed { i, c -> header[off + i] = c.code.toByte() }
        fun putInt(off: Int, v: Int) { for (i in 0..3) header[off + i] = (v shr (8 * i) and 0xFF).toByte() }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = (v shr 8 and 0xFF).toByte()
        }

        putStr(0, "RIFF")
        putInt(4, 36 + dataSize)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, bitsPerSample)
        putStr(36, "data")
        putInt(40, dataSize)
        return header
    }
}

object RecordingStore {
    const val STATUS_FAILED = "failed"
    const val STATUS_COMPLETED = "completed"
    private const val DIR_NAME = "recordings"
    private const val INDEX_FILE = "index.json"
    private const val MAX_CLIPS = 10

    private val lock = Any()

    fun newClipId(): String = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

    fun recordingsDir(baseDir: File): File = File(baseDir, DIR_NAME).apply { mkdirs() }

    fun createWavFile(baseDir: File, clipId: String): File =
        File(recordingsDir(baseDir), "$clipId.wav")

    fun createWavFile(context: Context, clipId: String): File =
        createWavFile(context.filesDir, clipId)

    fun clipsToJson(clips: List<SavedClip>): String {
        val arr = JSONArray()
        for (c in clips) {
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("wavPath", c.wavPath)
                put("createdAtMs", c.createdAtMs)
                put("durationSec", c.durationSec)
                put("presetId", c.presetId)
                put("presetBadge", c.presetBadge)
                put("languageCode", c.languageCode)
                put("partialText", c.partialText)
                if (c.finalText != null) put("finalText", c.finalText)
                put("status", c.status)
                put("flagged", c.flagged)
                if (c.liveDraftText.isNotEmpty()) put("liveDraftText", c.liveDraftText)
            })
        }
        return arr.toString()
    }

    fun clipsFromJson(json: String?): List<SavedClip> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<SavedClip>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(SavedClip(
                    id = o.getString("id"),
                    wavPath = o.getString("wavPath"),
                    createdAtMs = o.optLong("createdAtMs", 0L),
                    durationSec = o.optInt("durationSec", 0),
                    presetId = o.optString("presetId", "default"),
                    presetBadge = o.optString("presetBadge", "EN"),
                    languageCode = o.optString("languageCode", "en"),
                    partialText = o.optString("partialText", ""),
                    finalText = if (o.has("finalText") && !o.isNull("finalText")) o.getString("finalText") else null,
                    status = o.optString("status", STATUS_FAILED),
                    flagged = o.optBoolean("flagged", false),
                    liveDraftText = o.optString("liveDraftText", "")
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun listClips(baseDir: File): List<SavedClip> = synchronized(lock) {
        val idx = File(recordingsDir(baseDir), INDEX_FILE)
        if (!idx.exists()) return emptyList()
        clipsFromJson(idx.readText())
    }

    fun listClips(context: Context): List<SavedClip> = listClips(context.filesDir)

    fun upsertClip(baseDir: File, clip: SavedClip) = synchronized(lock) {
        val dir = recordingsDir(baseDir)
        val existing = listClips(baseDir).filterNot { it.id == clip.id }
        val updated = listOf(clip) + existing
        val pinned = updated.filter { it.flagged }
        val unflagged = updated.filterNot { it.flagged }
        val kept = pinned + unflagged.take(MAX_CLIPS)
        val evicted = unflagged.drop(MAX_CLIPS)
        for (old in evicted) {
            try { File(old.wavPath).delete() } catch (_: Exception) {}
        }
        File(dir, INDEX_FILE).writeText(clipsToJson(kept.sortedByDescending { it.createdAtMs }))
    }

    fun upsertClip(context: Context, clip: SavedClip) = upsertClip(context.filesDir, clip)

    fun setFlagged(baseDir: File, clipId: String, flagged: Boolean): Boolean = synchronized(lock) {
        val dir = recordingsDir(baseDir)
        val all = listClips(baseDir)
        var found = false
        val updated = all.map {
            if (it.id == clipId) {
                found = true
                it.copy(flagged = flagged)
            } else it
        }
        if (found) File(dir, INDEX_FILE).writeText(clipsToJson(updated))
        found
    }

    fun setFlagged(context: Context, clipId: String, flagged: Boolean): Boolean =
        setFlagged(context.filesDir, clipId, flagged)

    fun deleteClip(baseDir: File, clipId: String) = synchronized(lock) {
        val dir = recordingsDir(baseDir)
        val all = listClips(baseDir)
        val target = all.firstOrNull { it.id == clipId }
        if (target != null) {
            try { File(target.wavPath).delete() } catch (_: Exception) {}
        }
        val remaining = all.filterNot { it.id == clipId }
        File(dir, INDEX_FILE).writeText(clipsToJson(remaining))
    }

    fun deleteClip(context: Context, clipId: String) = deleteClip(context.filesDir, clipId)

    fun failedCount(baseDir: File): Int = listClips(baseDir).count { it.isFailed }

    fun failedCount(context: Context): Int = failedCount(context.filesDir)
}
