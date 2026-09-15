# Android Auto probe projection

`ProbeCarAppService` exposes one Android Auto `NAVIGATION` service. The head unit draws a `NavigationTemplate`; the app draws its own marked scenario route on the host-provided map surface. The phone console remains a separate phone UI. The service does not declare a calling category or inject a position into Android Auto, Google Maps, or a platform location provider.

## Behavior

- Load demo loads a scenario. Start, Pause, Resume, and Stop control the same `DriveSession` used by the phone console.
- A running scenario claims navigation with `NavigationManager.navigationStarted()` and supplies `updateTrip()` at up to one update per second during stable playback. Pause, stop, completion, scenario replacement, and session destruction end that ownership. A host stop request stops playback and suppresses stale asynchronous running frames.
- Map rendering runs only while the car screen is visible and a surface exists, at no more than 10 Hz. Tile callbacks share this limit. Stable and visible surface bounds keep the camera, simulation badge, and map attribution clear of host overlays. Surface replacements and destruction release the received surfaces.
- Navigation tracking remains active while the map screen is hidden, so a call overlay or another screen does not prevent playback completion from clearing the host trip. It uses the existing playback service and adds no wake lock.
- The trip destination is the fixture's last coordinate. Remaining distance sums the untraveled portions of its great-circle segments, rather than the straight-line distance to that coordinate. Remaining time follows the fixture schedule, including stationary dwells. ETA uses the fixture's **synthetic UTC** endpoint. This is a route playback preview; the endpoint cue is not road routing, traffic information, or real driving guidance.
- Dialer requests `ACTION_DIAL` with an empty `tel:` URI through `CarContext.startCarApp()`. The host supplies its dialer experience. The action does not place a call, provide a phone number, or embed phone call controls in a car template.

## Build and host requirements

The project uses `androidx.car.app:app:1.4.0`, Java 8, minSdk 29, and compileSdk 34. The released AAR declares `minCompileSdk=34`. Its official host allowlist resource supplies package/signature pairs to the release `HostValidator`. Only a debug build uses `ALLOW_ALL_HOSTS_VALIDATOR`.

The manifest declares `NAVIGATION_TEMPLATES` and `ACCESS_SURFACE`, an exported `CarAppService` with the `NAVIGATION` category, minimum Car API level 1, and the `@xml/automotive_app_desc` template descriptor. An Android Auto host or Desktop Head Unit is still required to verify projection, surface insets, call overlays, and hardware input. Compilation and fake-host tests do not prove head-unit compatibility or distribution approval.

## Verification

`RouteProgressTest` checks bent routes, dwells, date-line crossing, and endpoint timing. `NavigationStateTest` checks ownership and cancellation of stale playback previews. `CarProjectionTest` uses the real AndroidX `TestCarContext` and `ScreenController` to validate templates, destination estimates, navigation start/end behavior, and the queued dial intent, with no phone call or head-unit connection.

For a physical host or the DHU, verify: load/start; visible moving map and attribution; pause/resume from both phone and car; stop from the host; completion while the map is hidden; reconnect; surface resizing/night-mode changes; and opening/dismissing the host dialer. Use a parked test environment and a fixture visibly labeled SIMULATION.

## Primary references

- [Navigation apps: manifest, templates, and trip updates](https://developer.android.com/training/cars/apps/navigation)
- [NavigationTemplate API](https://developer.android.com/reference/androidx/car/app/navigation/model/NavigationTemplate)
- [SurfaceCallback lifecycle and release requirements](https://developer.android.com/reference/androidx/car/app/SurfaceCallback)
- [CarContext.startCarApp supported intents](https://developer.android.com/reference/androidx/car/app/CarContext#startCarApp(android.content.Intent))
- [HostValidator](https://developer.android.com/reference/androidx/car/app/validation/HostValidator)
- [AndroidX Car App release history](https://developer.android.com/jetpack/androidx/releases/car-app)
- [Official 1.4.0 source archive](https://dl.google.com/dl/android/maven2/androidx/car/app/app/1.4.0/app-1.4.0-sources.jar)
- [Test Android Auto with the Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
