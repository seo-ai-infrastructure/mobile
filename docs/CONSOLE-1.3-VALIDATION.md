# Hooking 1.3 validation — 2026-09-15

Built as the normal Android `:app` / `probeDebug` variant in a clean source export
with the DuoPlus module, SDK bridge, module token and local SDK configuration absent.

## Results

- Build and Android test APK assembly passed with JDK 21 and SDK 34.
- 91 Java unit tests passed; 70 Python compiler/import/schema tests passed.
- Android lint: 0 errors, 28 warnings.
- APK inspection: no native `.so`, module `config.json`, bridge/Entry/FixFile
  classes, or HookDiagnosticsActivity. `HOOK_MODE=false`.
- Installed in place over 1.2 on the selected Pixel 6; versionCode 4/versionName 1.3.
- Three on-device console tests passed, none skipped: immediate map movement,
  Pause/Resume/Stop freeze and advance, prepared scenario retention on recreation,
  and dial-intent prefill without a call or permission/role changes.
- Movement test observed more than 5 m of travel in the demo's first seconds.
  It verified increasing published pose sequences and 100 ms scene slots, then
  frozen pose time and sequence after Pause and Stop.

Dispatcher regression coverage includes shared GPS/NMEA/status/pose epochs, fix
loss, zero-noise IMU, late-frame omissions, pause clock rebasing, non-grid route
completion, and no reuse of an omitted terminal sequence. Explicit steps and
STILL/movement conflicts are validated on both the Java and Python import paths.

This release changes scheduling and packaging. The older ten-minute measurements
in CONSOLE-VALIDATION.md apply to 1.2, not a new 1.3 endurance run. Screen-off Doze,
a physical Auto head unit and real calls were not newly tested. No battery
optimization exemption was requested and no real call was made.

APK SHA-256:

```text
54fca1e62038f745351c16501a799405c6a3f5b3582ff578dbb39c856744011d
```
