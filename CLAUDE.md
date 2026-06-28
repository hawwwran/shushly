# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Shushly is a single-module Android app (Kotlin, Jetpack Compose) that runs an **app-owned smart quiet mode**: it silences ordinary notifications through a user-authorised Do-Not-Disturb rule, then plays a **sound** to re-surface only the notifications an AI judges important. It posts no notification of its own (sound-only) and never edits the user's global DND.

Package `com.hawwwran.shushly` (debug installs as `.debug`). minSdk 31, targetSdk 35, compileSdk 36 — 36 only to satisfy current AndroidX; targetSdk stays 35 to match the test device's Android 15 runtime behaviour.

## Build, test, run

The Gradle daemon is pinned to JDK 21 in `gradle.properties` (the machine default JDK is unsupported by this AGP line), so `./gradlew` works without setting `JAVA_HOME`.

- Build debug APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- All unit tests (pure JVM, no device): `./gradlew :app:testDebugUnitTest`
- One class or method: `./gradlew :app:testDebugUnitTest --tests "com.hawwwran.shushly.service.listener.NotificationPipelineTest"` (append `.methodName` for a single case)
- Install on the test device: `adb -s 10ACAF0ULK0027Y install -r app/build/outputs/apk/debug/app-debug.apk`

### On-device verification

DND, listener survival, and audio routing are **not** unit-tested; they are verified by hand over adb on a vivo V2145 (Android 15 / FunTouch, serial `10ACAF0ULK0027Y`). Hard-won notes:

- Confirm the quiet-mode state objectively: `adb shell dumpsys notification | grep mZenMode` (`ZEN_MODE_IMPORTANT_INTERRUPTIONS` = on, `ZEN_MODE_OFF` = off).
- Alarm volume is stream 4: `adb shell cmd audio set-volume 4 <n>` (alerts ride the alarm lane; keep it > 0).
- Get tap coordinates from `adb shell uiautomator dump` + the element's `bounds=`; screenshots are vertically scaled, so don't eyeball them. Capture with `adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png`.
- logcat is flaky on FunTouch after a process restart; treat the in-app **Decision history** screen as the source of truth for what the pipeline decided.
- `adb cmd statusbar click-tile` is a no-op on this build; test the Quick Settings tile by tapping the real panel.

## Architecture

Two cooperating halves plus an AI layer.

**1. System-level quiet (DND).** `ZenRuleQuietModeController` owns one `AutomaticZenRule`, whose declared owner component is `ShushlyConditionProviderService`. Enabling activates Shushly's own rule and never edits the user's global DND. `SmartQuietModeManager` is the single entry point for toggling, so the Home toggle and the Quick Settings tile can't drift. Whether the rule is *active* is derived from the pure `desiredZenActive(master, activeWhenLocked, inUse)` and applied by `reconcile()` (Mutex-serialised; it never mutates the persisted master flag). Lock/unlock transitions are reconciled by a runtime `BroadcastReceiver` in `ShushlyApp` (SCREEN_ON/OFF/USER_PRESENT) and once at process start.

**2. Observe and re-alert.** `ShushlyNotificationListenerService` hands each posted notification to `NotificationPipeline.processExtracted`, an ordered chain of guards (most record a decision to history). The order is load-bearing:

1. static/persistent drop — ongoing or `!StatusBarNotification.isClearable()`; checked first and the only branch that records nothing
2. learn the app (`SeenAppsRepository`, feeds the picker's "most used")
3. quiet-mode-off, then active-when-locked-and-in-use
4. protected source (`ProtectedSourcePolicy`: telephony/clock/auth/wallet packages, CALL/ALARM categories, OTP shape)
5. always-alert apps (sound immediately, AI bypassed)
6. group-summary, eligibility, usable-text, duplicate/cooldown guards
7. classify via AI, then alert iff `decision == ALERT && confidence >= 0.80` (`NotificationPipeline.ALERT_THRESHOLD`)

An alert (AI or always-alert) calls `CriticalAlertSounder` and is gated by a global anti-storm backstop (`DedupeRateLimiter`). It is never a posted notification.

**3. AI (direct, bring-your-own-key).** `AiClassifier` is bound to `RoutingAiClassifier`, which per call selects `DirectAiClassifier` when a key is present **and** `aiConnection.isVerified` (then `OpenAiProvider` calls OpenAI directly with the user's key from `ApiKeyStore`, Keystore-backed); otherwise the deterministic `FakeAiClassifier` in debug, or it throws in release so the pipeline fails safe to silent. There is **no relay/server** (it was removed; see `secret.plans/decisions.md` D12). `BuildConfig.USE_FAKE_CLASSIFIER` is true in debug, false in release.

### Invariants that explain non-obvious code

- **Alarm-lane.** The zen policy is `disallowAllSounds()` + `allowAlarms` (plus `allowCalls`/`allowRepeatCallers`), so it mutes every audio lane except the alarm lane. Shushly's own tone **and** haptic must be emitted on `USAGE_ALARM` (`CriticalAlertSounder`) or its own DND rule would silence them. Don't change the usage to NOTIFICATION.
- **Sound-only.** Shushly posts no notification for an alert; the source app's silenced notification already sits in the shade, and the sound is the cue to look.
- **Fail-safe to silent.** Every failure path resolves to SILENT, never a spurious alert. DB and playback failures are caught so they can't break the pipeline.
- **Privacy-minimal.** Decision history (Room) stores enums and metadata only, never raw notification title/body.

### DI and the system-service bridge

Hilt, with `@HiltAndroidApp` on `ShushlyApp` and `@AndroidEntryPoint` only on `MainActivity` + ViewModels. System-instantiated components (the listener, the QS tile, `HistoryPurgeWorker`, `ShushlyApp` itself) cannot use constructor injection; they reach singletons through `EntryPointAccessors` + `ServiceEntryPoint`. Repositories and services are interfaces bound to impls with `@Binds` in `di/AppModule` — this is what lets `NotificationPipeline` run in a plain-JVM test. Add a new pipeline collaborator as an interface so it can be faked.

### Layout

- `core/model` — data classes and enums (`Decision`, `DecisionReasonCode`, `ExtractedNotification`, `AppSettings`, …)
- `core/data` — repositories (interface + impl), the Room DB, the encrypted key store. Settings and seen-apps are DataStore; history is Room (30-day purge by `service/work/HistoryPurgeWorker` via WorkManager)
- `core/policy` — `ProtectedSourcePolicy`
- `service/{listener,ai,quietmode,alerting,tile,work}` — the runtime
- `feature/*` — a Compose screen + ViewModel per area (home, history, picker, settings, onboarding, about, aiconnection); the nav graph lives in `MainActivity`

## Testing approach

Pure-JVM only (`app/src/test`); no Robolectric or instrumented tests. `testOptions.unitTests.isReturnDefaultValues = true` lets the `android.jar` stubs (e.g. `android.util.Log`) no-op so pipeline logic runs without a device. The pipeline tests use recording fakes (`PipelineTestDoubles.kt`) for the I/O collaborators while keeping the pure ones (extractor, eligibility, dedupe) real.

## Conventions

- UI copy says **Shushly**, never "SiftPing" (the spec's internal codename).
- The authoritative spec is local-only, gitignored under `secret.plans/`: `siftping_implementation_plan.md` (full spec), `plans-progress.md` (running state — read first), `decisions.md` (decision log). Code comments cite its `§x.y` sections.
- Commits use identity `hawwwran` on branch `master`, with **no AI / co-author trailer**. `secret.plans/` is gitignored; never stage it.
