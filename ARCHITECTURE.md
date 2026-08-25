# Architecture — Audiobook NightForge

A map for maintainers (human or machine). Read top to bottom; each layer only
talks to the ones above it.

## The one-paragraph version

Books are imported by format parsers into `Book`/`Chapter` records. Forging is
a WorkManager job (`ConversionWorker`) that walks chapters, synthesizes audio
via sherpa-onnx (`KokoroEngine`), and writes per-chapter files. Playback uses
Media3. Everything is offline; the only network use is explicit voice-model
downloads.

## Module map

```
di/AppContainer        composition root; owns all singletons, reached via
                       (application as ForgeApp).container (NOT CompositionLocal —
                       standalone activities have no provider tree)
data/model             Book, Chapter, ChapterStatus; persisted alongside audio files
data/parser            EpubParser / TxtParser / PdfParser -> ParsedBook(title, chapters)
                       PdfParser.sanitize() strips PDF font-map garbage BEFORE TTS
                       (ligatures, thorn/eth, zero-widths) — prevents "PDF lisp"
tts/ModelManager       catalog downloads (k2-fsa tar.bz2), verify-before-replace,
                       one-model-at-a-time, plus the USB DROP-IN BAY:
                       getExternalFilesDir(null)/models/<Name>/ with *.onnx +
                       tokens.txt (+voices.bin for Kokoro-style). Engine family is
                       detected by layout (voices.bin present => KOKORO else VITS)
tts/KokoroEngine       wraps com.k2fsa.sherpa.onnx JNI; chooseModelFile(prefers int8);
                       load() takes a directory — same call for catalog & drop-ins
conversion/            ConversionController: StateFlow<ConversionState>
                       ConversionWorker: doWork() promotes its own thread to
                       THREAD_PRIORITY_URGENT_AUDIO (WorkManager starts threads at
                       BACKGROUND priority — do not remove this)
                       Unique work name "convert_<bookId>", policy REPLACE.
                       Foreground service type specialUse (dataSync caps at 6h/day).
audio/                 AacChapterWriter (MediaCodec AAC-LC -> .m4a per chapter),
                       BookExporter (per-chapter export: MediaStore or SAF tree),
                       M4bExporter (lossless AAC remux -> single .m4b) +
                       ChapterBox (Nero chpl atom injector; requires moov LAST box,
                       which MediaMuxer guarantees; failure message carries full box
                       layout + head hexdump for remote diagnosis)
ui/screens             LibraryScreen, BookDetailScreen, SettingsScreen
ui/ForgeNightActivity  black overnight screen. Lives in ITS OWN TASK
                       (taskAffinity="", launchMode=singleTask) so the app icon
                       NEVER lands on it — it only ever appears after an explicit
                       Start press. Patience timer re-arms on every state change;
                       exits after 30s silence or book-scoped Failed.
CrashRecorder          uncaught Java exceptions -> external files last-crash.txt;
                       Settings exposes a Share button only when the file exists
MainActivity           day-view keeps screen on while Running (FLAG_KEEP_SCREEN_ON)
```

## Invariants worth more than the code around them

1. **Never let rendering depend on UI.** Worker failures must leave valid
   chapter files; the night screen finishing early must never stop a forge.
2. **Verify before replace** on model installs — a failed download must never
   strand the user without a working engine.
3. **Every scripted edit ends with re-read-and-assert on disk.** This project
   shipped four ghost patches before that rule existed (v0.3.9 x2, v0.3.10,
   v0.6.6). Do not become the fifth.
4. **Badging before commit**: `aapt dump badging` must show the intended
   versionCode/versionName, else the release is fiction.
5. **Mixed-format books** are legal but block single-file export; the UI must
   say WHY rather than hide silently.
6. **R8 rules**: keep `com.k2fsa.sherpa.onnx.**`, `com.tom_roush.pdfbox.**`;
   `-dontwarn com.gemalto.jp2.**`.

## Releasing

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`
2. `gradle assembleRelease` (debug keystore fallback signs it for sideloading)
3. Verify badging. Commit, tag vX.Y.Z, push tags from your clone
4. APK filename carries the version; Settings footer must match installed build

## Performance facts measured on Snapdragon 8 Elite (see README table)

fp32 beats int8 here (ARM int8 kernel overhead); RTF ~0.44 cold, ~0.6 sustained;
overnight throttling defeated by holding foreground status (Night Forge),
sustaining 0.62 @ 8 threads past one hour. Always benchmark quantization on the
target chip instead of assuming smaller = faster.
