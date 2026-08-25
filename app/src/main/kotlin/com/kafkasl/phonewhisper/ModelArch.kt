package com.kafkasl.phonewhisper

/** How a model's transcription language is determined. */
enum class LanguageMode {
    /** Model auto-detects the spoken language (empty language string). */
    AUTO_DETECT,
    /** Model must be told the language explicitly (no auto-detect). */
    NEEDS_LANGUAGE,
    /** Language setting is irrelevant (single-language or fixed by the model). */
    NONE,
}

/**
 * Offline ASR architectures supported by the app.
 *
 * The architecture cannot be reliably inferred from a model's files — several
 * types ship a single `model.onnx` (SenseVoice, Dolphin, Paraformer, ...) and
 * others share the encoder+decoder shape (Whisper vs Canary vs FireRedAsr). So
 * we carry it explicitly, derived from the model's repo/archive name, and
 * persist it alongside the downloaded model (see ModelDownloader `.arch` marker).
 */
enum class ModelArch(
    val id: String,
    val label: String,
    val languageMode: LanguageMode,
    val help: String,
) {
    WHISPER("whisper", "Whisper", LanguageMode.AUTO_DETECT,
        "OpenAI Whisper. Very accurate, 99+ languages, auto-detects the spoken language. Heavier and slower; larger sizes need a lot of RAM."),
    MOONSHINE("moonshine", "Moonshine", LanguageMode.NONE,
        "Fast English short-form model. Very low latency, English only."),
    TRANSDUCER("transducer", "Transducer (RNN-T)", LanguageMode.NONE,
        "Zipformer / NeMo / Parakeet transducers. Fast and accurate, usually single-language (often English)."),
    NEMO_CTC("nemo_ctc", "NeMo CTC", LanguageMode.NONE,
        "NVIDIA NeMo CTC, including multilingual conformers. Fast; some variants cover several languages (e.g. en/de/es/fr)."),
    SENSE_VOICE("sense_voice", "SenseVoice", LanguageMode.AUTO_DETECT,
        "Multilingual (zh/en/ja/ko/yue). Fast, auto-detects among its supported languages."),
    PARAFORMER("paraformer", "Paraformer", LanguageMode.NONE,
        "Alibaba Paraformer. Strong for Chinese and bilingual zh-en. Fast, non-autoregressive."),
    ZIPFORMER_CTC("zipformer_ctc", "Zipformer CTC", LanguageMode.NONE,
        "Zipformer CTC. Fast and compact; typically single-language."),
    WENET_CTC("wenet_ctc", "WeNet CTC", LanguageMode.NONE,
        "WeNet CTC models, mostly Chinese/English."),
    DOLPHIN("dolphin", "Dolphin", LanguageMode.NONE,
        "Dolphin CTC. Multilingual across many East/South Asian languages."),
    TELESPEECH("telespeech", "TeleSpeech", LanguageMode.NONE,
        "TeleSpeech CTC. Chinese, incl. dialects."),
    FIRE_RED_ASR("fire_red_asr", "FireRedASR", LanguageMode.NONE,
        "FireRedASR. Strong Mandarin/English."),
    CANARY("canary", "Canary", LanguageMode.NEEDS_LANGUAGE,
        "NVIDIA Canary. Compact and accurate, multilingual en/de/es/fr — but the language must be set explicitly (no auto-detect)."),
    OMNILINGUAL("omnilingual", "Omnilingual ASR", LanguageMode.NONE,
        "Meta Omnilingual ASR, 1600+ languages. Large — heavy on a phone."),
    MEDASR("medasr", "MedASR", LanguageMode.NONE,
        "Medical-domain English ASR."),
    UNKNOWN("unknown", "Unknown", LanguageMode.NONE,
        "Unsupported or unrecognized model type.");

    val isSupported: Boolean get() = this != UNKNOWN

    companion object {
        fun fromId(id: String?): ModelArch = values().firstOrNull { it.id == id } ?: UNKNOWN

        /**
         * Infer the architecture from a sherpa-onnx repo/archive name.
         * Unsupported (non-ASR, streaming, FunAsr-Nano) → [UNKNOWN].
         */
        fun fromRepoName(nameRaw: String): ModelArch {
            val n = nameRaw.lowercase()
            val unsupported = listOf(
                "streaming", "online", "funasr-nano", "-tts", "vits", "kokoro", "matcha",
                "-vad", "kws", "keyword", "punct", "speaker", "diariz", "spleeter", "hifigan",
                "pyannote", "reverb", "-apk", "-bin", "-libs", "cmake", "flutter", "harmony",
                "rknn-models", "audio-tagging", "spoken-language", "source-models",
            )
            if (unsupported.any { it in n }) return UNKNOWN
            return when {
                "whisper" in n -> WHISPER
                "moonshine" in n -> MOONSHINE
                "sense-voice" in n || "sensevoice" in n -> SENSE_VOICE
                "canary" in n -> CANARY
                "dolphin" in n -> DOLPHIN
                "telespeech" in n -> TELESPEECH
                "omnilingual" in n -> OMNILINGUAL
                "medasr" in n -> MEDASR
                "fire-red" in n || "fireredasr" in n -> FIRE_RED_ASR
                "paraformer" in n -> PARAFORMER
                // Parakeet: "..._tdt_ctc_..." is CTC (single model); plain "-tdt-" is a transducer.
                "parakeet" in n -> if ("tdt_ctc" in n || "tdt-ctc" in n) NEMO_CTC else TRANSDUCER
                "zipformer-ctc" in n -> ZIPFORMER_CTC
                "wenet" in n -> WENET_CTC
                "nemo-ctc" in n || "fast-conformer-ctc" in n || "conformer-ctc" in n -> NEMO_CTC
                "zipformer" in n -> TRANSDUCER
                "conformer" in n -> TRANSDUCER
                "lstm" in n -> TRANSDUCER
                else -> UNKNOWN
            }
        }
    }
}
