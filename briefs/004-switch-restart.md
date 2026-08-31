# Brief #004 — Multi-engine model bay: Activate button + Switch & Restart

**Base:** branch `main` at `39758c0` (v0.9.2). Work on NEW branch `qwen/switch-restart`. Commit there. **NEVER push. NEVER touch main.**

## Background (read first — this is why, not just what)
sherpa-onnx allows exactly ONE native TTS engine per process (proven: release+re-create SIGSEGVs; two resident engines die in InitFrontend). The app enforces this in `KokoroEngine` — DO NOT touch that file. Today the only way to change engines is re-downloading (the downloader deletes all other engines — a 440 MB "switch"). This brief makes engines COEXIST ON DISK (storage only, never two in RAM) and adds one-tap activation with an automatic app restart.

## Rules
Snippets are ground truth; if code at a location differs, STOP that item and report what you see. Re-read after every edit.

## T1 — ModelManager.kt: engines coexist on disk
1. In `download()`, DELETE this cleanup block entirely:
```kotlin
            // Verified: now enforce one-model-at-a-time.
            CATALOG.filter { it.id != option.id }.forEach { other ->
                File(modelsRoot, other.id).deleteRecursively()
            }
            if (option.id != "kokoro") File(modelsRoot, "kokoro").deleteRecursively()
```
2. In its place, write:
```kotlin
            // Multi-engine model bay: engines coexist on disk (storage only —
            // the engine runtime still allows one per process). The freshly
            // installed engine becomes the active choice immediately.
            prefs().edit().putString("active_model_path", target.absolutePath).apply()
```
3. Update the success notice (later in `download()`): `"${option.title} installed — previous model removed"` → `"${option.title} installed and activated"`.

## T2 — ModelManager.kt: detect() must recognize catalog engines activated via pref
In `detect()`, the pref branch (line ~82-94) currently returns `optionId = "local:${dir.name}"`, which breaks the Settings "installed" check. Replace that branch with:
```kotlin
        prefs().getString("active_model_path", null)?.let { path ->
            val dir = File(path)
            if (isValidBundle(dir)) {
                val catalogOpt = CATALOG.firstOrNull { dirFor(it) == dir }
                return ModelUi(
                    ready = true,
                    modelDir = dir,
                    optionId = catalogOpt?.id ?: "local:${dir.name}",
                    int8Available = File(dir, "model.int8.onnx").isFile(),
                )
            } else {
                prefs().edit().remove("active_model_path").apply()
            }
        }
```
Also add `installedIds` to `ModelUi` (next to the existing fields, with the same default style):
```kotlin
        val installedIds: List<String> = emptyList(),
```
and populate it in BOTH the pref-branch and catalog-branch `ModelUi(...)` constructions plus the fallback return, by computing once at the top of `detect()`:
```kotlin
        val installed = CATALOG.filter { isValidBundle(dirFor(it)) }.map { it.id }
```
then `installedIds = installed,` in each ModelUi construction. (The fallback return at the bottom gets `installedIds = installed,` too.)

## T3 — ModelManager.kt: public activation API
Add these two small public functions (anywhere in the class body, above `detect()` is fine):
```kotlin
    /** Storage location for a catalog engine (exposes dirFor for the UI). */
    fun catalogDir(option: ModelOption): File = dirFor(option)

    /** Point the active choice at an already-installed catalog engine. */
    fun activateCatalog(option: ModelOption) {
        prefs().edit().putString("active_model_path", dirFor(option).absolutePath).apply()
        _ui.value = detect()
    }
```

## T4 — AppRestart.kt (NEW FILE): clean process relaunch
Create `app/src/main/java/com/forge/audiobookforge/di/AppRestart.kt`:
```kotlin
package com.forge.audiobookforge.di

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock

/**
 * Restarts the app process. Used when switching TTS engines: the engine
 * choice is persisted BEFORE calling this, and the fresh process loads it.
 * Hand-rolled ProcessPhoenix pattern — no third-party dependency.
 */
object AppRestart {
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        val pi = PendingIntent.getActivity(
            context, 1001, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 100, pi)
        Runtime.getRuntime().exit(0)
    }
}
```

## T5 — SettingsScreen.kt: Active / Activate / Get per engine
Replace the catalog `when` block (lines ~82-91) with:
```kotlin
                        val active = modelUi.optionId == opt.id
                        val installed = opt.id in modelUi.installedIds
                        when {
                            active -> Text(
                                "Active",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            installed -> TextButton(
                                onClick = { confirmActivate(opt) },
                            ) { Text("Activate") }
                            !modelUi.downloading -> TextButton(
                                onClick = { scope.launch { container.models.download(opt) } },
                            ) { Text("Get") }
                        }
```
(Above the `@Composable` containing this, or in the same file scope, add the confirm helper — adapt names to what exists in the file for scope/snackbar access):
```kotlin
    private fun confirmActivate(opt: com.forge.audiobookforge.tts.ModelOption) {
        val engine = container.kokoroEngine
        val loaded = engine.loadedDir
        val needsRestart = loaded != null && loaded != container.models.catalogDir(opt)
        if (!needsRestart) {
            container.models.activateCatalog(opt)
            return
        }
        // Engine already warm with a different model: save choice + relaunch.
        container.models.activateCatalog(opt)
        com.forge.audiobookforge.di.AppRestart.restart(container.context)
    }
```
If `container.context` does not exist, check how other code in this file obtains a Context (e.g., `LocalContext.current`) and use that instead — report which one you used.

## Invariants (hard rules)
- **KokoroEngine.kt: ZERO changes.** ConversionWorker.kt: ZERO changes. No new Gradle dependencies.
- Only ONE native engine may ever load per process — the restart path exists precisely to guarantee that.
- The same-id replacement path in `download()` (`target.deleteRecursively()` before rename) MUST stay.
- Downloads must NOT delete other engines anymore (T1 removes exactly that).

## Verification (mandatory, paste outputs)
1. `git diff --stat`
2. Build → BUILD SUCCESSFUL
3. `aapt dump badging` → `com.forge.audiobookforge`, versionCode 81, versionName 0.9.2, label `Audiobook NightForge` (unchanged — version bump happens at merge)
4. `grep -c "deleteRecursively" app/src/main/java/com/forge/audiobookforge/tts/ModelManager.kt` → must be ≤ 3 (replacement paths only, no catalog-wide deletion)
5. `grep -n "FLAG_IMMUTABLE" app/src/main/java/com/forge/audiobookforge/di/AppRestart.kt` → present
6. `grep -n "KokoroEngine.kt" app/src/main/java/com/forge/audiobookforge/tts/ModelManager.kt` → no modifications to KokoroEngine (`git diff main --stat` must not list it)

## Report format
Per task DONE/BLOCKED(+what you saw), verification outputs 1–6, commit hash on `qwen/switch-restart`.
