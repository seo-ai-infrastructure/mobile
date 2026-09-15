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

The published application source is commit [`bd15d606`](https://github.com/seo-ai-infrastructure/mobile/commit/bd15d606d54b54d60975f97f858ef1a4fbc796a3). Its [GitHub Actions build and tests passed](https://github.com/seo-ai-infrastructure/mobile/actions/runs/35028556938).

Local delivered APK: `hooking-probe-1.2-debug.apk`, 9,129,996 bytes, SHA-256 `e57338413e2c12db37f0f05167413255f6cb76cb652c4c63576a43706787f3a3`. CI APKs use the runner's debug signing certificate and may differ from this installed build.

## Device checks

Observed portrait compact call strip and landscape 2:3 call/map panes. Demo preparation survives rotation. Route overlay and OpenStreetMap attribution are visible; unavailable/loading basemap states remain labeled.

Six AndroidX car-host checks passed on the device: templates/trip estimates, navigation ownership and host stop, dial intent, hidden-screen completion, surface replacement/destruction and host viewport bounds. Date-line catalog filtering and malformed/oversized import rejection passed. Direct-console recreation preserves the same prepared scenario at time zero without starting playback.

All 11 final short device checks passed: six car-host/surface checks, two console lifecycle/dial-intent checks, two import/catalog checks, and the background playback/notification/export check. No skipped cases are included in that count.

The separate 600-second background run passed (`OK (1 test)`, instrumentation duration 612.425 seconds). It verified Pause freezes scenario time and delivery, releases the wake lock, Resume works from the notification, the foreground service continues with the UI backgrounded, reopening returns to RUNNING, and Stop releases the wake lock and foreground notification. The exported report validates against `report-v1.schema.json` and ends in STOPPED.

### Measured playback

These totals cover the full playback exercise, including setup and final UI checks around the 600-second background interval. The display remained interactive; this was not screen-off or Doze testing. Instrumentation remained attached throughout the run.

- Delivered: **40,083** channel frames.
- Skipped deadlines: **4**, all in the 50 Hz IMU channel.
- Mean delivery lateness: **0.696 ms**; maximum: **18.184 ms**.
- History: **2,048** retained frames, **38,035** older frames evicted as designed. History eviction is distinct from missed delivery.
- This cloud image lacks SQLite R-tree support. It used the bounded SQLite bounding-box B-tree fallback followed by exact distance filtering.

| Channel | Delivered | Skipped | Mean delay (ms) | Maximum delay (ms) |
| --- | ---: | ---: | ---: | ---: |
| gnss | 607 | 0 | 0.710 | 7.944 |
| wifi | 61 | 0 | 0.864 | 7.944 |
| cell | 607 | 0 | 0.710 | 7.944 |
| ble | 607 | 0 | 0.710 | 7.944 |
| imu | 30,316 | 4 | 0.695 | 18.184 |
| magnetic | 6,064 | 0 | 0.691 | 10.409 |
| pressure | 607 | 0 | 0.710 | 7.944 |
| power | 607 | 0 | 0.710 | 7.944 |
| activity | 607 | 0 | 0.710 | 7.944 |

After a separate force-stop during foreground playback, cold reopening showed **EMPTY · SIMULATED**, with Start disabled and no silent resume. This exercises process termination explicitly; it does not certify every low-memory or vendor kill path.

An earlier long run reached the end of continuous playback but failed in the activity-reopen test harness and was not counted. The corrected harness observes RESUMED state when Android reuses an existing activity; both the short and repeated long exercises passed.

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
