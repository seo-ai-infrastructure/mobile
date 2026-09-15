# Dispatcher and surfaces

`PlaybackService` owns one `HandlerThread` named `PlaybackDispatcher-Thread`.
`PlaybackDispatcher` evaluates independent channel deadlines from
`SystemClock.elapsedRealtimeNanos()`. `PlaybackEngine` is a compatibility class
for existing callers, with no additional timer or state.

Start publishes the time-zero slots. Every later deadline is calculated from its
absolute scene sample index. A late wake omits old slots and publishes at most
one latest due frame per channel. The next deadline stays on that index grid;
it is never reset to arrival time plus one interval. There is no 200 ms grace
period hiding omissions. Sequence numbers are one-based slot indices and retain
holes. Pause freezes scene time without emitting a final batch. Resume rebases
device deadlines; Stop cancels the worker pump and freezes its published pose.
The service invalidates queued work with a run generation and releases the wake
lock on Pause, Stop, completion, visibility failure and destruction.

Completion publishes one exact terminal pose, including endpoints between the
regular 100 ms slots. It replaces the due pose for that tick and is marked
`terminal:true`; it never produces a catch-up batch. Pausing consumes overdue
unsent slots as omissions so Resume cannot retime old samples into the pause.

Each tick evaluates a scene once per distinct due offset. It contains the pose,
heading-only kinematics, satellite fixture and effective fix validity. Channels
due at the same offset reuse that scene. Different cadence slots retain their
own explicit `scenario_ms`; a pane showing the preceding GPS second must not be
interpreted as simultaneous with a newer 10 Hz map frame.

| Publication | Consumer | Default cadence |
| --- | --- | --- |
| `gnss` from `renderGps(scene)` | GPS values, GGA/RMC pane, satellite fixture pane, report | 1 Hz |
| `pose` | DriveMapFragment and Auto SurfaceCallback via DriveSession | 10 Hz |
| `imu` | Instrument pane and report; UI may subsample | 50 Hz |
| `magnetic` | Fixture compass values | 10 Hz |
| `wifi` | Catalog visibility table | Every 10 seconds |
| `cell`, `ble` | Opaque cellular catalog and scripted BLE seen/idle table | 1 Hz |
| `pressure`, `power` | QNH/MSL pressure and held battery/thermal fixtures | 1 Hz |
| `activity` | Activity label and cumulative scripted steps | 1 Hz |

Views read immutable publications on their UI thread. The map and Auto share the
same atomic pose publication; neither runs another interpolator or advances the
scene clock. InCallFragment has no dispatcher subscription. UI callbacks are
not executed from the worker thread and fragments are not retained by it.

The v1 IMU, magnetic and pressure models have zero added noise. Optional indexed
variation is limited to Wi-Fi/BLE catalog RSSI. Cellular fields remain opaque and
have no invented RAT signal measurement. Explicit `events.steps` are cumulative
`start + sum(delta at or before scene time)`; older cadence fixtures remain
supported. A STILL interval overlapping a moving trajectory is rejected.

Everything is rendered or recorded inside Hooking. No SensorManager,
WifiManager, TelephonyManager, BLE scanner, GMS, mock-location or target-package
feed is created. Tile fetching belongs to the map renderer, not the tick loop.
Imports prepare a bounded SQLite corridor before playback. Tick evaluation does
no application disk I/O, network requests or JSON parsing.

The bundled 660-second demo now begins driving immediately, then exercises
scripted stops, walking and fix loss. Dropped 50 Hz samples are timing evidence,
not a requirement to alter battery optimization settings.
