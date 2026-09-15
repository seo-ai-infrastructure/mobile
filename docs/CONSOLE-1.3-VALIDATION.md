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

## Follow-up device verification

A 600-second background run passed on the selected DuoPlus Pixel 6 with this
exact 1.3 APK. Including its setup and notification checks, it ran 611.903 seconds:
46,170 frames delivered, one omitted IMU sample, maximum scheduler lateness
16.205641 ms and mean 0.651982 ms. History retained 2,048 frames and evicted
44,122. These timestamps measure entry to the dispatcher, not view rendering.
The instrumentation process remained active. Pause/Resume through notification
actions, UI reopening, Stop, wake-lock release and JSON report validation passed.

Six AndroidX Auto fake-host/surface tests plus two import/index tests passed.
The private Flamingo import retained source metadata, preparing 452 of 454
catalog records for the route. This phone lacks SQLite R-tree, so the reported
`sqlite_bbox_btree` fallback performed bounding-box and exact-distance filtering.

A screen-off run was attempted but correctly failed its precondition: the
DuoPlus device remained interactive after Sleep. Background measurements are
not screen-off/Doze evidence. Android Auto is absent on this phone; the local
Desktop Head Unit runs but projection needs a compatible phone host. No battery
optimization exemption was requested and no real call was made.

A deterministic follow-up test exposed a Pause/completion race in this release:
Pause could invalidate queued foreground cleanup after the route completed.
This is fixed and regression-tested in 1.3.1.

APK SHA-256:

```text
54fca1e62038f745351c16501a799405c6a3f5b3582ff578dbb39c856744011d
```
