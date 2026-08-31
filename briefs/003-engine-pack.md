# Brief #003 — Engine Pack: Kitten port, catalog order, int8 plumbing removal

**Prerequisite:** Brief #002 merged first (orchestrator will say when). Base: the branch state you are given.
**Work on:** NEW branch `qwen/engine-pack` from the CURRENT `main`. Commit there. **NEVER push. NEVER touch main.**

## Rules
Same as #002: snippets are ground truth; if code at a location differs, STOP that item and report. Re-read after every edit.

## T1 — CATALOG quality order (ModelManager.kt)
Reorder `CATALOG` to exactly this sequence (same entries, new order; entries not listed stay out):

1. `kokoro-fp32` — title "Kokoro 82M · full precision"
2. `kokoro-int8` — title "Kokoro 82M · int8"
3. `kitten-nano-en` (NEW entry, see T2)
4. `piper-lessac`
5. `piper-nl-ronnie`
6. `piper-nl-pim`

**Critical:** `dirFor()` (line ~124) currently uses `CATALOG.first().id` to decide who owns the legacy `kokoro` directory. That MUST become order-independent:
```kotlin
private fun dirFor(opt: ModelOption): File =
    if (opt.id == "kokoro-int8") {
        val stable = File(modelsRoot, opt.id)
        if (stable.isDirectory) stable else File(modelsRoot, "kokoro")
    } else {
        File(modelsRoot, opt.id)
    }
```
**Invariant:** no code may depend on CATALOG list order except display order.

## T2 — Add Kitten engine (Apache-2.0, verified by orchestrator: HF card `KittenML/kitten-tts-nano-0.8-fp32` says `license: apache-2.0`)

Catalog entry (place 3rd per T1):
```kotlin
ModelOption(
    id = "kitten-nano-en",
    title = "Kitten nano · en v0.8",
    subtitle = "25M-param nano TTS, English, Apache-2.0 · ≈30 MB",
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_8-int8.tar.bz2",
    kind = EngineKind.KITTEN,
),
```

ModelManager.kt:
- Line 22 enum: `enum class EngineKind { KOKORO, VITS, KITTEN }` (ONLY these three — no Pocket/Supertonic/ZipVoice in the main app)
- In `companion object` add:
```kotlin
/** Kitten bundles look like Kokoro (voices.bin) but need the KITTEN config:
 *  detect by directory-name prefix first. */
fun bundleKind(dir: File): EngineKind? {
    if (!dir.isDirectory) return null
    if (KokoroEngine.chooseModelFile(dir, preferInt8 = true) == null) return null
    val n = dir.name.lowercase()
    return when {
        n.startsWith("kitten") -> EngineKind.KITTEN
        File(dir, "voices.bin").isFile() -> EngineKind.KOKORO
        else -> EngineKind.VITS
    }
}
fun bundleTokensOk(dir: File): Boolean = File(dir, "tokens.txt").isFile()
```
- Replace every existing check of the shape `KokoroEngine.chooseModelFile(dir, true) != null && File(dir, "tokens.txt").isFile()` in `isValidBundle`/`detect`/download-verify with `isValidBundle(dir)` or `bundleKind(dir) != null && bundleTokensOk(dir)` — keep behavior identical for KOKORO/VITS, add KITTEN via prefix.
- The download-verify `check(...)` inside `downloadModel` (~line 172) must keep working for all three kinds: use `bundleKind(target) != null && bundleTokensOk(target)`.

KokoroEngine.kt:
- Add import `com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig`
- At the top of `load()`, detect family: `val family = ModelManager.bundleKind(modelDir) ?: return "Unrecognized model bundle layout in ${modelDir.absolutePath}"`
- Branch on `family`: existing KOKORO path (voices.bin present, NOT kitten-prefixed) stays as-is; existing VITS path stays; add:
```kotlin
ModelManager.EngineKind.KITTEN -> {
    val modelFile = chooseModelFile(modelDir, preferInt8)
        ?: return "Kitten bundle has no model .onnx"
    val tokens = File(modelDir, "tokens.txt")
    if (!tokens.isFile) return "Kitten bundle is missing tokens.txt"
    val voices = File(modelDir, "voices.bin")
    OfflineTtsModelConfig(kitten = OfflineTtsKittenModelConfig(
        model = modelFile.absolutePath,
        voices = if (voices.isFile) voices.absolutePath else "",
        tokens = tokens.absolutePath,
        dataDir = if (espeak.isDirectory) espeak.absolutePath else "",
    ))
}
```
- Synthesis needs NO change: Kitten uses the plain `generate(text, sid, speed)` path already used by Kokoro (speaker id 0).
- **DO NOT copy anything else from the GBGH branch (no Pocket/Supertonic/ZipVoice/reference-voice code).**

## T3 — Remove preferInt8 plumbing
- `AppSettings.kt`: delete `_preferInt8`, `preferInt8` StateFlow, `setPreferInt8`, and `KEY_INT8` const.
- `ConversionWorker.kt` line ~84: `engine.load(modelDir, settings.numThreads.value, settings.preferInt8.value)` → `engine.load(modelDir, settings.numThreads.value)` (engine default is preferInt8 = true).
- Removal scope: the **user option** is gone. Afterward `grep -rn "preferInt8\|KEY_INT8" app/src/main/java/com/forge/audiobookforge/di app/src/main/java/com/forge/audiobookforge/conversion app/src/main/java/com/forge/audiobookforge/ui` must return NOTHING. Hits inside `tts/` (the `preferInt8` function parameter of `chooseModelFile`/`load`) are ALLOWED — engine-internal plumbing defaulting to true, not a user option.

## Verification (mandatory, paste output)
1. `git diff --stat`
2. Build → BUILD SUCCESSFUL
3. `aapt dump badging` → unchanged identity (78 / 0.8.6 / com.forge.audiobookforge / Audiobook NightForge)
4. `grep -rn "CATALOG\[" app/src/main/java/` → empty
5. `grep -rn "preferInt8\|KEY_INT8" app/src/main/java/` → empty
6. License receipt: after downloading the kitten bundle (or `tar -xjf` the tarball), print the first 3 lines of the `LICENSE` file inside it.

## Report format
Per task DONE/BLOCKED(+what you saw), verification outputs 1–6, commit hash on `qwen/engine-pack`.
