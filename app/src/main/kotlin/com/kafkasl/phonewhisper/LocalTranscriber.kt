package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Thrown by [LocalTranscriber.create] when a model can't be loaded, carrying a
 *  user-presentable reason. */
class ModelLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's external files dir.
 */
class LocalTranscriber private constructor(private val recognizer: OfflineRecognizer) {

    /** Transcribe raw PCM float samples. Blocking — call from background thread. */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, sampleRate)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Marker file written into a model dir recording its [ModelArch] id. */
        const val ARCH_MARKER = ".arch"

        /** The persisted architecture for a model dir, or UNKNOWN if absent. */
        fun archOf(ctx: Context, modelName: String): ModelArch =
            readArch(File(ctx.filesDir, "models/$modelName"))

        /** True if [dir] has all the files needed to build a usable config for
         *  [arch] (or any auto-detected type). Used to validate legacy downloads
         *  during marker migration. */
        fun hasValidFiles(dir: File, arch: ModelArch): Boolean =
            (if (arch.isSupported) buildConfig(dir, arch, "") else null) != null ||
                detectModelConfig(dir, "") != null

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        /**
         * Create a LocalTranscriber for the given model directory name.
         * Throws [ModelLoadException] with a human-readable reason on failure so
         * callers can surface it to the user instead of failing silently.
         *
         * [language] is an ISO code ("fr", "en", …) or "" for auto-detect; it is
         * applied to language-aware architectures (Whisper, SenseVoice, Canary).
         *
         * The architecture is read from the [ARCH_MARKER] file written at download
         * time; if absent (legacy download), it falls back to file-shape detection.
         *
         * Note: a native crash (SIGABRT from onnxruntime on a corrupt/unsupported
         * model) inside [OfflineRecognizer] kills the process and cannot be caught
         * here — that case is handled by the crash-guard marker in the service.
         */
        fun create(ctx: Context, modelName: String, language: String = ""): LocalTranscriber {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                throw ModelLoadException("Model folder not found: $modelName")
            }

            val arch = readArch(modelDir)
            val config = (if (arch.isSupported) buildConfig(modelDir, arch, language) else null)
                ?: detectModelConfig(modelDir, language)
                ?: throw ModelLoadException(
                    "Unrecognized model files in $modelName " +
                        "(missing tokens.txt or encoder/decoder/model onnx files)"
                )
            Log.i(TAG, "Loading '$modelName' as ${arch.id} (language='$language')")

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer)
            } catch (e: UnsatisfiedLinkError) {
                // The native sherpa-onnx runtime (libsherpa-onnx-jni.so) isn't
                // bundled in the APK. This is a build/packaging problem, not a
                // per-model one — it fails identically for every model.
                Log.e(TAG, "Native sherpa-onnx library missing: ${e.message}", e)
                throw ModelLoadException(
                    "Speech engine not installed in this build (native library missing)", e
                )
            } catch (e: Throwable) {
                // Catch Throwable, not just Exception: onnxruntime can surface
                // failures as Errors (e.g. LinkageError) that would otherwise
                // crash the service instead of degrading to API/no-op.
                Log.e(TAG, "Failed to load model: ${e.message}", e)
                throw ModelLoadException("sherpa-onnx failed to load $modelName: ${e.message}", e)
            }
        }

        /** Read the persisted architecture marker, or UNKNOWN if absent. */
        private fun readArch(dir: File): ModelArch {
            val f = File(dir, ARCH_MARKER)
            return if (f.exists()) ModelArch.fromId(f.readText().trim()) else ModelArch.UNKNOWN
        }

        /** Tokens are usually tokens.txt, but some models ship it size-prefixed
         *  (e.g. base-tokens.txt), so accept any *tokens.txt as a fallback. */
        private fun findTokens(p: String): String? =
            File("$p/tokens.txt").takeIf { it.exists() }?.absolutePath
                ?: File(p).listFiles()?.firstOrNull { it.name.endsWith("tokens.txt") }?.absolutePath

        /**
         * Build the recognizer config for an explicitly-known [arch]. Returns null
         * if the expected model files are missing (caller falls back to detection).
         */
        private fun buildConfig(dir: File, arch: ModelArch, language: String): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = findTokens(p) ?: return null
            fun onnx(vararg keys: String): String? {
                for (k in keys) findFile(p, k)?.let { return it }
                return null
            }
            val m = OfflineModelConfig(tokens = tokens, numThreads = 2)
            when (arch) {
                ModelArch.WHISPER -> {
                    m.whisper = OfflineWhisperModelConfig(
                        encoder = onnx("encoder") ?: return null,
                        decoder = onnx("decoder") ?: return null,
                        language = language, // "" => auto-detect
                        task = "transcribe",
                    )
                    m.modelType = "whisper"
                }
                ModelArch.CANARY -> {
                    val lang = language.ifBlank { "en" } // Canary can't auto-detect
                    m.canary = OfflineCanaryModelConfig(
                        encoder = onnx("encoder") ?: return null,
                        decoder = onnx("decoder") ?: return null,
                        srcLang = lang, tgtLang = lang, usePnc = true,
                    )
                }
                ModelArch.FIRE_RED_ASR -> m.fireRedAsr = OfflineFireRedAsrModelConfig(
                    encoder = onnx("encoder") ?: return null,
                    decoder = onnx("decoder") ?: return null,
                )
                ModelArch.TRANSDUCER -> m.transducer = OfflineTransducerModelConfig(
                    encoder = onnx("encoder") ?: return null,
                    decoder = onnx("decoder") ?: return null,
                    joiner = onnx("joiner") ?: return null,
                )
                ModelArch.MOONSHINE -> {
                    val preprocess = onnx("preprocess")
                    m.moonshine = if (preprocess != null) OfflineMoonshineModelConfig(
                        preprocessor = preprocess,
                        encoder = onnx("encode") ?: return null,
                        uncachedDecoder = onnx("uncached_decode") ?: return null,
                        cachedDecoder = onnx("cached_decode") ?: return null,
                    ) else OfflineMoonshineModelConfig( // v2
                        encoder = onnx("encoder", "encode") ?: return null,
                        mergedDecoder = onnx("merged") ?: return null,
                    )
                }
                ModelArch.NEMO_CTC ->
                    m.nemo = OfflineNemoEncDecCtcModelConfig(model = onnx("model") ?: return null)
                ModelArch.SENSE_VOICE ->
                    m.senseVoice = OfflineSenseVoiceModelConfig(model = onnx("model") ?: return null, language = language)
                ModelArch.PARAFORMER ->
                    m.paraformer = OfflineParaformerModelConfig(model = onnx("model") ?: return null)
                ModelArch.ZIPFORMER_CTC ->
                    m.zipformerCtc = OfflineZipformerCtcModelConfig(model = onnx("model") ?: return null)
                ModelArch.WENET_CTC ->
                    m.wenetCtc = OfflineWenetCtcModelConfig(model = onnx("model") ?: return null)
                ModelArch.DOLPHIN ->
                    m.dolphin = OfflineDolphinModelConfig(model = onnx("model") ?: return null)
                ModelArch.OMNILINGUAL ->
                    m.omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = onnx("model") ?: return null)
                ModelArch.MEDASR ->
                    m.medasr = OfflineMedAsrCtcModelConfig(model = onnx("model") ?: return null)
                ModelArch.TELESPEECH ->
                    m.teleSpeech = onnx("model") ?: return null
                ModelArch.UNKNOWN -> return null
            }
            return OfflineRecognizerConfig(modelConfig = m)
        }

        /** Fallback: auto-detect model type from files present in the directory
         *  (used for legacy downloads that predate the [ARCH_MARKER]). */
        private fun detectModelConfig(dir: File, language: String = ""): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = findTokens(p) ?: return null

            // Moonshine (has preprocess.onnx)
            if (File("$p/preprocess.onnx").exists()) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = "$p/preprocess.onnx",
                            encoder = findFile(p, "encode") ?: return null,
                            uncachedDecoder = findFile(p, "uncached_decode") ?: return null,
                            cachedDecoder = findFile(p, "cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner)
            val whisperEncoder = findFile(p, "encoder")
            val whisperDecoder = findFile(p, "decoder")
            if (whisperEncoder != null && whisperDecoder != null && findFile(p, "joiner") == null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = whisperEncoder,
                            decoder = whisperDecoder,
                            // Empty language => sherpa-onnx auto-detects the spoken
                            // language and transcribes it. The vendored default is
                            // "en", which forces English output (French in => English
                            // out, i.e. an unwanted translation). task defaults to
                            // "transcribe"; "translate" would render everything as English.
                            language = language,
                            task = "transcribe",
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT (has encoder + decoder + joiner)
            val encoder = findFile(p, "encoder")
            val decoder = findFile(p, "decoder")
            val joiner = findFile(p, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // NeMo CTC (single model.onnx / model.int8.onnx)
            val ctcModel = findFile(p, "model")
            if (ctcModel != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            return null
        }

        /** Find first file matching a name component (prefer int8 quantized).
         *  Matches the token anywhere in the filename so size-prefixed Whisper
         *  files like "tiny-encoder.int8.onnx" are found for prefix "encoder". */
        private fun findFile(dir: String, prefix: String): String? {
            val d = File(dir)
            fun matches(name: String) =
                name.startsWith(prefix) || name.contains("-$prefix") || name.contains("_$prefix")
            // Prefer int8 quantized
            d.listFiles()?.firstOrNull {
                matches(it.name) && it.name.contains("int8") &&
                    (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.let { return it.absolutePath }
            // Fallback to any onnx/ort
            return d.listFiles()?.firstOrNull {
                matches(it.name) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.absolutePath
        }
    }
}
