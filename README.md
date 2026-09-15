# Hooking

An Android multisensor simulator with a phone call/map console and Android Auto navigation projection. Install the app named **Hooking**; scenario values are visibly labeled and stay inside its own process.

## Quick start

1. Download the debug APK from a successful [Android build](https://github.com/seo-ai-infrastructure/mobile/actions/workflows/android.yml), or build locally below.
2. Open **Hooking → Load demo → Start**, allowing playback notifications. Import your own scenario with **Import JSON**.
3. Choose **Open call & map console** for the route map and phone controls. Expand **Keypad** to request the default phone role or enter a number. A dial intent only prefills; a real call requires pressing **Place real call**.

Portrait uses a map and compact call strip; landscape uses two panes. Android Auto draws navigation templates and its own calling experience. The map uses visible OpenStreetMap tiles, with attribution and a clearly labeled route-only fallback when tiles are unavailable. Scenario playback itself requires no network or cloud service.

## Build

Use JDK 21 and an Android SDK containing platform 34 and build-tools 34.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then:

```sh
./plugin/build.sh
# APK: plugin/app/build/outputs/apk/probe/debug/app-probe-debug.apk
adb install -r plugin/app/build/outputs/apk/probe/debug/app-probe-debug.apk
```

The standalone project needs no module token, DuoPlus loader, SDK bridge JAR, native library, or API key. Debug builds are for local testing. Build unsigned release output with `./plugin/build.sh :app:assembleProbeRelease`; configure your own signing separately. Debug APKs built on different machines may have different signing certificates.

## Scenario tools

Python 3.10 or later:

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt -r requirements-dev.txt
.venv/bin/python scenario_cli.py build \
  --route examples/probe/demo-route.geojson \
  --fixtures examples/probe/demo-fixtures.json \
  --output demo-scenario.json
.venv/bin/python scenario_cli.py validate --scenario demo-scenario.json
.venv/bin/python scenario_cli.py preview --scenario demo-scenario.json --limit 5
```

The versioned [scenario schema](schemas/scenario-v1.schema.json) specifies units, trajectory, altitude references, radio catalogs, per-field provenance, satellite fixtures, connection transitions and activity intervals. The [report schema](schemas/report-v1.schema.json) specifies timing, omissions and bounded frame history. The 660-second bundled demo contains driving, stops, walking, fix loss and power transitions, using fictional/example data.

## Documentation

- [Normal Android packaging](docs/PACKAGING.md)
- [Dispatcher and consumer routing](docs/DISPATCHER.md)

- [Scenario import, geodesy, sensors and limits](docs/SCENARIOS.md)
- [Clock domains and report fields](docs/CLOCKS-AND-REPORTS.md)
- [Phone console and real call controls](docs/CONSOLE.md)
- [Android Auto templates and host requirements](docs/ANDROID-AUTO.md)
- [Map rendering and tile policy](docs/MAP.md)
- [1.3.1 validation and device limitations](docs/CONSOLE-1.3.1-VALIDATION.md)

## Verification

```sh
./plugin/build.sh :app:assembleProbeDebug :app:testProbeDebugUnitTest :app:lintProbeDebug
.venv/bin/python -m unittest discover -s tests
./plugin/build.sh :app:assembleProbeDebugAndroidTest
```

Device tests include explicit opt-in private-import and screen-off cases; see the validation guide before running them. AndroidX fake-host tests verify Auto template contracts; they do not replace a head-unit test. Real calling requires a supported Telecom account and user-selected phone role. No call is made by the tests or by map playback.

Imported survey files, credentials and device-specific artifacts stay outside this repository. The normal Android APK contains no hook diagnostics, bridge SDK, native hook library or module configuration. The optional legacy DuoPlus module is a separate binary and is absent from this published source. GNSS raw measurements, third-party telemetry injection and integrity bypasses are outside the simulator.
