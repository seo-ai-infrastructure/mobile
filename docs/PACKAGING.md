# One normal Android APK

Hooking is `:app`, with a `probe` packaging flavor and `BuildConfig.HOOK_MODE=false`.
Its application ID remains `com.example.duoplus_probe` so 1.3 updates 1.2 in place.
The normal build contains no DuoPlus bridge JAR, native hook library, loader entry,
module configuration, socket stream or hook diagnostics Activity.

```sh
cd plugin
./gradlew :app:assembleProbeDebug
adb install -r app/build/outputs/apk/probe/debug/app-probe-debug.apk
```

Use the existing JDK 21 / SDK 34 setup, or `./plugin/build.sh` from the repository
root. This runs the app build, unit tests and lint. No token or API key is needed.
Shared Java code lives in `app/src/main`; the probe flavor adds only its packaging
flag. Android's default Application does not start playback. The bound service
prepares imported data; only an explicit Start/Resume action starts its foreground
run and acquires its wake lock.

The local checkout also retains `:dplus` as an optional different binary. Gradle
does not even configure it by default. It is included only with
`-PincludeDplus=true`, and is omitted entirely from the public source export.
The normal app must compile with that directory absent.

The map uses OSM/Canvas and the dispatcher pose. Android Auto consumes the same
published pose through the car host. The call pane uses Telecom and remains
independent of simulation. Notifications, specialUse foreground service, wake
lock, optional calling, and Auto surface permissions support those features;
there are no location, package-query, accessibility or hook permissions. Document
picker grants cover scenario imports without broad storage permission.

See [Android build variants](https://developer.android.com/build/build-variants)
for how the `probe` flavor combines with the debug build type.
