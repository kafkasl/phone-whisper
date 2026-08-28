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
         * Note: a native crash (SIGABRT from onnxruntime on a corrupt/unsupported
         * model) inside [OfflineRecognizer] kills the process and cannot be caught
         * here — that case is handled by the crash-guard marker in the service.
         */
        fun create(ctx: Context, modelName: String): LocalTranscriber {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                throw ModelLoadException("Model folder not found: $modelName")
            }

            val config = detectModelConfig(modelDir)
                ?: throw ModelLoadException(
                    "Unrecognized model files in $modelName " +
                        "(missing tokens.txt or encoder/decoder/model onnx files)"
                )

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

        /** Auto-detect model type from files present in the directory. */
        private fun detectModelConfig(dir: File): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            // Tokens are usually tokens.txt, but some archives ship it size-prefixed
            // (e.g. base-tokens.txt), so accept any *tokens.txt as a fallback.
            val tokens = File("$p/tokens.txt").takeIf { it.exists() }?.absolutePath
                ?: File(p).listFiles()?.firstOrNull { it.name.endsWith("tokens.txt") }?.absolutePath
                ?: return null

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
                            language = "",
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
