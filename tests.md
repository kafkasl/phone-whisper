# Manual Tests

## Custom Transcription API URL

Feature/change name: Configurable OpenAI-compatible transcription endpoint.

Prerequisites/setup:
- `Phone Whisper` installed.
- Accessibility service and audio permission enabled.
- Cloud transcription enabled.
- A reachable OpenAI-compatible transcription endpoint, for example `http://100.68.233.33:6022/v1`.

Step-by-step actions:
1. Open `Phone Whisper`.
2. Tap `API key / bearer token` and enter the bearer token expected by the custom endpoint.
3. Tap `Transcription API URL` and enter the endpoint base URL.
4. Open any app with a text field and focus it so the keyboard appears.
5. Tap the floating microphone button, record a short phrase, then tap again to stop.
6. Repeat in both light theme and dark theme.

Expected results:
- The app sends audio to `<base URL>/audio/transcriptions` when the base URL already ends in `/v1`.
- The request includes `Authorization: Bearer <token>` when a token is configured.
- The returned `text` value is inserted into the focused field, with clipboard fallback behavior preserved.
- Light theme result: endpoint settings are readable and transcription still inserts text.
- Dark theme result: endpoint settings are readable and transcription still inserts text.

Rollback/cleanup notes:
- Clear `Transcription API URL` with `Use default` to return to the official OpenAI endpoint.
- Disable cloud transcription to return to local transcription.
