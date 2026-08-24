package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMessagesTest {
    @Test fun `maps unauthorized transcription errors`() {
        assertEquals(
            "Wrong transcription token",
            ErrorMessages.transcription("Unauthorized", 401)
        )
    }

    @Test fun `maps network failures`() {
        assertEquals(
            "Network error",
            ErrorMessages.transcription("Unable to resolve host whisper.example", null)
        )
    }

    @Test fun `maps server errors`() {
        assertEquals(
            "Transcription server error",
            ErrorMessages.transcription("bad gateway", 502)
        )
    }

    @Test fun `maps empty transcript`() {
        assertEquals(
            "No transcript returned",
            ErrorMessages.transcription("empty transcript", 200)
        )
    }
}
