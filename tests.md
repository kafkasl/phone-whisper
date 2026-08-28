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

## Protected Bridge Token And Diagnostics

Feature/change name: Bearer token protected custom transcription with Android/server timing logs.

Prerequisites/setup:
- `Phone Whisper Codex` installed.
- Accessibility service and audio permission enabled.
- Cloud transcription enabled.
- Server bridge running at `http://whisper.webuirenat.duckdns.org/v1`.
- API key / bearer token set to the bridge token.
- Android `adb logcat` available for inspecting logs tagged `PhoneWhisper`.

Step-by-step actions:
1. Open `Phone Whisper Codex`.
2. Set `API key / bearer token` to the server bridge token.
3. Set `Transcription API URL` to `http://whisper.webuirenat.duckdns.org/v1`.
4. Open a text field in any app so the overlay appears.
5. Tap the microphone, record a short phrase, then tap again to stop.
6. Run `adb logcat -s PhoneWhisper` and confirm all entries for that attempt share the same `trace=<id>`.
7. Check the server bridge log and match the request by timestamp and request duration.
8. Repeat in both light theme and dark theme.

Expected results:
- Android logs include recording, WAV encoding, HTTP DNS/connect/upload/response, callback, post-processing, and injection stages with elapsed milliseconds.
- Server logs include bridge timing stages for the same request and show whether auth passed or failed.
- With the correct bearer token, transcription succeeds and inserts text.
- Without the bearer token, the server returns `401 Unauthorized`.
- Light theme result: token and endpoint settings remain readable and transcription succeeds.
- Dark theme result: token and endpoint settings remain readable and transcription succeeds.

Rollback/cleanup notes:
- Revert the API key and endpoint settings to previous values if testing another provider.
- Clear logcat after collecting diagnostics with `adb logcat -c` if needed.

## Clipboard Copy Toggle

Feature/change name: Optional transcript clipboard copy.

Prerequisites/setup:
- `Phone Whisper Codex` installed.
- Accessibility service and audio permission enabled.
- A working local or cloud transcription engine.
- A target app with an editable text field.

Step-by-step actions:
1. Open `Phone Whisper Codex`.
2. Turn `Copy transcript to clipboard` off.
3. Dictate into a focused editable field.
4. Confirm the text is inserted and the Android clipboard is not replaced by the transcript.
5. Turn `Copy transcript to clipboard` on.
6. Dictate again into a focused editable field.
7. Confirm the app may use paste actions and the transcript is available in the clipboard.
8. Repeat in both light theme and dark theme.

Expected results:
- When the toggle is off, the service does not call `ClipboardManager.setPrimaryClip` and only attempts direct `ACTION_SET_TEXT` insertion.
- When the toggle is on, the previous clipboard-backed paste fallback remains available.
- Light theme result: the toggle is readable and changes state correctly.
- Dark theme result: the toggle is readable and changes state correctly.

Rollback/cleanup notes:
- Turn `Copy transcript to clipboard` on to restore the previous clipboard-backed behavior.

## Direct Insert Placeholder And Feedback Regression

Feature/change name: Direct insertion must not prepend input hints or show success feedback.

Prerequisites/setup:
- `Phone Whisper Codex` installed from the current debug APK.
- Accessibility service and audio permission enabled.
- A working local or cloud transcription engine.
- `Copy transcript to clipboard` turned off.
- A target app with an empty editable field whose hint is visible, for example `Message` or `Сообщение`.

Step-by-step actions:
1. Open the target app and focus the empty text field so the hint is visible.
2. Tap the floating microphone button, record a short phrase, then tap again to stop.
3. Watch the overlay feedback while the text is inserted.
4. Inspect the final text in the field.
5. Repeat in both light theme and dark theme.

Expected results:
- The dictated text is inserted without prepending the hint text.
- Placeholder text such as `Message` or `Сообщение` is not converted into real message content.
- No `Inserted` success bubble is shown after successful direct insertion.
- Light theme result: insertion succeeds and no unwanted success bubble appears.
- Dark theme result: insertion succeeds and no unwanted success bubble appears.

Rollback/cleanup notes:
- Reinstall the previous APK if a target app depends on clipboard-backed paste behavior.
- Turn `Copy transcript to clipboard` on to allow the older paste-based fallback path.

## Kwork Trumbowyg Placeholder Regression

Feature/change name: Web rich-text placeholder cleanup for Kwork offer description.

Prerequisites/setup:
- `Phone Whisper Codex` installed from the current debug APK.
- Accessibility service and audio permission enabled.
- Cloud or local transcription working.
- `Copy transcript to clipboard` turned off.
- Android browser opened to `https://kwork.ru/new_offer?project=3173172`.
- The `Описание` editor is empty and shows the placeholder text.

Step-by-step actions:
1. Focus the empty Kwork `Описание` editor.
2. Tap the Phone Whisper floating microphone button.
3. Record a short phrase, then tap again to stop.
4. Inspect the inserted editor text.
5. Open `adb logcat -v time -s PhoneWhisper`.
6. Repeat in both light theme and dark theme.

Expected results:
- The inserted text does not include `Напишите, как вы будете решать задачу клиента`.
- Logcat includes `stage=inject_existing_text_ignored` with a placeholder-related reason.
- Real existing text is preserved when the cursor is inside non-placeholder content.
- The visual CSS placeholder may remain visible until the page receives another real input event; this is accepted because the placeholder is not part of the inserted text.
- Light theme result: the editor remains usable and the placeholder is not prepended.
- Dark theme result: the editor remains usable and the placeholder is not prepended.

Rollback/cleanup notes:
- Reinstall the previous APK if a specific web editor requires the older direct text behavior.
- Turn `Copy transcript to clipboard` on if a site works better with paste actions.

## Recording Button States And Screen-Off Cancel

Feature/change name: Voice-reactive recording button, in-button loader, and screen-off recording cancellation.

Prerequisites/setup:
- `Phone Whisper` installed from the current debug APK.
- Accessibility service and audio permission enabled.
- A target app with an editable field focused so the overlay is visible.
- A working local or cloud transcription engine.

Step-by-step actions:
1. Focus a text field and confirm the idle microphone button appears.
2. Tap the microphone and speak at different volumes.
3. Confirm there are no rings around or inside the button, and the button itself grows/shrinks strongly with voice volume.
4. Confirm the microphone icon is hidden during recording and a three-bar equalizer is shown instead.
5. Tap again to stop recording.
6. Confirm the microphone icon disappears and a loader spins inside the button during transcription.
7. Confirm successful text insertion returns the button to idle.
8. Start recording again, then turn the screen off with the power button.
9. Turn the screen back on and confirm the recording did not submit transcription.
10. Repeat visual checks in both light theme and dark theme.

Expected results:
- Recording animation reacts strongly to microphone input level through button scaling.
- No circular ring animation is drawn around or inside the recording button.
- The recording icon is a three-bar equalizer whose bars move in a quick delayed wave from left to right.
- Transcribing state shows only the in-button loader, not the microphone icon.
- Turning the screen off during recording cancels the recording, discards captured audio, and does not submit transcription.
- Light theme result: all overlay states are visible and readable.
- Dark theme result: all overlay states are visible and readable.

Rollback/cleanup notes:
- Reinstall the previous APK if the simpler microphone/pulse recording state is desired.
