package com.kafkasl.phonewhisper

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ModelDownloaderTest {

    @Test fun `extracts tar bz2 with nested files`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            val outDir = File(tmp, "out")

            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "hello\nworld",
                "mymodel/encoder.onnx" to "fake-onnx-data",
            ))

            ModelDownloader.extractTarBz2(archive, outDir)

            assertTrue(File(outDir, "mymodel").isDirectory)
            assertEquals("hello\nworld", File(outDir, "mymodel/tokens.txt").readText())
            assertEquals("fake-onnx-data", File(outDir, "mymodel/encoder.onnx").readText())
        }
    }

    @Test fun `rejects path traversal`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `catalog has expected structure`() {
        assertEquals(6, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.all { it.archive.startsWith("sherpa-onnx-") })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
        // Every catalog model must declare a supported architecture so the loader
        // builds the right config instead of guessing.
        assertTrue(MODEL_CATALOG.all { it.arch.isSupported })
    }

    @Test fun `arch inference maps known repo names`() {
        assertEquals(ModelArch.WHISPER, ModelArch.fromRepoName("sherpa-onnx-whisper-small"))
        assertEquals(ModelArch.CANARY, ModelArch.fromRepoName("sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"))
        assertEquals(ModelArch.NEMO_CTC, ModelArch.fromRepoName("sherpa-onnx-nemo-fast-conformer-ctc-en-de-es-fr-14288"))
        assertEquals(ModelArch.NEMO_CTC, ModelArch.fromRepoName("sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"))
        assertEquals(ModelArch.TRANSDUCER, ModelArch.fromRepoName("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"))
        assertEquals(ModelArch.SENSE_VOICE, ModelArch.fromRepoName("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"))
        assertEquals(ModelArch.UNKNOWN, ModelArch.fromRepoName("sherpa-onnx-streaming-zipformer-en"))
        assertEquals(ModelArch.UNKNOWN, ModelArch.fromRepoName("sherpa-onnx-vits-piper-en"))
    }

    // -- helpers --

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun writeTarBz2(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(file))).use { tar ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
}
