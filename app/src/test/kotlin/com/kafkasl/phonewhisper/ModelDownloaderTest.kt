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
        assertTrue(MODEL_CATALOG.size >= 4)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.all { it.archive.startsWith("sherpa-onnx-") })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
        assertEquals(MODEL_CATALOG.size, MODEL_CATALOG.map { it.archive }.distinct().size)
    }

    @Test fun `loose-file models have a url and valid checksums`() {
        for (model in MODEL_CATALOG.filter { it.files.isNotEmpty() }) {
            assertNotNull(model.name, model.baseUrl)
            assertTrue(model.name, model.files.any { it.name == "tokens.txt" })
            for (f in model.files) {
                assertTrue(f.name, f.size > 0)
                assertTrue(f.name, Regex("[0-9a-f]{64}").matches(f.sha256))
            }
        }
    }

    @Test fun `copyWithSha256 copies data and returns its hash`() {
        val out = java.io.ByteArrayOutputStream()
        var progress = 0L
        val sha = ModelDownloader.copyWithSha256("abc".byteInputStream(), out) { progress = it }

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha)
        assertEquals("abc", out.toString())
        assertEquals(3L, progress)
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
