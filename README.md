# Audiobook Forge

Convert EPUB/TXT ebooks into audiobooks **fully offline, on your phone** — no server,
no cloud, no Termux. Renders each chapter with the Kokoro-82M neural TTS model
(multilingual, dozens of voices) running on-device via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

Designed around a simple insight: *real-time TTS reading fights your battery and
stutters; rendering while charging eliminates both.*

## How it works

1. **Import** an EPUB or TXT (system file picker — EPUB is parsed natively: ZIP → OPF → spine → XHTML text).
2. Pick a **voice** (curated Kokoro voice list) and **speed**.
3. Hit **Render**. Background work (WorkManager) synthesizes chapter-by-chapter:
   - one persistent sherpa-onnx session (no per-sentence re-init),
   - paragraph→sentence chunking with prefetch-friendly sizes,
   - PCM → AAC-LC encode via `MediaCodec`, muxed to `.m4a` via `MediaMuxer` per chapter.
4. **Render only while charging** toggle enforces the wall-power workflow at the OS level.
5. Tap any finished chapter for instant playback (Media3/ExoPlayer mini-player), or grab
   the `.m4a` files from app storage.

## Why not just use Moon+ Reader + a TTS engine?

Real-time engines synthesize one sentence at a time against the reader's pace — you get
buffering gaps when synthesis lags, and 40–50%/hour battery drain from sustained inference.
Pre-rendering converts once (plugged in), then plays like any local audiobook (~2–5%/hour).

## Building

Requirements: JDK 17, Android SDK (platform 34, build-tools 34).

```bash
./gradlew assembleDebug        # or: gradle assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Install on an arm64 device (Android 10+).

First launch: download the Kokoro int8 multi-lang model in-app (~90 MB, one time) —
pulled from k2-fsa's release assets, stored in app-private storage, fully offline after.

## Tech notes

- **sherpa-onnx v1.13.6**: prebuilt `arm64-v8a` JNI libs vendored under `app/src/main/jniLibs/`,
  Kotlin API wrapper vendored under `com.k2fsa.sherpa.onnx`.
- **No third-party runtime deps** beyond AndroidX/Media3/WorkManager/kotlinx +
  commons-compress (tar.bz2 extraction). EPUB/TXT parsing is hand-rolled and unit-tested.
- Voice ids map to Kokoro's alphabetical `voices.bin` ordering (see `Voices.kt`).

## Roadmap

- Single-file `.m4b` output with chapter markers (needs chpl-atom muxing)
- PDF input (PdfBox-Android)
- Per-chapter parallelism / NNAPI execution provider experiments
- Export/share rendered books to other audiobook players

**Won't fix:** MOBI/AZW3 input — proprietary, declining format; convert to EPUB once with Calibre instead (Amazon itself dropped MOBI uploads in 2022). DRM-protected files are out of scope permanently.

## License

Apache-2.0. Kokoro model weights: Apache-2.0 (hexgrad/kokoro). sherpa-onnx: Apache-2.0.
