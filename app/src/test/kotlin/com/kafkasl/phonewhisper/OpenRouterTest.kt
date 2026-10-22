package com.kafkasl.phonewhisper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class OpenRouterTest {

    @Test fun `filters models by output modality and sorts them`() {
        val json = """
        {"data": [
            {"id": "openai/whisper-1", "architecture": {"output_modalities": ["transcription"]}},
            {"id": "openai/gpt-4o-mini", "architecture": {"output_modalities": ["text"]}},
            {"id": "google/chirp-3", "architecture": {"output_modalities": ["transcription"]}}
        ]}
        """
        assertEquals(listOf("google/chirp-3", "openai/whisper-1"), OpenRouter.parseModels(json, "transcription"))
        assertEquals(listOf("openai/gpt-4o-mini"), OpenRouter.parseModels(json, "text"))
    }

    @Test fun `keeps models without architecture info`() {
        val json = """{"data": [{"id": "b/model"}, {"id": "a/model"}]}"""
        assertEquals(listOf("a/model", "b/model"), OpenRouter.parseModels(json, "text"))
    }

    @Test fun `transcription body carries model and raw base64 wav`() {
        val wav = byteArrayOf(1, 2, 3, 4, 5)
        val body = JSONObject(TranscriberClient.openRouterBody(wav, "openai/whisper-large-v3"))

        assertEquals("openai/whisper-large-v3", body.getString("model"))
        val audio = body.getJSONObject("input_audio")
        assertEquals("wav", audio.getString("format"))
        assertArrayEquals(wav, Base64.getDecoder().decode(audio.getString("data")))
    }

    @Test fun `transcription response parses with the shared parser`() {
        val r = TranscriberClient.parseResponse(
            """{"text": "Hallo Welt", "usage": {"seconds": 2, "cost": 0.0001}}"""
        )
        assertEquals("Hallo Welt", r.text)
        assertNull(r.error)
    }
}
