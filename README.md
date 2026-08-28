<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper

Push-to-talk dictation for Android.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Local on-device transcription** with sherpa-onnx
- **Cloud transcription** with OpenAI Whisper
- **OpenAI-compatible transcription endpoints** for custom bridge services
- **Optional cleanup** with OpenAI to fix punctuation and grammar
- **Optional clipboard copy** when direct text insertion needs a paste fallback

If you try it and it genuinely saves you time, consider [sponsoring](https://github.com/sponsors/kafkasl)


## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/kafkasl/phone-whisper/releases).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/kafkasl/phone-whisper.git && cd phone-whisper
make build
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. If `Copy transcript to clipboard` is enabled, the transcript is also copied and paste actions can be used as a fallback

## Setup

### First-time setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: paste your OpenAI API key
   - **Custom endpoint**: paste a bearer token and set a compatible transcription base URL

Once setup is done, the floating button is ready.

### Custom transcription endpoint

Cloud transcription can use either the official OpenAI API or a compatible bridge that implements:

```text
POST /v1/audio/transcriptions
Authorization: Bearer <token>
multipart/form-data:
  model=whisper-1
  file=audio.wav
```

The response must include a top-level `text` field:

```json
{"text":"transcribed text"}
```

In the app:

1. Enable **Use cloud transcription**.
2. Set **API key / bearer token** to the token expected by the endpoint.
3. Set **Transcription API URL** to the base URL, for example `http://whisper.example.test/v1`.

URL handling:

- `https://api.openai.com/v1` sends to `https://api.openai.com/v1/audio/transcriptions`.
- `http://host:port/v1` sends to `http://host:port/v1/audio/transcriptions`.
- `http://host:port` sends to `http://host:port/v1/audio/transcriptions`.

Do not commit real bearer tokens to the repository. Store them only in the app settings or in your private server environment.

### Clipboard behavior

By default, `Copy transcript to clipboard` is off. In this mode Phone Whisper tries direct `ACTION_SET_TEXT` insertion and does not replace the Android clipboard.

When `Copy transcript to clipboard` is on, the app also writes the transcript to the clipboard and allows paste actions as a fallback for apps that do not support direct text setting.

The app treats an editable field whose visible text equals its hint text as empty. This prevents placeholders such as `Message` or `Сообщение` from being prepended to dictated text.

Successful direct insertion does not show an `Inserted` overlay. The app only shows feedback for explicit clipboard copy, insertion failure, or cleanup fallback.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Phone Whisper supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to OpenAI's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to OpenAI's chat API

I don't run a backend for this app. In cloud mode, requests go straight from your phone to OpenAI using your own API key.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.kafkasl.phonewhisper/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Parakeet 110M | 100 MB | Best default |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

### Android diagnostics

Phone Whisper writes timing diagnostics to Logcat with the tag `PhoneWhisper`.

```bash
adb logcat -c
adb logcat -v time -s PhoneWhisper
```

Each dictation attempt gets a short `trace=<id>` value. Use that value to follow one request across recording, WAV encoding, HTTP upload, server response, post-processing, and text injection.

Useful stages include:

- `record_start_requested`
- `record_stopped`
- `wav_encode_start`
- `wav_encode_end`
- `api_transcribe_start`
- `client_build_start`
- `http_request_headers_start`
- `http_request_body_end`
- `http_response_headers_start`
- `client_response_body`
- `api_transcribe_callback`
- `inject_start`
- `inject_action_set_text`
- `inject_end`

For a custom bridge, compare Android `trace=<id>` timestamps with the bridge request logs. A server-side `401` with `hasAuthorizationHeader=false` means the incoming request reached the bridge without an `Authorization` header.

## App compatibility

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own OpenAI API key

## Support the project

If Phone Whisper saves you time, you can sponsor the project on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it.
