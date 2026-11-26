package com.kafkasl.phonewhisper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiLiveClientTest {

    @Test fun `buildSetupJson includes languageCodes when specified and omits when null`() {
        val es = JSONObject(GeminiLiveClient.buildSetupJson("gemini-3.5-transcribe-live", "es-ES"))
        val setup = es.getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        val langs = setup.getJSONObject("inputAudioTranscription").getJSONArray("languageCodes")
        assertEquals(1, langs.length())
        assertEquals("es-ES", langs.getString(0))

        val auto = JSONObject(GeminiLiveClient.buildSetupJson("models/gemini-3.5-transcribe-live", null))
        assertFalse(auto.getJSONObject("setup").getJSONObject("inputAudioTranscription").has("languageCodes"))
    }

    @Test fun `parseServerMessage handles setup, interim, final, and generationComplete`() {
        assertEquals(
            listOf(GeminiLiveClient.ServerEvent.SetupComplete),
            GeminiLiveClient.parseServerMessage("""{"setupComplete": {}}""")
        )
        assertEquals(
            listOf(GeminiLiveClient.ServerEvent.Interim("Hola mundo")),
            GeminiLiveClient.parseServerMessage("""{"serverContent":{"interimInputTranscription":{"text":"Hola mundo"}}}""")
        )
        val combined = """{"serverContent":{"inputTranscription":{"text":"Final text."},"generationComplete":true}}"""
        assertEquals(
            listOf(
                GeminiLiveClient.ServerEvent.FinalSegment("Final text."),
                GeminiLiveClient.ServerEvent.GenerationComplete
            ),
            GeminiLiveClient.parseServerMessage(combined)
        )
    }

    @Test fun `parseRestResponse extracts candidate text and API error`() {
        val ok = """{"candidates":[{"content":{"parts":[{"text":"Hola Kamil"}]}}]}"""
        assertEquals(GeminiLiveClient.RestResult("Hola Kamil", null), GeminiLiveClient.parseRestResponse(ok))

        val err = """{"error":{"code":400,"message":"Invalid key"}}"""
        val r = GeminiLiveClient.parseRestResponse(err)
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }
}
