# Audiobook NightForge

Convert EPUB/TXT/PDF ebooks into audiobooks **fully offline, on your phone** — no server,
no cloud, no Termux. Renders each chapter with the Kokoro-82M neural TTS model
(multilingual, dozens of voices) running on-device via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

Designed around a simple insight: *real-time TTS reading fights your battery and
stutters; rendering while charging eliminates both.*

## How it works

1. **Import** an EPUB, TXT, or PDF (system file picker — EPUB parsed natively; PDFs must be born-digital text, not scans).
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
  commons-compress (tar.bz2 extraction). EPUB/TXT/PDF parsing is hand-rolled and unit-tested.
- Voice ids map to Kokoro's alphabetical `voices.bin` ordering (see `Voices.kt`).

## Measured performance (Snapdragon 8 Elite, plugged in)

Multi-hour full-book renders, real EPUB content, per-chapter RTF tracked start to finish:

| Engine / mode | RTF cold | RTF sustained | Notes |
|---|---|---|---|
| Kokoro 82M **fp32**, 6 threads, stock clocks | 0.51 | ~0.59–0.60 plateau | default recommendation |
| Kokoro 82M **fp32**, 6 threads, perf mode | 0.44 | converges to ~0.59 | wins the first hour only |
| Kokoro 82M int8, 6 threads | — | 0.7 → 1.7 spiral | ARM int8 kernels underperform |
| Piper Lite int8 | ~0.30 | ~0.30 flat | thermally trivial; audibly flatter |

**Findings worth stealing:**

- **RTF is thermally bounded.** Stock and boosted clocks converge on the same
  ~0.6 equilibrium on this chassis — higher clocks arrive sooner and pay it back
  as heat soak. Mode choice is a *book-length* decision: short content finishes
  inside perf mode's golden hour; overnight novels do the same job either way.
- **Quality is independent of speed.** Same model, same output bits at any RTF;
  faster rendering buys more books per night, not better ones.
- On modern flagship ARM the fp32 model can be ~2× **faster** than its int8
  variant — always benchmark before assuming quantized is quicker.

Listening verdict (by the project author): Kokoro fp32 sounds clearly better than
both alternatives; Piper trades noticeable naturalness for ~2× more speed and a
30 MB footprint — the right choice for modest hardware.

RTF = synthesis time ÷ audio duration; below 1.0 renders faster than realtime.

## Roadmap

- Single-file `.m4b` output with chapter markers (needs chpl-atom muxing)
- ~~PDF input~~ ✅ shipped in v0.3.0 (born-digital text via PdfBox-Android)
- Per-chapter parallelism / NNAPI execution provider experiments
- Export/share rendered books to other audiobook players
- **Screen-off throttling**: gaming/performance clocks often disengage when the
  display sleeps, so overnight renders run at stock speeds even though our
  wake lock keeps the CPU awake. Candidate fix: an opt-in "keep screen awake
  while forging (plugged-in)" toggle rendering at zero brightness.

**Won't fix:** MOBI/AZW3 input — proprietary, declining format; convert to EPUB once with Calibre instead (Amazon itself dropped MOBI uploads in 2022). DRM-protected files are out of scope permanently.

## License

**This project is MIT-licensed** — see [LICENSE](LICENSE). Do what you like with the code.

Third-party components keep their own licenses, none changed by bundling: sherpa-onnx (Apache-2.0), Kokoro model weights (Apache-2.0, hexgrad/kokoro), pdfbox-android (Apache-2.0), commons-compress (Apache-2.0). Full details in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
