package com.kafkasl.phonewhisper

import android.content.SharedPreferences

enum class CloudProvider(val label: String, val keyPref: String) {
    OPENAI("OpenAI", "api_key"),
    OPENROUTER("OpenRouter", "openrouter_api_key");

    val prefValue get() = name.lowercase()

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.prefValue == value } ?: OPENAI
    }
}

/** Provider, credentials and model for one kind of cloud request, resolved from prefs. */
data class CloudTarget(val provider: CloudProvider, val apiKey: String, val model: String)

const val DEFAULT_OPENROUTER_TRANSCRIPTION_MODEL = "openai/whisper-large-v3"
const val DEFAULT_OPENROUTER_POST_PROCESSING_MODEL = "openai/gpt-4o-mini"

val OPENROUTER_TRANSCRIPTION_PRESETS = listOf(
    "openai/whisper-large-v3",
    "openai/whisper-1",
    "google/chirp-3",
    "elevenlabs/scribe-v2",
)

val OPENROUTER_POST_PROCESSING_PRESETS = listOf(
    "openai/gpt-4o-mini",
)

fun SharedPreferences.transcriptionTarget(): CloudTarget {
    val provider = CloudProvider.from(getString("transcription_provider", null))
    val model = when (provider) {
        CloudProvider.OPENAI -> "whisper-1"
        CloudProvider.OPENROUTER -> getString("openrouter_transcription_model", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_OPENROUTER_TRANSCRIPTION_MODEL
    }
    return CloudTarget(provider, getString(provider.keyPref, "") ?: "", model)
}

fun SharedPreferences.postProcessingTarget(): CloudTarget {
    val provider = CloudProvider.from(getString("post_processing_provider", null))
    val model = when (provider) {
        CloudProvider.OPENAI -> "gpt-4o-mini"
        CloudProvider.OPENROUTER -> getString("openrouter_post_processing_model", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_OPENROUTER_POST_PROCESSING_MODEL
    }
    return CloudTarget(provider, getString(provider.keyPref, "") ?: "", model)
}
