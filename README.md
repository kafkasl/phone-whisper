# 🎤 Phone Whisper

Push-to-talk dictation for Android. Tap a floating button, speak, and text gets inserted into whatever field has focus. Uses OpenAI Whisper API for transcription.

Works with any keyboard (SwiftKey, Gboard, etc.) — no switching needed.

## How It Works

1. A small overlay dot floats on your screen
2. **Tap** → starts recording (dot turns red)
3. **Tap again** → sends audio to OpenAI Whisper API (dot turns blue)
4. Transcribed text is pasted into the focused text field
5. Dot turns green → ready for next dictation

If no text field is focused, the text is copied to clipboard.

## Setup

### Prerequisites

- Android phone (API 30+ / Android 11+)
- OpenAI API key
- [Termux](https://f-droid.org/en/packages/com.termux/) with `termux-api` (for remote install via SSH) — or just transfer the APK manually

### Install the APK

**Option A — from your computer (SSH via Tailscale):**

```bash
cp .env.example .env
# edit .env with your phone's IP/hostname
make install
```

**Option B — manual:**

Copy `app/build/outputs/apk/debug/app-debug.apk` to your phone and open it.

### First-Time Phone Setup

1. **Open Phone Whisper** → grant the audio recording permission when prompted

2. **Paste your OpenAI API key** in the text field → tap **Save API Key**

3. **Allow restricted settings** (required for sideloaded apps on Android 13+):
   - Go to **Settings → Apps → Phone Whisper**
   - Tap **⋮ (three dots)** top-right → **"Allow restricted settings"**

4. **Enable the accessibility service**:
   - Tap **"Open Accessibility Settings"** in the app
   - Find **Phone Whisper** → toggle it **on**
   - Confirm the prompt

5. A **green dot** appears on the right edge of your screen — you're ready!

### Status Checklist

The app shows ✅/❌ for each requirement:

```
Audio permission:      ✅
Accessibility service: ✅
API key:               ✅
→ Ready! Tap the green dot to dictate.
```

All three must be green.

## Build

```bash
make build   # build debug APK
make test    # run unit tests (12 tests)
make clean   # clean build artifacts
```

Requires JDK 17 and Android SDK. The Makefile sets `JAVA_HOME` and `ANDROID_HOME` automatically (macOS/Homebrew paths).

## Architecture

```
app/src/main/kotlin/com/kafkasl/phonewhisper/
├── WavWriter.kt                    # PCM → WAV encoding
├── TranscriberClient.kt            # OpenAI Whisper API client
├── WhisperAccessibilityService.kt  # overlay button + recording + text injection
└── MainActivity.kt                 # permissions + API key setup
```

The accessibility service does the heavy lifting:
- Shows a floating overlay button (`TYPE_ACCESSIBILITY_OVERLAY` — no extra permission needed)
- Records audio via `AudioRecord` (16kHz mono PCM)
- Wraps PCM in WAV, sends to OpenAI Whisper API
- Injects transcribed text via clipboard paste into the focused field

## License

Personal project. Do whatever you want with it.
