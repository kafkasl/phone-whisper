package com.kafkasl.phonewhisper

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object AppPrefs {
    const val PREFS_NAME = "phone_whisper_prefs"

    const val KEY_USE_LOCAL = "use_local"
    const val KEY_LOCAL_MODEL = "local_model"

    const val KEY_STT_PROVIDER = "stt_provider"
    const val PROVIDER_ELEVENLABS = "elevenlabs"
    const val PROVIDER_GEMINI = "gemini"

    const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
    const val KEY_ELEVENLABS_STREAM_MODEL = "elevenlabs_stream_model"
    const val KEY_ELEVENLABS_RETRY_MODEL = "elevenlabs_retry_model"

    const val KEY_GEMINI_API_KEY = "gemini_api_key"
    const val KEY_STREAMING_MODEL = "streaming_model"
    const val KEY_RETRY_MODEL = "retry_model"

    const val KEY_CLEANUP_BASE_URL = "cleanup_base_url"
    const val KEY_CLEANUP_MODEL = "cleanup_model"
    const val KEY_CLEANUP_API_KEY = "cleanup_api_key"
    const val KEY_CLEANUP_PROMPT = "cleanup_prompt"

    const val KEY_SHORTCUT_PRESETS = "shortcut_presets_v1"
    const val KEY_OVERLAY_EDGE_LEFT = "overlay_edge_left"
    const val KEY_OVERLAY_Y = "overlay_y"

    const val DEFAULT_ELEVENLABS_STREAM_MODEL = "scribe_v3_live"
    const val DEFAULT_ELEVENLABS_RETRY_MODEL = "scribe_v2"
    const val DEFAULT_STREAMING_MODEL = "gemini-3.5-transcribe-live"
    const val DEFAULT_RETRY_MODEL = "gemini-3.5-transcribe"
    const val DEFAULT_CLEANUP_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val DEFAULT_CLEANUP_MODEL = "gemini-3.5-flash-lite"

    fun sttProvider(prefs: SharedPreferences): String =
        prefs.getString(KEY_STT_PROVIDER, PROVIDER_ELEVENLABS)?.trim()
            ?.takeIf { it == PROVIDER_ELEVENLABS || it == PROVIDER_GEMINI } ?: PROVIDER_ELEVENLABS

    fun isElevenLabs(prefs: SharedPreferences): Boolean =
        sttProvider(prefs) == PROVIDER_ELEVENLABS

    fun elevenLabsApiKey(prefs: SharedPreferences): String =
        prefs.getString(KEY_ELEVENLABS_API_KEY, "")?.trim() ?: ""

    fun elevenLabsStreamModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_ELEVENLABS_STREAM_MODEL, DEFAULT_ELEVENLABS_STREAM_MODEL)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_ELEVENLABS_STREAM_MODEL

    fun elevenLabsRetryModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_ELEVENLABS_RETRY_MODEL, DEFAULT_ELEVENLABS_RETRY_MODEL)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_ELEVENLABS_RETRY_MODEL

    fun activeSttApiKey(prefs: SharedPreferences): String =
        if (isElevenLabs(prefs)) elevenLabsApiKey(prefs) else geminiApiKey(prefs)

    fun geminiApiKey(prefs: SharedPreferences): String =
        prefs.getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""

    fun streamingModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_STREAMING_MODEL, DEFAULT_STREAMING_MODEL)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_STREAMING_MODEL

    fun retryModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_RETRY_MODEL, DEFAULT_RETRY_MODEL)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_RETRY_MODEL

    fun cleanupBaseUrl(prefs: SharedPreferences): String =
        prefs.getString(KEY_CLEANUP_BASE_URL, DEFAULT_CLEANUP_BASE_URL)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLEANUP_BASE_URL

    fun cleanupModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_CLEANUP_MODEL, DEFAULT_CLEANUP_MODEL)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLEANUP_MODEL

    fun effectiveCleanupApiKey(prefs: SharedPreferences): String {
        val override = prefs.getString(KEY_CLEANUP_API_KEY, "")?.trim() ?: ""
        return override.ifEmpty { geminiApiKey(prefs) }
    }

    fun cleanupPrompt(prefs: SharedPreferences): String =
        prefs.getString(KEY_CLEANUP_PROMPT, PostProcessor.DEFAULT_PROMPT)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: PostProcessor.DEFAULT_PROMPT

    fun maskApiKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "Tap to set"
        if (trimmed.length <= 8) return "${trimmed.take(2)}...***"
        return "${trimmed.take(4)}...${trimmed.takeLast(4)}"
    }
}

data class ShortcutPreset(
    val id: String,
    val slotLabel: String,
    val badge: String,
    val name: String,
    val languageCode: String,
    val postProcess: Boolean,
    val screenContext: Boolean,
    val enabled: Boolean,
    val isDefault: Boolean = false
) {
    fun toBcp47(): String? = when (languageCode.lowercase()) {
        "en" -> "en-US"
        "es" -> "es-ES"
        "ca" -> "ca-ES"
        "auto", "" -> null
        else -> languageCode
    }

    fun languageDisplayName(): String = when (languageCode.lowercase()) {
        "en" -> "English"
        "es" -> "Spanish"
        "ca" -> "Catalan"
        "auto", "" -> "Auto-detect"
        else -> languageCode.uppercase()
    }

    fun summary(): String {
        val mode = if (postProcess) "Polish" else "Raw"
        val ctx = if (screenContext) "Screen context" else "No context"
        return "${languageDisplayName()} · $mode · $ctx"
    }
}

object ShortcutStore {
    val SUPPORTED_LANGUAGES = listOf(
        "en" to "English (en)",
        "es" to "Spanish (es)",
        "ca" to "Catalan (ca)",
        "auto" to "Auto-detect"
    )

    fun defaults(): List<ShortcutPreset> = listOf(
        ShortcutPreset(
            id = "default",
            slotLabel = "Default tap",
            badge = "EN",
            name = "English",
            languageCode = "en",
            postProcess = true,
            screenContext = true,
            enabled = true,
            isDefault = true
        ),
        ShortcutPreset(
            id = "slot1",
            slotLabel = "Long-press 1",
            badge = "ES",
            name = "Spanish",
            languageCode = "es",
            postProcess = true,
            screenContext = true,
            enabled = true
        ),
        ShortcutPreset(
            id = "slot2",
            slotLabel = "Long-press 2",
            badge = "CA",
            name = "Catalan",
            languageCode = "ca",
            postProcess = true,
            screenContext = true,
            enabled = true
        ),
        ShortcutPreset(
            id = "slot3",
            slotLabel = "Long-press 3",
            badge = "NOTE",
            name = "Quick note",
            languageCode = "en",
            postProcess = false,
            screenContext = false,
            enabled = true
        ),
        ShortcutPreset(
            id = "slot4",
            slotLabel = "Long-press 4",
            badge = "RAW",
            name = "Spanish raw",
            languageCode = "es",
            postProcess = false,
            screenContext = false,
            enabled = false
        )
    )

    fun toJson(presets: List<ShortcutPreset>): String {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("slotLabel", p.slotLabel)
                put("badge", p.badge)
                put("name", p.name)
                put("languageCode", p.languageCode)
                put("postProcess", p.postProcess)
                put("screenContext", p.screenContext)
                put("enabled", if (p.isDefault) true else p.enabled)
                put("isDefault", p.isDefault)
            })
        }
        return arr.toString()
    }

    fun fromJson(json: String?): List<ShortcutPreset> {
        val fallback = defaults()
        if (json.isNullOrBlank()) return fallback
        return try {
            val arr = JSONArray(json)
            val byId = mutableMapOf<String, ShortcutPreset>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                val def = fallback.firstOrNull { it.id == id } ?: continue
                val badge = o.optString("badge", def.badge).trim().uppercase().take(4).ifEmpty { def.badge }
                val name = o.optString("name", def.name).trim().ifEmpty { def.name }
                val lang = o.optString("languageCode", def.languageCode).trim().lowercase().ifEmpty { def.languageCode }
                val isDef = def.isDefault
                byId[id] = ShortcutPreset(
                    id = id,
                    slotLabel = def.slotLabel,
                    badge = badge,
                    name = name,
                    languageCode = lang,
                    postProcess = o.optBoolean("postProcess", def.postProcess),
                    screenContext = o.optBoolean("screenContext", def.screenContext),
                    enabled = if (isDef) true else o.optBoolean("enabled", def.enabled),
                    isDefault = isDef
                )
            }
            fallback.map { byId[it.id] ?: it }
        } catch (_: Exception) {
            fallback
        }
    }

    fun load(prefs: SharedPreferences): List<ShortcutPreset> =
        fromJson(prefs.getString(AppPrefs.KEY_SHORTCUT_PRESETS, null))

    fun save(prefs: SharedPreferences, presets: List<ShortcutPreset>) {
        prefs.edit().putString(AppPrefs.KEY_SHORTCUT_PRESETS, toJson(presets)).apply()
    }

    fun defaultPreset(prefs: SharedPreferences): ShortcutPreset =
        load(prefs).first { it.isDefault }

    fun activeLongPressPresets(prefs: SharedPreferences): List<ShortcutPreset> =
        load(prefs).filter { !it.isDefault && it.enabled }

    fun findById(prefs: SharedPreferences, id: String): ShortcutPreset =
        load(prefs).firstOrNull { it.id == id } ?: defaultPreset(prefs)
}
