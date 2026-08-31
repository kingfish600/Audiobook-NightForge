# Brief #002 — UI Cleanup Pack (8 changes)

**Base:** branch `main` at `95c0bc7` (v0.8.6) in `/home/kingfish600/workspace/projects/Audiobook-NightForge`
**Work on:** NEW branch `qwen/ui-cleanup` (create from main). Commit there. **NEVER push. NEVER touch main.**

## Rules
- Line numbers below are from v0.8.6 and may drift by a few lines. If the code at a location does not look like the snippet, **STOP that item and report what you actually see. Never guess.**
- Every edit: re-read the file section afterward and confirm the change landed.
- No functional rewrites. These are surgical UI edits.

## Tasks

### T1 — App name fix
`app/src/main/java/com/forge/audiobookforge/ui/ForgeRoot.kt` line 38:
`title = { Text("Audiobook Forge") },` → `title = { Text("Audiobook NightForge") },`

### T2 — Starting screen: only Kokoro full precision
`app/src/main/java/com/forge/audiobookforge/ui/screens/LibraryScreen.kt`, composable `ModelBanner()` (lines ~190–230):
1. Line 212: `CATALOG[1]` → `CATALOG.first { it.id == "kokoro-fp32" }`
2. DELETE the entire `OutlinedButton` block (lines ~216–220, "Lite version for modest phones")
3. DELETE the paragraph "Both sound great — full precision renders fastest..." (lines ~222–227)
4. Line ~201 body copy: replace `"It lives inside the app afterwards — no internet needed from then on. The Lite engine is smaller and faster; the standard one sounds better."` with `"It lives inside the app afterwards — no internet needed from then on. The recommended engine is best quality. More voices (Lite, Piper, Dutch) live in Settings."`

**Invariant:** after all edits, `grep -rn "CATALOG\[" app/src/main/java/` must return NOTHING. Positional catalog indexing is banned forever.

### T3 — Remove Day view option
`app/src/main/java/com/forge/audiobookforge/ui/screens/SettingsScreen.kt`:
1. Lines ~238–247: delete the whole `androidx.compose.material3.FilterChip(` block whose `selected = forgeScreen == "day"` and whose label is `Text("☀ Day view")`.
2. Line ~272: delete the `"day" -> "Day view: screen stays on ..."` line from the `when`.
3. `grep -rn '"day"' app/src/main/java/` — if any rendering branch (a `when` consuming the value) handles `"day"` in the forge screen code, delete that branch too (falls through to Off). Storage code in `AppSettings` stays.

### T4 — WAV chip label
`SettingsScreen.kt` line ~320: `Text("WAV · lossless")` → `Text("WAV")`

### T5 — Remove Apple-style toggle row and Drop-in models section
`SettingsScreen.kt`:
1. Delete the whole settings row containing `Text("Apple-style chapter track (.m4b)")` (~lines 100–106): the Row with its Switch and the long description Text below it.
2. Delete the whole "Drop-in models (USB)" section starting at the header `Text("Drop-in models (USB)", style = MaterialTheme.typography.titleSmall)` (~line 114) through the end of that section's content block.

**Invariant:** do NOT modify `AppSettings.appleChapters`, `BookDetailScreen` export logic, or `ModelManager` external-model code. UI rows only — plumbing stays dormant.

### T6 — Remove prefer-int8 toggle (UI part)
`SettingsScreen.kt`: delete line ~37 (`val int8 by container.settings.preferInt8.collectAsState()`) and the whole row with `Text("Prefer int8 weights (smaller download; often *slower* than full on flagship chips)")` (~lines 209–212). (AppSettings plumbing is removed in Brief #003 — leave it for now.)

### T7 — Import chip: mention PDF
`LibraryScreen.kt` line 78: `Text("Import EPUB / TXT")` → `Text("Import EPUB / TXT / PDF")`

### T8 — Forge-while-charging: Settings only
`BookDetailScreen.kt` line ~193: delete the whole menu item block containing `"Forge only while charging"`. The identical row in `SettingsScreen.kt` line ~216 MUST STAY.

**Invariant check:** `grep -rn "while charging" app/src/main/java/` afterward → only `SettingsScreen.kt` and the `ConversionWorker.kt` doc comment.

## Verification (mandatory, paste output in report)
1. `git diff --stat` (list every changed file)
2. Build: `gradle assembleRelease` (your toolchain) → must print BUILD SUCCESSFUL
3. `aapt dump badging app/build/outputs/apk/release/app-release.apk | grep -E "^package:|application-label"` → must show `com.forge.audiobookforge`, versionCode 78, versionName 0.8.6, label `Audiobook NightForge`
4. `grep -rn "CATALOG\[" app/src/main/java/` → empty
5. `grep -rn "Audiobook Forge" app/src/main/java/` → empty

## Report format
- Per task: DONE / BLOCKED(+what you saw)
- Outputs of verification steps 1–5
- Commit hash on `qwen/ui-cleanup`
