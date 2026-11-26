package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

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

        /** Find available model dirs under the app's files/models/ dir, ordered by catalog priority. */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            val dirs = modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: return emptyList()
            val catalogOrder = MODEL_CATALOG.map { it.archive }
            val ordered = catalogOrder.filter { it in dirs }
            val extra = dirs.filterNot { it in catalogOrder }
            return ordered + extra
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            val config = detectModelConfig(modelDir) ?: run {
                Log.e(TAG, "Could not detect model type in $modelDir")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}")
                null
            }
        }

        /** Auto-detect model type from files present in the directory. */
        internal fun detectModelConfig(dir: File): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = dir.listFiles()?.firstOrNull { it.name.endsWith("tokens.txt") }?.absolutePath
                ?: return null
            val threads = 4

            // Moonshine v2 (encoder + merged_decoder, no preprocess.onnx)
            val mergedDecoder = findFile(p, "merged_decoder")
            if (mergedDecoder != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            encoder = findFile(p, "encoder") ?: return null,
                            mergedDecoder = mergedDecoder,
                        ),
                        tokens = tokens,
                        numThreads = threads,
                    )
                )
            }

            // Moonshine v1 (has preprocess.onnx)
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
                        numThreads = threads,
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
                        numThreads = threads,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner; supports multilingual auto-detect via language="")
            if (encoder != null && decoder != null) {
                val isEnglishOnly = dir.name.contains(".en")
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            language = if (isEnglishOnly) "en" else "",
                            task = "transcribe",
                        ),
                        tokens = tokens,
                        numThreads = threads,
                        modelType = "whisper",
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
                        numThreads = threads,
                    )
                )
            }

            return null
        }

        /** Find model file matching keyword (prefer int8 quantized; handles prefixes like turbo-encoder.int8.onnx). */
        internal fun findFile(dir: String, keyword: String): String? {
            val files = File(dir).listFiles()?.filter {
                (it.name.endsWith(".onnx") || it.name.endsWith(".ort")) &&
                    it.name.contains(keyword, ignoreCase = true)
            } ?: return null
            return files.firstOrNull { it.name.contains("int8", ignoreCase = true) }?.absolutePath
                ?: files.firstOrNull()?.absolutePath
        }
    }
}
