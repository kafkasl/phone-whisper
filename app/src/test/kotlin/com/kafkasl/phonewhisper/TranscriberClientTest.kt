package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `parses string error response`() {
        val r = TranscriberClient.parseResponse("""{"error":"Unauthorized"}""")
        assertNull(r.text)
        assertEquals("Unauthorized", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `uses official OpenAI transcription URL by default`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            TranscriberClient.transcriptionUrl("")
        )
    }

    @Test fun `builds transcription URL from OpenAI compatible base URL`() {
        assertEquals(
            "http://100.68.233.33:6022/v1/audio/transcriptions",
            TranscriberClient.transcriptionUrl("http://100.68.233.33:6022/v1")
        )
    }

    @Test fun `keeps explicit transcription URL`() {
        assertEquals(
            "http://100.68.233.33:6022/v1/audio/transcriptions",
            TranscriberClient.transcriptionUrl("http://100.68.233.33:6022/v1/audio/transcriptions")
        )
    }
}
