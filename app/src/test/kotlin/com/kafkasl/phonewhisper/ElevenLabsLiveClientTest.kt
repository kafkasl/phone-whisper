package com.kafkasl.phonewhisper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ElevenLabsLiveClientTest {

    @Test
    fun buildWsUrlIncludesRequiredParamsAndLanguage() {
        val url = ElevenLabsLiveClient.buildWsUrl("scribe_v3_live", "ca")
        assertTrue(url.startsWith("wss://api.elevenlabs.io/v1/speech-to-text/realtime?"))
        assertTrue(url.contains("model_id=scribe_v3_live"))
        assertTrue(url.contains("audio_format=pcm_16000"))
        assertTrue(url.contains("commit_strategy=manual"))
        assertTrue(url.contains("no_verbatim=true"))
        assertTrue(url.contains("language_code=ca"))
    }

    @Test
    fun buildWsUrlOmitsLanguageForAuto() {
        val url = ElevenLabsLiveClient.buildWsUrl("scribe_v3_live", "auto")
        assertFalse(url.contains("language_code="))
    }

    @Test
    fun buildAudioChunkUsesAudioBase64Key() {
        val json = ElevenLabsLiveClient.buildAudioChunkJson(byteArrayOf(1, 2, 3, 4), 4, commit = false)
        val obj = JSONObject(json)
        assertEquals("input_audio_chunk", obj.getString("message_type"))
        assertTrue(obj.has("audio_base_64"))
        assertEquals(16000, obj.getInt("sample_rate"))
        assertFalse(obj.getBoolean("commit"))
    }

    @Test
    fun parseServerMessages() {
        assertEquals(
            ElevenLabsLiveClient.ServerEvent.SessionStarted,
            ElevenLabsLiveClient.parseServerMessage("""{"message_type":"session_started","session_id":"abc"}""")
        )
        assertEquals(
            ElevenLabsLiveClient.ServerEvent.Partial("Massa petita"),
            ElevenLabsLiveClient.parseServerMessage("""{"message_type":"partial_transcript","text":"Massa petita"}""")
        )
        assertEquals(
            ElevenLabsLiveClient.ServerEvent.Committed("Massa petita per torturar-la així."),
            ElevenLabsLiveClient.parseServerMessage("""{"message_type":"committed_transcript","text":"Massa petita per torturar-la així."}""")
        )
        val err = ElevenLabsLiveClient.parseServerMessage("""{"message_type":"input_error","error":"Bad chunk"}""")
        assertEquals(ElevenLabsLiveClient.ServerEvent.Error("Bad chunk"), err)

        val quota = ElevenLabsLiveClient.parseServerMessage("""{"message_type":"quota_exceeded","message":"Not enough credits"}""")
        assertTrue(quota is ElevenLabsLiveClient.ServerEvent.Error)
        assertTrue(ElevenLabsLiveClient.isQuotaError((quota as ElevenLabsLiveClient.ServerEvent.Error).message))
    }

    @Test
    fun parseSubscriptionAndQuotaHelper() {
        val sub = ElevenLabsLiveClient.parseSubscriptionResponse("""{"character_count":9500,"character_limit":10000}""")
        assertNotNull(sub)
        assertEquals(500, sub!!.remaining)
        assertTrue(sub.isLow)

        assertTrue(ElevenLabsLiveClient.isQuotaError("HTTP 402 Payment Required"))
        assertFalse(ElevenLabsLiveClient.isQuotaError("Socket timeout"))
    }

    @Test
    fun parseRestResponseSuccessAndError() {
        val ok = ElevenLabsLiveClient.parseRestResponse("""{"text":"Massa petita per torturar-la així."}""")
        assertEquals("Massa petita per torturar-la així.", ok.text)
        assertNull(ok.error)

        val err = ElevenLabsLiveClient.parseRestResponse("""{"detail":{"status":"invalid_api_key","message":"Invalid key"}}""")
        assertNull(err.text)
        assertEquals("Invalid key", err.error)
    }
}
