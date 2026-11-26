# Privacy Policy for Phone Whisper

Last updated: November 2026

Phone Whisper is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps.

## Data handling

Phone Whisper supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local `sherpa-onnx` speech recognition models. Audio does not leave the device.

### Cloud streaming mode

In cloud mode, audio is streamed directly from your device to the cloud speech-to-text provider you configure (**ElevenLabs** or **Google Gemini**) using your own API key. If an offline recording is retried later, the saved WAV clip is sent directly to that provider's transcription API.

If optional post-processing cleanup is enabled for a shortcut, the transcribed text—and, only if you enable **Include screen context** for that shortcut, a bounded snapshot of visible non-password text in the active window and draft text before the cursor—is sent directly from your device to your configured OpenAI-compatible chat endpoint (such as Google Gemini, OpenAI, or Groq).

### Local recordings cache

To prevent lost speech when the network drops, recordings are written to private app storage (`files/recordings/`). Phone Whisper keeps up to 10 recent unflagged clips plus any clips you explicitly flag, and automatically deletes older unflagged clips. You can delete any clip at any time from **Saved recordings** in the app.

## API keys

Your API keys are stored locally on your device in private app storage and used solely to authenticate requests sent directly from your device to the providers you configure.

I do not operate a backend or relay server for these requests.

## Accessibility Service

Phone Whisper uses Android Accessibility Service only after you explicitly trigger dictation via the floating overlay button, for two narrow purposes:

1. Identifying the currently focused text field and inserting dictated text.
2. When **Include screen context** is enabled on the chosen shortcut, reading visible text in the active window and text before the cursor (always skipping password fields) to improve spelling of names, technical terms, and self-corrections. You can disable screen context per shortcut (it is off by default on `NOTE`).

Phone Whisper does not monitor browsing, log keystrokes, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for Phone Whisper and do not collect user accounts, analytics, crash reports, or recordings myself.

Third-party providers you choose to use (such as ElevenLabs, Google Gemini, OpenAI, or another OpenAI-compatible endpoint) process data according to their own terms and privacy policies.

## Contact

For questions about privacy, contact: pol.avms@gmail.com
