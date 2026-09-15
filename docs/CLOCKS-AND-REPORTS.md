# Clock and report contract

Playback is local to the probe process. It does not dispatch Android `Location`,
`SensorEvent`, network scan, modem, GNSS HAL, or third-party callback objects.

## Clocks

| Value | Meaning |
| --- | --- |
| `utc_origin_ms` | Scenario's synthetic Unix UTC origin, supplied by its author |
| `scenario_ms` | Logical playback position; freezes while paused |
| NMEA UTC/date | `utc_origin_ms + scenario_ms`, never the arrival wall clock |
| `sample_elapsed_ns` | Scheduled scenario sample mapped to the current start/resume anchor in device `elapsedRealtimeNanos()` |
| `delivered_elapsed_ns` | Actual device `elapsedRealtimeNanos()` when the sample was produced |
| `lateness_ms` | Nonnegative delivery delay against that sample's scheduled deadline |

Every sensor deadline uses the device elapsed clock. Wall time never advances,
rewinds, or schedules the engine. Resume preserves logical position and creates
a new elapsed-clock anchor. Restart resets scenario position, frame sequences,
cumulative steps and run metrics. Pause/resume does not reset them.

The delivery timestamp is captured when the dispatcher enters a tick and is
shared by that tick's due channel frames. It measures dispatcher scheduling
delay, not UI rendering latency or Android hardware callback completion.

A missed interval is counted as skipped. The engine produces the most recent
due sample per channel, never a burst of old samples. Seeded variation uses the
scenario seed, channel, sample index and component. Skipping a sample cannot
change the noise attached to a later sample.

Android scheduling remains best effort. The foreground service and partial wake
lock support background execution, but cannot guarantee 20 ms delivery. The app
does not request an ignore-battery-optimizations exemption. The device exercise
measures delivered cadence and gaps; it does not certify hard real-time operation.

## Export

Export is **JSON only** in v1, validated by
[report-v1.schema.json](../schemas/report-v1.schema.json). There is no implicit CSV
column order or silent background export.

The top-level format is `hooking.report`, version `1`, `simulated:true`:

- `snapshot`: run state, scenario name/time/duration, latest frame per channel,
  total delivered/skipped counters, warnings, and the simulation label.
- `metrics`: delivered/skipped totals and per-channel totals, maximum and mean
  lateness in milliseconds, and the number of evicted history frames.
- `history`: the last at most 2048 frames across all channels. This is a bounded
  diagnostic tail, not a complete recording of the run.
- `models`: descriptions of interpolation, sensor assumptions, baseline inputs,
  clock mappings and provenance.
- `catalog_preparation` (Android exports): source and retained corridor record
  counts, actual SQLite index type, and any fallback warning. An SQLite build
  without R-tree uses indexed bounding-box queries with the same exact-distance
  filter. The index is also recorded in `models.spatial_index`.

Each history/latest frame contains `channel`, `sequence`, `scenario_ms`,
`sample_elapsed_ns`, `delivered_elapsed_ns`, `lateness_ms`, `simulated`, `values`,
and `provenance`. A frame's `values` are channel-specific; labels distinguish
survey catalog metadata from modeled signal levels and example additions.

### Channel values in v1

| Channel | Principal `values` fields and units |
| --- | --- |
| `gnss` | `valid`, `synthetic_utc_ms`; nullable `location` with `lat`/`lon` degrees, `alt_msl_m`, `geoid_sep_m`, `alt_ellipsoid_m`, `speed_mps`, `bearing_degrees`, `accuracy_m`; `nmea` is a two-string GGA/RMC array including checksums and CRLF; `satellites` uses scenario satellite fields plus `fixture_used_in_fix`; `satellites_used`, `fixture_eligible_satellites`, `satellite_fixture_t_ms`, `hdop`, `hdop_provenance`, `raw_measurements:false` |
| `imu` | Device-coordinate 3-vectors `gravity_mps2`, nullable `linear_accel_mps2` and `accelerometer_mps2`, nullable `gyro_rads`; `linear_accel_valid`, `gyro_valid`, `discontinuous`, `derivative_reason`, `heading_true_deg`, `model` |
| `magnetic` | Device `field_ut` vector, `baseline_enu_ut`, `declination_deg`, `heading_true_deg` |
| `pressure` | `pressure_hpa`, `qnh_hpa`, `altitude_msl_m` |
| `power` | Held `battery_pct`, `charging`, `thermal_status` |
| `activity` | Held `type`, `cadence_hz`; cumulative `step_count`; `step_event_count` over the preceding nominal sample interval and boolean `step_detector` (an aggregate indicator, not an Android callback) |
| `wifi`, `cell`, `ble` | `visible` catalog entries with `id`, coordinates, source `fields`, `field_provenance`, source dates, `distance_m`, modeled `rssi_dbm` and `rssi_provenance`; `visible_total`, `truncated`, `radius_m`, `connected_ids`, `connection_fixture_t_ms`, `connection_state_source`, catalog source/coverage/dates |

Radio display lists retain the nearest 64 Wi-Fi/BLE or 16 cellular entries;
`visible_total` and `truncated` explicitly describe omitted visible entries.
This display bound does not remove records from the prepared scenario catalog.
Invalid GNSS epochs retain the satellite fixture but emit zero used satellites
and a null location. The `fixture_used_in_fix` flags preserve the original
fixture separately from effective `used_in_fix` flags.

Reports can contain imported survey identifiers. They stay on the device until
the user selects an export destination. The repository's `.local/` directory is
ignored for private scenarios and device-validation reports. Bundled examples
contain fictional fixtures, never account keys or the private Flamingo survey.

## Model conventions

- Horizontal coordinates are WGS84 latitude/longitude in degrees. Distance uses
  a mean-Earth-radius sphere (6371008.8 m), not a precision ellipsoidal solver.
- Trajectory segments follow the shortest great-circle arc, including across the
  antimeridian. Height and geoid separation interpolate linearly with time.
- MSL height and geoid separation are explicit metres. Ellipsoidal height is
  their sum; GGA uses MSL height and the separate geoid field.
- IMU is evaluated on demand from the trajectory, never stored as pre-expanded
  50 Hz arrays. Gravity, linear acceleration and their accelerometer sum are
  separate values. Gyroscope models heading yaw only; suspension pitch/roll are
  not inferred. Noise is off by default; optional indexed variation is a fixture.
  Derivative windows stay inside a segment. At abrupt knots or a terminal stop,
  undefined derivatives are null and explicitly flagged as discontinuous; the
  model does not smear jumps into large fabricated accelerations. Dwell preserves
  arrival heading. No gait oscillation is inferred from the activity script.
- Magnetic input is one true east/north/up vector in microtesla. Its horizontal
  `atan2(east,north)` defines declination; the same true heading and mount rotate
  this vector into phone coordinates. Pressure uses scenario QNH in hPa.
- GNSS status comes from held satellite-epoch fixtures, not GGA/RMC parsing.
  NMEA and status share fix validity and distinct used-satellite counts. Raw
  receiver measurements, GSA/GSV output and hardware attestation are outside v1.
- Radio tables display catalog visibility. Scripted connection labels are
  independent fixtures, not Wi-Fi associations, live modem state, received BLE
  advertisements, or GATT sessions. Activity scripts are not classifier results.
- Step count is cumulative integration of walking/running cadence over the
  script's half-open intervals. Driving/stationary intervals retain its value.
