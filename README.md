# Audiobook NightForge

Convert EPUB/TXT/PDF ebooks into audiobooks **fully offline, on your phone** — no server,
no cloud, no Termux. Renders chapter by chapter with on-device neural TTS via
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), then plays them like any local
audiobook.

Designed around a simple insight: *real-time TTS reading fights your battery and
stutters; rendering while charging eliminates both.*

## Engines

Pick one engine, or install several and switch between them without restarting the app
(installing another no longer removes the first).

| Engine | Size | Languages | Notes |
|---|---|---|---|
| **Kokoro 82M fp32** | ≈440 MB | 8 + Mandarin | Best quality, and **usually the fastest on modern ARM** — see the benchmark below |
| Kokoro 82M int8 | ≈126 MB | 8 + Mandarin | Smaller download, but **int8 kernels underperform on ARM** and slow further as the device warms |
| Kitten nano | ≈30 MB | English | Tiny and quick |
| Piper Lite | ≈30 MB | English | Fast, flatter delivery |
| Piper Dutch (ronnie / pim) | ≈21 MB | Dutch | The two Dutch voices |
| **ZipVoice** | ≈104 MB + 54 MB vocoder | Chinese + English | **Voice cloning** — see below |

**Voice cloning (ZipVoice).** Give it a short reference clip *plus the exact words spoken
in it* and it reads your books in that voice — no training, nothing leaves the device. Two
ways to make a clip:

- **Record one in the app** — it puts a sentence on screen that is written to cover the
  awkward English sounds, records you reading it, and fills in the transcript for you
  (a known script means a known transcript).
- **Import one** — any WAV the engine can read (16-bit PCM or 32-bit float, 8–96 kHz).
  The app can **transcribe it for you** with an optional Whisper model (≈111 MB) so you
  don't have to type the words.

A wrong transcript degrades a clone badly, so check what the transcriber heard before
saving — it is accurate on ordinary prose and shaky on acronyms and proper nouns. Cloning
is also roughly **6× slower** than Kokoro (RTF ≈ 5.7 measured here): fine for a project,
not for a whole library.

## How it works

1. **Import** an EPUB, TXT, or PDF (system file picker — EPUB parsed natively; PDFs must be born-digital text, not scans).
2. Pick an **engine** and **voice** (Kokoro's curated list, a Kitten speaker, the built-in
   Piper voice, or one of your clones) and a **speed**.
3. Hit **Render**. Background work (WorkManager) synthesizes chapter-by-chapter:
   - one persistent sherpa-onnx session (no per-sentence re-init),
   - paragraph→sentence chunking with prefetch-friendly sizes,
   - every finished chapter is **read back** before being marked done, so a file the
     platform cannot parse fails immediately and visibly instead of surfacing later.
4. **Render only while charging** is on by default: an unplugged render pauses and
   resumes when you plug in, losing nothing. Turning it off is allowed — the app says
   plainly that it drains the battery fast.
5. Tap any finished chapter for instant playback (Media3/ExoPlayer mini-player), or
   export the files.

**Output formats:** Opus (`.ogg`, resampled to an Opus-safe rate), AAC (`.m4a`), or WAV.
**Single-file `.m4b`** bundles every chapter into one file, with per-chapter files left
untouched. Chapter marks are written two ways: Nero `chpl` atoms always (capped at 255 —
the box's limit, and the exported filename says so: `Book.Part255of260.m4b`), plus an
Apple-style chapter track when the device's muxer supports one. If neither can be written
the file is honestly named `Book.nochapters.m4b` rather than pretending.

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

First launch: download an engine from the in-app catalogue — pulled from k2-fsa's release
assets, stored in app-private storage, fully offline after. Start with **Kokoro fp32**
unless storage is tight.

## Tech notes

- **sherpa-onnx v1.13.6**: prebuilt `arm64-v8a` JNI libs vendored under `app/src/main/jniLibs/`,
  Kotlin API wrapper vendored under `com.k2fsa.sherpa.onnx` — including the offline
  recogniser bindings used for clone transcription, from the same tag as the native libs.
- **No third-party runtime deps** beyond AndroidX/Media3/WorkManager/kotlinx +
  commons-compress (tar.bz2 extraction). EPUB/TXT/PDF parsing is hand-rolled and unit-tested.
- Voice ids map to Kokoro's alphabetical `voices.bin` ordering (see `Voices.kt`).
- Model installs are transactional: the working model is moved aside, the new one verified,
  then the backup dropped — a corrupt archive never destroys a working engine.

## Measured performance (Snapdragon 8 Elite, plugged in)

Multi-hour full-book renders, real EPUB content, per-chapter RTF tracked start to finish:

| Engine / mode | RTF cold | RTF sustained | Notes |
|---|---|---|---|
| Kokoro 82M **fp32**, 6 threads, stock clocks | 0.51 | ~0.59–0.60 plateau | default recommendation |
| Kokoro 82M **fp32**, 6 threads, perf mode | 0.44 | converges to ~0.59 | wins the first hour only |
| Kokoro 82M **fp32**, **8 threads**, Night Forge mode | ~0.5 | **~0.62 flat at 1h+** | foreground-status fix defeats overnight throttling entirely |
| Kokoro 82M int8, 6 threads | — | 0.7 → 1.7 spiral | ARM int8 kernels underperform |
| Piper Lite int8 | ~0.30 | ~0.30 flat | thermally trivial; audibly flatter |
| ZipVoice (cloning) | — | ~5.7 | measured on a mid-range MediaTek tablet |

**Findings worth stealing:**

- **RTF is thermally bounded — and scheduler-bounded.** Stock and boosted clocks
  converge on the same ~0.6 equilibrium interactively. Unattended renders
  previously collapsed to RTF 2.5+ (OEM governors revoke big cores from
  background apps regardless of wake locks); **Night Forge mode** holds the app
  foreground and eliminates the collapse: 8-thread sustained 0.62 at the one-hour
  mark, no degradation.
- **Mode choice is a book-length decision**: short content finishes inside perf
  mode's golden hour; overnight novels do the same job either way.
- **Quality is independent of speed.** Same model, same output bits at any RTF;
  faster rendering buys more books per night, not better ones.
- On modern flagship ARM the fp32 model can be ~2× **faster** than its int8
  variant — always benchmark before assuming quantized is quicker.
- **Fewer cloning steps render faster for almost no audible cost** — try 3–4 instead
  of the default 5. A shorter reference clip (5–10 s) helps too.

Listening verdict (by the project author): Kokoro fp32 sounds clearly better than
both alternatives; Piper trades noticeable naturalness for ~2× more speed and a
30 MB footprint — the right choice for modest hardware.

RTF = synthesis time ÷ audio duration; below 1.0 renders faster than realtime.

## Permissions

Every permission the app declares, and why:

| Permission | Why |
|---|---|
| `INTERNET` | Downloading engines from k2-fsa's release assets, in-app. No user content is ever uploaded — books, recordings and clones stay in app-private storage |
| `RECORD_AUDIO` | Recording a voice-clone reference clip, and nothing else |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE(_SPECIAL_USE)` | The render progress notification |
| `WAKE_LOCK` | Keeping the CPU awake through a long render |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Asking to be exempted from Doze so an overnight render is not throttled |

No storage permission is requested: files are chosen through the system picker, so the app
only ever reads what you hand it.

## Accessibility

NightForge targets TalkBack compatibility: every interactive control carries a
spoken label. Feedback from screen-reader users is especially welcome — open an
issue with what you hear and what you expected.

## Roadmap

- ~~Single-file `.m4b` output~~ ✅ shipped in v0.6.0 (Nero `chpl` + Apple chapter track)
- ~~PDF input~~ ✅ shipped in v0.3.0 (born-digital text via PdfBox-Android)
- ~~Screen-off throttling~~ ✅ shipped in v0.3.4 — opt-in **"Keep screen awake while
  forging"** holds the display on (zero-brightness recommended) so ROM gaming clocks
  persist through plugged-in overnight renders.
- ~~In-process engine switching~~ ✅ — a new engine installs without a restart and swaps in place.
- ~~Voice cloning~~ ✅ — ZipVoice with an in-app recorder, importable reference clips and
  optional ASR transcription.
- Per-book engine memory so a part-rendered book cannot silently change narrator ✅
  (v0.9.49) — long renders should also pause automatically when the battery gets low.
- Per-chapter parallelism / NNAPI execution provider experiments.
- An orphaned USB drop-in model path (`listExternal`) has no UI and should either be
  exposed again or removed.

**Won't fix:** MOBI/AZW3 input — proprietary, declining format; convert to EPUB once with Calibre instead (Amazon itself dropped MOBI uploads in 2022). DRM-protected files are out of scope permanently.

## License

**This project is MIT-licensed** — see [LICENSE](LICENSE). Do what you like with the code.

Third-party components keep their own licenses, none changed by bundling: sherpa-onnx (Apache-2.0), Kokoro model weights (Apache-2.0, hexgrad/kokoro), ZipVoice (Apache-2.0), Whisper tiny (MIT), pdfbox-android (Apache-2.0), commons-compress (Apache-2.0). Full details in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
