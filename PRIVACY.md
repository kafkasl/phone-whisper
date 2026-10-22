# Privacy Policy for Phone Whisper

Phone Whisper is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps.

## Data handling

Phone Whisper supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local speech recognition models. Audio does not leave the device.

Models are downloaded only when you choose to download them, from GitHub (sherpa-onnx releases) or Hugging Face (Parakeet Ultra). These downloads contain no audio or text.

### Cloud mode

In cloud mode, recorded audio is sent directly from the device to the transcription API of the provider you select, OpenAI or OpenRouter, to generate text.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to the chat API of the provider you select, OpenAI or OpenRouter, to improve punctuation, capitalization, and clarity.

OpenRouter forwards requests to the provider of the model you choose there, which processes the data under its own terms.

## API keys

If you use cloud features, your OpenAI and/or OpenRouter API keys are stored locally on your device in app storage and used to authenticate requests sent directly to those services.

I do not operate a relay server for these requests.

## Accessibility Service

Phone Whisper uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

Phone Whisper is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for Phone Whisper and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as OpenAI or OpenRouter, may process data according to their own terms and privacy policies.

## Contact

For questions about privacy, contact: pol.avms@gmail.com
