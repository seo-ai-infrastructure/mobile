# Implementation contract: scenario v1

This is a local, visibly simulated probe format. It never sets Android hardware
providers or invokes third-party callbacks. All numbers must be finite.

Top-level JSON:

- `schema`: `hooking.scenario`, `version`: 1, `simulated`: true
- `name`: nonempty string; `seed`: signed 32-bit integer; `utc_origin_ms`: Unix ms
- `trajectory`: 2–100000 records `{t_ms, lat, lon, alt_msl_m, geoid_sep_m}`.
  Integer offsets start at zero and strictly increase; duration at most 24 hours.
  Repeated positions encode dwell. Heights are finite and explicitly MSL/geoid;
  `altitude_provenance` is a top-level string explaining measured or example data.
- `catalog`: `{source, complete, observed_at, records}`; records at most 10000.
  Each record is `{kind, id, lat, lon, fields, provenance}`. Kind is `wifi`, `cell`,
  or `ble`; id is opaque. Fields hold source values (SSID, frequency, opaque
  cellular attributes) and optional example values. Provenance maps each field
  to `survey`, `example`, or `modeled`; record source dates may remain unknown.
  Generated RSSI is always modeled, never a survey measurement.
- `profiles`: optional object with `mount` (9 row-major rotation values, default
  `[1,0,0, 0,0,1, 0,-1,0]`), `magnetic_enu_ut` (default `[0,20,-40]`),
  `noise` (radio variation boolean, default false), and `rates_hz` map. Rates default GPS/NMEA/GNSS,
  cell/BLE/pressure/power/activity=1; wifi=0.1; imu=50; magnetic=10. GPS/NMEA/GNSS
  are one `gnss` channel. Channel keys: `gnss,wifi,cell,ble,imu,magnetic,pressure,
  power,activity`. Rates must remain positive and <=100 Hz; GNSS fixed at 1 Hz.
  `qnh_hpa` defaults to 1013.25. Magnetic vector is true-ENU; its horizontal
  atan2(E,N) defines declination. Heading is clockwise from true north.
- `events`: optional object with ordered `fix` records `{t_ms, valid}`;
  `activity` records `{t_ms, type, cadence_hz}` where type is STILL, IN_VEHICLE,
  WALKING, RUNNING, ON_BICYCLE; `power` records `{t_ms,battery_pct,charging,
  thermal_status}`. Defaults: valid fix; IN_VEHICLE with no steps; battery 80%,
  discharging, thermal NONE. Defaults and satellite fixtures are examples.
- `events.gnss`: held per-epoch satellite fixtures `{t_ms,satellites}`. Each
  satellite has constellation (GPS/GLONASS/GALILEO/BEIDOU/QZSS/SBAS/IRNSS), svid,
  cn0_dbhz, elevation_deg, azimuth_deg, used_in_fix, has_ephemeris, has_almanac,
  optional carrier_hz. GGA counts distinct used constellation/svid pairs.
  Effective validity is requested fix validity AND at least four used satellites.
  Status is derived from these records, never decoded from GGA/RMC.
- `events.connections`: held epochs `{t_ms,wifi_id,cell_id,ble_ids}`; Wi-Fi/cell
  nullable IDs and BLE ID list refer to matching catalog kinds. This is a
  connection-state fixture independent from catalog visibility, not a real
  association, serving modem state, or BLE GATT connection.
- `warnings`: string array (including coverage gaps and abrupt route transitions).

Import limits: 16 MiB UTF-8 JSON, no unknown schema version. Preserve missing
survey data provenance and incomplete coverage. Example supplements are explicit.

## Java boundary (package com.example.duoplus_probe.sim)

`Scenario.parse(JSONObject)` validates to immutable typed data. Public fields:
`name`, `durationMs`, `catalog` (List<Scenario.Radio>), `trajectory`
(List<Scenario.Knot>), `warnings`. Radio fields: kind,id,lat,lon,fields,provenance.
Knot fields: timeMs,lat,lon,altMslM,geoidSepM.
`Scenario.withCatalog(List<Scenario.Radio>)` returns a prepared immutable copy.

`PlaybackEngine(Scenario)` has synchronized methods `start(long nowNs)`,
`pause(long nowNs)`, `resume(long nowNs)`, `stop()`, `tick(long nowNs)`,
`snapshot(long nowNs)` (Map<String,Object>), `report(long nowNs)`
(Map<String,Object>), `nextDelayMillis(long nowNs)` (long).
`isRunning()` provides a lightweight synchronized completion check for the service.
Snapshot keys: state, name, scenario_ms, duration_ms, frames (channel -> map),
delivered, skipped, warnings. States READY,RUNNING,PAUSED,STOPPED,COMPLETED.
Frame maps include channel, sequence, scenario_ms, sample_elapsed_ns, delivered_elapsed_ns,
lateness_ms, simulated=true, values, provenance. No JSON or disk/network in tick.
History bounded to 2048 frames globally; report includes timing aggregates/history.
UI can poll snapshots every 250 ms, never retaining mutable engine internals.

`ScenarioCatalog.prepare(Context, Scenario)` (Android adapter outside sim package)
uses SQLite R-tree, bounding then exact point-to-route distance; prepares a bounded
in-memory corridor list before playback. Where the platform lacks R-tree, it uses
SQLite bounding-box B-tree indexes with the same distance filter. Actual index
type is visible and exported. Imports reject more than 2,000,000 candidate checks;
the fallback also rejects more than 2,000,000 segment/catalog pairs before lookup.
Radii: Wi-Fi 300 m, BLE 100 m, cell 5 km.

Service owns the HandlerThread and invokes engine methods. Engine has no Android
dependencies except import-time org.json. Service binds via local Binder for UI.
Scenario stays in memory; no automatic process-death restart. JSON export happens
only on explicit export. Hook diagnostics is absent from the normal APK; optional DuoPlus code is a different binary.

Clock domains: scenario UTC = utc_origin_ms + scenario offset; NMEA uses that
synthetic UTC. Scheduled sample_elapsed_ns is the scenario offset mapped through
the current start/resume anchor into device elapsedRealtimeNanos. Delivery time
is a fresh elapsedRealtimeNanos reading. Pausing freezes scenario time; resume
rebases deadlines. Wall clock is metadata only and never schedules playback.
The app does not create Location or SensorEvent objects.

IMU is a test model sampled on demand, not pre-expanded 50 Hz arrays: geodesic
piecewise trajectory interpolation and segment-local derivative windows. At
abrupt knots/terminal velocity changes, mark `discontinuous:true` and undefined
derivative vectors as null instead of smearing a jump into huge accelerations.
Heading yaw only; no invented suspension roll/pitch or gait oscillation. Output
gravity, linear acceleration, accelerometer (sum) and gyroscope separately; stops
retain gravity and arrival heading. Explicit `events.steps` supplies a start count and timed positive deltas. Legacy
cadence fixtures remain supported. See SCENARIOS.md for validation bounds.

The dispatcher additionally publishes `pose` at 10 Hz to phone and Auto from the
same scene evaluation. Views never interpolate independently. IMU/magnetic/pressure
noise is zero in v1; cells have no synthesized RAT/RSSI measurement.
New frames include `scene_elapsed_ns`, `synthetic_utc_ms`, and shared `valid`.
`scene_elapsed_ns` uses the original run boot epoch plus scene time; device
deadlines remain pause-adjusted. See DISPATCHER.md and CLOCKS-AND-REPORTS.md.
