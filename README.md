# 🎤 Phone Whisper

Push-to-talk dictation for Android. Tap a floating button, speak, and text gets inserted into whatever field has focus. Supports **local on-device transcription** (sherpa-onnx) and **cloud** (OpenAI Whisper API).

Works with any keyboard (SwiftKey, Gboard, etc.) — no switching needed.

## How It Works

1. A small overlay dot floats on your screen (draggable, snaps to edges)
2. **Tap** → starts recording (dot pulses red)
3. **Tap again** → transcribes (dot turns gray)
4. Transcribed text is pasted into the focused text field
5. Dot returns to idle → ready for next dictation

If no text field is focused, the text is copied to clipboard.

## Setup

### Prerequisites

- Android phone (API 30+ / Android 11+)
- **For local mode**: a model pushed to the phone (see [Local Models](#local-models))
- **For cloud mode**: OpenAI API key

### Install

**Preferred — ADB (preserves accessibility permissions across updates):**

```bash
make adb-install
```

> **Why ADB?** Installing via package manager (Termux/file manager) resets
> accessibility permissions every time. `adb install -r` does an in-place
> update that keeps them.

**Alternative — SSH via Tailscale:**

```bash
cp .env.example .env   # set PHONE_HOST to your phone's Tailscale IP
make install
```

> Note: Tailscale MagicDNS may not resolve hostnames if your system DNS
> doesn't include the `.ts.net` search domain. Use the Tailscale IP directly
> (check with `tailscale status`). The phone also needs the `termux-api`
> package installed (`pkg install termux-api` in Termux).

### First-Time Phone Setup

1. **Open Phone Whisper** → grant the audio recording permission

2. **Allow restricted settings** (required for sideloaded apps on Android 13+):
   - **Settings → Apps → Phone Whisper → ⋮ (three dots) → "Allow restricted settings"**

3. **Enable the accessibility service**:
   - Tap **"Open Accessibility Settings"** in the app
   - Find **Phone Whisper** → toggle **on** → confirm

4. **Configure engine**:
   - **Local mode** (default): push a model first (see below)
   - **Cloud mode**: toggle to cloud, paste your OpenAI API key, save

5. The overlay dot appears — you're ready!

## Local Models

Models live on the phone at `/data/data/com.kafkasl.phonewhisper/files/models/`.
Push them via ADB (phone must be connected via USB):

```bash
# Download a model (example: moonshine tiny)
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2
tar xjf sherpa-onnx-moonshine-tiny-en-int8.tar.bz2

# Push to phone
make push-model MODEL=sherpa-onnx-moonshine-tiny-en-int8
```

The app auto-detects model type (Moonshine, Whisper, Parakeet/NeMo).

### Available Models

| Model | Size | Speed* | Quality | Download |
|-------|------|--------|---------|----------|
| `sherpa-onnx-moonshine-tiny-en-int8` | ~117MB | ⚡ fastest | ★★☆ | [link](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2) |
| `sherpa-onnx-whisper-tiny.en` | ~40MB | ⚡ fast | ★★☆ | [link](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2) |
| `sherpa-onnx-whisper-base.en` | ~80MB | ⚡ fast | ★★★ | [link](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2) |
| `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` | ~350MB | ⚡⚡⚡ | ★★★★ | [link](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2) |

*Speed estimates on Pixel 5 (Snapdragon 765G)

## Build

```bash
make build       # build debug APK
make test        # run unit tests (12 tests)
make adb-install # build + install via ADB
make push-model MODEL=/path/to/model  # push model to phone
make clean       # clean build artifacts
```

Requires JDK 17 and Android SDK.

### Native Libraries

The sherpa-onnx native `.so` files go in `app/src/main/jniLibs/arm64-v8a/` (gitignored).
To set up from scratch:

```bash
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.28/sherpa-onnx-v1.12.28-android.tar.bz2
tar xjf sherpa-onnx-v1.12.28-android.tar.bz2
mkdir -p app/src/main/jniLibs/arm64-v8a
cp jniLibs/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
```

## Architecture

```
app/src/main/kotlin/com/kafkasl/phonewhisper/
├── WavWriter.kt                    # PCM → WAV encoding (TDD)
├── TranscriberClient.kt            # OpenAI Whisper API client (TDD)
├── LocalTranscriber.kt             # sherpa-onnx wrapper, auto-detects model type
├── WhisperAccessibilityService.kt  # overlay + recording + transcription + text injection
└── MainActivity.kt                 # setup UI, engine selection

app/src/main/kotlin/com/k2fsa/sherpa/onnx/
├── OfflineRecognizer.kt            # sherpa-onnx Kotlin API (JNI bindings)
├── OfflineStream.kt
├── FeatureConfig.kt
├── Vad.kt
├── QnnConfig.kt
└── HomophoneReplacerConfig.kt
```

### Key Design Decisions

- **Overlay uses `TYPE_ACCESSIBILITY_OVERLAY`** — no `SYSTEM_ALERT_WINDOW` permission needed
- **Text injection via clipboard paste** — `ACTION_PASTE` on focused node, clipboard fallback
- **Models on filesystem, not in APK** — keeps APK at 33MB, swap models without rebuilding
- **Auto-detect model type** from files present in model directory
- **ADB install for dev** — preserves accessibility permissions across updates

## License

Personal project. Do whatever you want with it.
