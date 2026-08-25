# Building NightForge from scratch

No special hardware, no private keys, no secrets in the repo. Any machine with
the standard Android toolchain can build and sign a working APK.

## Toolchain

- JDK 17 (Temurin or any)
- Android SDK: platform 34, build-tools 34.x
- Gradle 8.7+ (or the wrapper if present)

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
git clone https://github.com/kingfish600/Audiobook-NightForge
cd Audiobook-NightForge
gradle assembleRelease          # or ./gradlew if wrapper is used
# output: app/build/outputs/apk/release/app-release.apk
```

## Signing

Release builds fall back to the debug keystore at `$ANDROID_USER_HOME`
(default `~/.android/debug.keystore`) so sideloading "just works" and updates
install over each other. For Play Store upload, configure your own keystore:

```kotlin
// app/build.gradle.kts — signingConfigs.release
```

## Making changes safely

1. Read `ARCHITECTURE.md` first; respect its six invariants.
2. After ANY scripted edit to source files, re-read the file from disk and
   assert your change is present. Trust builds + git diffs, not tool output.
3. Bump versionCode every release (it once froze at 29 for many releases —
   verify with `aapt dump badging` before committing).
4. Run `aapt dump badging app/build/outputs/apk/release/app-release.apk` after
   every successful build.

## Producing a Play Store bundle

```bash
gradle bundleRelease
# output: app/build/outputs/bundle/release/app-release.aab
```

Upload needs: a Google Play developer account (one-time fee), the AAB,
a privacy policy URL (`PRIVACY.md` in this repo — the app collects nothing),
and Play Console's data-safety form (answers: no data collected, no sharing).

## Where a smaller local LLM can help maintenance

The design decisions are already recorded (ARCHITECTURE.md invariants, commit
messages as narrative). Maintenance-mode tasks that do NOT require frontier
scale: version bumps, dependency updates, adding a model to ModelManager.CATALOG,
string/UI tweaks following existing patterns, running the release checklist.
Architecture-level changes are rarer and benefit from stronger models — but by
then you'll have the docs above, which is most of the conversation anyway.
