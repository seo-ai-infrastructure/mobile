# Hooking 1.2 validation

Validation date: 2026-09-15. Android package: `com.example.duoplus_probe`, versionCode 3, versionName 1.2. Device: DuoPlus Pixel 6 cloud phone, Android 15 / API 35. This is a sideloaded diagnostic build.

## Build and contracts

- Clean Android build: passed with JDK 21, Gradle 8.7, AGP 8.6, compile/target SDK 34.
- Probe JVM suite: 75 tests passed; no failures or skips, including small-viewport projection and queued tile cooldown regressions.
- Python suite and formal scenario/report contracts: 56 tests passed.
- Existing local module suite: 26 tests passed. That optional loader/bridge module is not part of this standalone publication.
- Lint: zero errors, 29 warnings. Warnings include pinned dependency versions, compatibility/deprecation guidance, static application-context ownership and layout recommendations. No lint baseline suppresses failures.
- The separately exported standalone source builds and runs its probe tests/lint without the legacy module, token, bridge SDK, private survey data or local project configuration.
- Device test APK excludes Robolectric desktop resources. The AndroidX fake-host tests use actual Android framework objects and never call the JVM-only notification helper.

## Device checks

Observed portrait compact call strip and landscape 2:3 call/map panes. Demo preparation survives rotation. Route overlay and OpenStreetMap attribution are visible; unavailable/loading basemap states remain labeled.

Six AndroidX car-host checks passed on the device: templates/trip estimates, navigation ownership and host stop, dial intent, hidden-screen completion, surface replacement/destruction and host viewport bounds. Date-line catalog filtering and malformed/oversized import rejection passed. Direct-console recreation preserves the same prepared scenario at time zero without starting playback.

The initial dial-intent check exposed a collapsed keypad and was fixed. The harness now tracks activity identity across changed dial intents. An initial 600-second background run passed its continuous-playback checks but failed when the harness waited for a new Activity creation while Android reused an existing task; it did not save final metrics. The corrected harness observes actual RESUMED state and saves a timing checkpoint before the UI check. Final measured results will be recorded here after the repeated checks finish.

The default dialer remains `com.android.dialer`; `CALL_PHONE` remains ungranted. Tests do not place calls or request a phone-role change.

## Reproduce

```sh
./plugin/build.sh :probe:assembleDebug :probe:assembleDebugAndroidTest :probe:testDebugUnitTest :probe:lintDebug
python -m unittest discover -s tests
adb install -r plugin/probe/build/outputs/apk/debug/probe-debug.apk
adb install -r plugin/probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class com.example.duoplus_probe.car.CarProjectionTest,com.example.duoplus_probe.car.CarSurfaceRendererTest,com.example.duoplus_probe.console.ConsoleDeviceTest \
  com.example.duoplus_probe.test/androidx.test.runner.AndroidJUnitRunner
```

An ordinary full device suite skips private-file import and the extended playback exercise. To opt into ten minutes of background playback plus notification Pause/Resume/Stop, use:

```sh
adb shell am instrument -w -r \
  -e class com.example.duoplus_probe.PlaybackDeviceTest#playbackSurvivesBackgroundAndNotificationControls \
  -e runPlayback true -e screenMode background -e durationSeconds 600 \
  com.example.duoplus_probe.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.example.duoplus_probe cat files/instrumentation-evidence.json > device-evidence.json
adb exec-out run-as com.example.duoplus_probe cat files/instrumentation-report.json > device-report.json
```

Use `-e screenMode off` to require actual screen-off state; the test fails if Android still reports the display interactive. No battery-optimization exemption is requested. The selected cloud image previously remained interactive after the sleep command, so a background result must not be described as screen-off/Doze verification.

## Limits

Real calls, role selection, carrier service, Bluetooth call audio, a physical head unit/DHU, and sustained screen-off timing remain unverified. Android Auto is not installed on the selected cloud phone. Host contract tests do not establish head-unit compatibility or Play distribution approval. The map is a scenario preview, not road routing or driving guidance. No test result is a guarantee of hard real-time delivery or absence of all defects.
