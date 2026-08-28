# Manual Tests

## Pixel Home Swipe Overlay Watchdog

Feature/change name: Accessibility overlay visibility watchdog for Pixel Home swipe.

Prerequisites/setup:
- Pixel 7 Pro connected with USB debugging enabled.
- `Phone Whisper Codex` installed with the accessibility service enabled.
- Android SDK `adb` available.

Step-by-step actions:
1. Open `Phone Whisper Codex` on the phone.
2. Tap the `OpenAI API Key` setting to show a text field and keyboard.
3. Confirm the microphone overlay appears while the keyboard is visible.
4. Swipe up from the bottom to go Home.
5. Wait 1-2 seconds.
6. Confirm the microphone overlay disappears on the launcher screen.
7. Reopen the app and repeat in both light theme and dark theme.

Expected results:
- The microphone overlay appears when the keyboard is visible in the app.
- The microphone overlay disappears after Home swipe, even if Android does not emit a reliable keyboard hide event.
- Light theme result: overlay appears over the keyboard state and is removed on Home.
- Dark theme result: overlay appears over the keyboard state and is removed on Home.

Rollback/cleanup notes:
- Reinstall the previous APK if the watchdog causes unwanted overlay behavior.
- Disable the `Phone Whisper Codex` accessibility service to stop all overlay behavior.
