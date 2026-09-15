# Hooking 1.3.1 validation — 2026-09-15

## Release change

Pause at the instant of automatic completion now preserves the completed state
and removes the foreground notification under the latest run generation. A
normal paused run retains its Resume/Stop notification. The shared dispatcher,
sensor models, and phone/Auto routing are unchanged from 1.3.

## Build and regression checks

- Version code 5, version name 1.3.1; installed in place on the selected Pixel 6.
- Normal `:app:assembleProbeDebug` and Android test APK assembly passed.
- 91 Java unit tests and 70 Python compiler/import/schema tests passed.
- Android lint: zero errors, 28 warnings.
- APK inspection found no native library, module config, bridge, Entry, FixFile,
  or HookDiagnosticsActivity. The normal build does not require a module token.
- A separate source export also built, tested and linted successfully with
  `plugin/dplus`, `dpbridge.jar`, and local SDK/token configuration absent.
- Eleven on-device tests passed without skips: two completion tests, three
  console tests, and six AndroidX Auto fake-host/surface tests.
- The deterministic Pause/completion regression failed against the previous
  1.3 APK and passed against 1.3.1. Latches selected the exact ordering where
  completion cleanup is queued and a late Pause invalidates its generation.
- Completion checks cover a 1,050 ms route ending between normal pose slots,
  exact terminal position, released wake lock/foreground status, preserved
  completion message, and no subsequent samples.
- Console tests cover immediate movement, pause/resume/stop, activity recreation,
  and dial-intent prefill. They do not make calls or change calling permissions.

## Runtime and environment limits

The selected DuoPlus Pixel 6 does not enter screen-off state after the Sleep
command. Android Auto is not installed on it. Desktop Head Unit 2.0 is available
on the laptop, but a phone with Android Auto is needed to verify projection.
Fake-host tests do not prove head-unit compatibility. No battery optimization
exemption, real call, default-dialer change, or third-party sensor injection was
used during verification.

The earlier exact-APK 1.3 endurance results remain documented separately in
[1.3 validation](CONSOLE-1.3-VALIDATION.md).

APK SHA-256:

```text
1da8e6e050c38e78c0ae9a84ce24b120d65292b0c42e06f6e6242f9429492e32
```
