# Vendored native libraries

The app vendors the sherpa-onnx Kotlin API under
`app/src/main/kotlin/com/k2fsa/sherpa/onnx/`. Those `external fun` declarations
are backed by the prebuilt native libraries below, which must be kept in sync
with the Kotlin API version.

## Source

Extracted from the official sherpa-onnx Android AAR:

- Release: **v1.12.33** — https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.33
- Asset: `sherpa-onnx-1.12.33.aar` → `jni/arm64-v8a/`

Only `arm64-v8a` is shipped, matching `abiFilters` in `app/build.gradle.kts`.
Only the two libs actually referenced are included: `libsherpa-onnx-jni.so`
(loaded via `System.loadLibrary("sherpa-onnx-jni")`) and its sole non-system
dependency `libonnxruntime.so` (per `readelf -d`).

## Why v1.12.33

The vendored Kotlin (`OfflineRecognizer.kt` and all config classes) is
byte-identical to the v1.12.33 kotlin-api, and `OfflineModelConfig` is
field-identical, so the JNI `GetFieldID` lookups all resolve. v1.12.34+ adds
`qwen3Asr`/`cohereTranscribe` config fields the vendored Kotlin lacks, which
would mismatch a newer `.so`.

## Checksums (SHA-256)

```
9ae6a66f4acc0a300420aea7bef37140f9f299006907d161bd431ccf8a4ec8e4  arm64-v8a/libsherpa-onnx-jni.so
e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302  arm64-v8a/libonnxruntime.so
```

## Upgrading

When bumping the vendored Kotlin API, replace these `.so` files from the
matching sherpa-onnx release and update the checksums above.
