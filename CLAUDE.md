# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Phone Whisper is a single-module Android app (Kotlin, package `com.kafkasl.phonewhisper`, minSdk 30, targetSdk 34, JDK 17, arm64-v8a only). It shows a floating push-to-talk overlay button, records audio, transcribes it locally (sherpa-onnx) or in the cloud (OpenAI or OpenRouter), optionally cleans up the text with a chat model (OpenAI or OpenRouter), and inserts the result into the focused text field of whatever app is in the foreground.

## Commands

```bash
make jni-libs      # download sherpa-onnx native libs (pinned version + SHA-256) into app/src/main/jniLibs/arm64-v8a/
make build         # fetches jni-libs if missing, then ./gradlew assembleDebug → app/build/outputs/apk/debug/app-debug.apk
make test          # ./gradlew testDebugUnitTest
make adb-install   # build + adb install -r
make install       # build + scp the APK to a phone running Termux over SSH (PHONE_HOST/SSH_PORT from .env)
make push-model MODEL=/path/to/model-dir   # copy a local sherpa-onnx model into the app's files/models/ via adb run-as
make clean
```

Run a single test class or method:

```bash
./gradlew testDebugUnitTest --tests 'com.kafkasl.phonewhisper.PostProcessorTest'
./gradlew testDebugUnitTest --tests 'com.kafkasl.phonewhisper.TranscriberClientTest.parses success response'
```

The Makefile hard-codes macOS/Homebrew paths for `JAVA_HOME` and `ANDROID_HOME`. On other machines, call `./gradlew` directly (with a valid `local.properties`/`ANDROID_HOME`) instead of going through `make`.

There is no lint or format configuration. Unit tests are plain JVM JUnit 4 tests (no Robolectric or instrumentation). `org.json` is added as a test dependency so the `parseResponse` functions work off-device.

## Architecture

There are only two Android components (see `AndroidManifest.xml`). They share state through `SharedPreferences("phonewhisper")`.

- **`MainActivity`** is the settings/setup screen. The whole UI is built programmatically in Kotlin; there are no layout XML files. It handles permissions, the accessibility-service link, the local/cloud toggle, provider/model choice, the API keys, model download/select/delete, and post-processing prompt presets. It writes prefs and calls `WhisperAccessibilityService.instance?.reloadModel()` when the selected model changes.
- **`WhisperAccessibilityService`** is where the app actually runs. On `onServiceConnected` it loads the local model and adds a `TYPE_ACCESSIBILITY_OVERLAY` button. A small state machine (`IDLE → RECORDING → TRANSCRIBING`) drives the pipeline:
  1. `AudioRecord` captures 16 kHz mono PCM16 into a `ByteArrayOutputStream` on a background thread.
  2. Local mode (`use_local` pref, and a model is loaded): PCM is converted to floats and passed to `LocalTranscriber`. Cloud mode, or local mode with no model loaded: `WavWriter.encode` wraps the PCM, and `TranscriberClient` sends it to the provider from `transcriptionTarget()`. OpenAI gets multipart `whisper-1`. OpenRouter gets JSON with base64 audio under `input_audio`; its STT endpoint is **not** OpenAI-compatible.
  3. If `use_post_processing` is set: `PostProcessor` sends the text to the chat-completions endpoint of the provider from `postProcessingTarget()` (temperature 0) with the selected prompt. Both providers share the OpenAI request/response format. If that call fails, the raw text is used.
  4. `injectText` always copies the text to the clipboard first. It then searches the accessibility tree for candidate editable nodes (scored), and tries `ACTION_PASTE`, a custom paste action, and finally `ACTION_SET_TEXT` on each candidate.
  
  Callbacks come back on OkHttp or worker threads. UI and state changes are posted to the main `handler`.

Prefs keys: `use_local`, `model_name`, `use_post_processing`, `post_processing_prompt`, `custom_post_processing_prompt`, plus the cloud settings `api_key` (OpenAI), `openrouter_api_key`, `transcription_provider` / `post_processing_provider` (`openai`|`openrouter`), and `openrouter_transcription_model` / `openrouter_post_processing_model`.

### Cloud providers

`CloudProvider.kt` turns prefs into a `CloudTarget(provider, apiKey, model)` via `SharedPreferences.transcriptionTarget()` and `postProcessingTarget()`. Both the service and `MainActivity` (for the ready check) use them. Model selection exists only for OpenRouter; OpenAI stays on `whisper-1` / `gpt-4o-mini`. The model picker loads OpenRouter's public `/models` list (`OpenRouter.listModels`, filtered by output modality `transcription` or `text`) and falls back to the presets in `CloudProvider.kt` plus free-text input.

### Local models

- `ModelDownloader.MODEL_CATALOG` lists the models. For most entries, the `archive` name is the base name of a `.tar.bz2` file in the sherpa-onnx GitHub `asr-models` release. Each archive is downloaded and extracted into `filesDir/models/<archive>/`; `extractTarBz2` guards against path traversal. Entries with `files` (currently Parakeet Ultra from Hugging Face) are instead fetched file by file from `baseUrl` (pin it to a commit) into a staging dir. Each file's SHA-256 is checked, and the dir is moved to `models/<archive>/` only when all of them match.
- `LocalTranscriber.detectModelConfig` works out the model type from the files in the directory: `preprocess.onnx` means Moonshine; encoder+decoder without a joiner means Whisper; encoder+decoder+joiner means a NeMo transducer/Parakeet; a single `model*.onnx` means NeMo CTC. It prefers `int8` files. To add a new model family, you need a new branch here.

### sherpa-onnx bindings

`app/src/main/kotlin/com/k2fsa/sherpa/onnx/` holds Kotlin API files vendored from upstream sherpa-onnx; treat them as third-party code. They call `System.loadLibrary("sherpa-onnx-jni")`. The native `.so` files (`libsherpa-onnx-jni.so`, `libonnxruntime.so`) live in `app/src/main/jniLibs/arm64-v8a/`, which is gitignored; `make jni-libs` fetches them from the sherpa-onnx Android release pinned in the Makefile (`SHERPA_VERSION`). Calling `./gradlew` directly on a fresh clone builds an APK without them, and local transcription then fails at `loadLibrary`. When bumping `SHERPA_VERSION`, update `SHERPA_SHA256` and re-vendor the Kotlin API files from the same release, since the JNI signatures must match.

## Other directories

- `docs/` is the static GitHub Pages site (landing page, privacy page, JS demo animations). It is not part of the app build.
- `PRIVACY.md` describes the data flows (local vs. direct-to-OpenAI). Keep it in sync if network behavior changes.
