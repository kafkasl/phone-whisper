package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ScreenContextAndPresetsTest {

    @Test fun `formatUserMessage wraps non-empty context fields in XML`() {
        val ctx = ScreenContext(
            appLabel = "WhatsApp",
            screenText = "Kamil: Did nbdev fix FastHTML?",
            textBeforeCursor = "Hey Kamil, ",
            recentDictations = listOf("Testing on rubicon")
        )
        val msg = ScreenContextCapture.formatUserMessage("yeah it works", ctx, "en")
        assertTrue(msg.contains("<app>WhatsApp</app>"))
        assertTrue(msg.contains("<screen_context>"))
        assertTrue(msg.contains("<text_before_cursor>Hey Kamil, </text_before_cursor>"))
        assertTrue(msg.contains("<language>English</language>"))
        assertTrue(msg.contains("<raw_dictation>\nyeah it works\n</raw_dictation>"))
    }

    @Test fun `validateAndCleanOutput strips echoed cursor prefix and rejects runaway answers`() {
        val deduped = ScreenContextCapture.validateAndCleanOutput(
            cleaned = "Hey Kamil, yes it works on Hetzner.",
            rawDictation = "yes it works on hetzner",
            textBeforeCursor = "Hey Kamil, "
        )
        assertEquals("yes it works on Hetzner.", deduped)

        val runaway = ScreenContextCapture.validateAndCleanOutput(
            cleaned = "A".repeat(400),
            rawDictation = "Where is Paris?"
        )
        assertNull(runaway)
    }

    @Test fun `ShortcutStore round-trips JSON and handles malformed input`() {
        val edited = ShortcutStore.defaults().map {
            if (it.id == "slot4") it.copy(badge = "fr", name = "French", languageCode = "fr", enabled = true)
            else it
        }
        val restored = ShortcutStore.fromJson(ShortcutStore.toJson(edited))
        val slot4 = restored.first { it.id == "slot4" }
        assertEquals("FR", slot4.badge)
        assertTrue(slot4.enabled)
        assertEquals("fr", slot4.languageCode)

        val fallback = ShortcutStore.fromJson("not json")
        assertEquals(5, fallback.size)
        assertTrue(fallback.first { it.isDefault }.enabled)
    }

    @Test fun `PcmWavFileRecorder writes valid WAV header and RecordingStore evicts oldest`() {
        val dir = Files.createTempDirectory("pw_rec_test").toFile()
        try {
            val wav = RecordingStore.createWavFile(dir, "clip1")
            val rec = PcmWavFileRecorder(wav)
            rec.write(ByteArray(3200))
            assertEquals(3200L, rec.finish())
            val bytes = wav.readBytes()
            assertEquals(3244, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))

            for (i in 1..12) {
                val id = "c$i"
                val f = RecordingStore.createWavFile(dir, id).apply { writeBytes(ByteArray(64)) }
                RecordingStore.upsertClip(dir, SavedClip(id, f.absolutePath, i.toLong(), 3, "default", "EN", "en", "", null, RecordingStore.STATUS_FAILED))
            }
            val listed = RecordingStore.listClips(dir)
            assertEquals(10, listed.size)
            assertFalse(RecordingStore.createWavFile(dir, "c1").exists())
            assertTrue(RecordingStore.createWavFile(dir, "c12").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
