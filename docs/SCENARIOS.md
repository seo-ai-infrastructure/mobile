# Hooking scenarios

The probe plays local, visibly labeled fixtures inside `com.example.duoplus_probe`.
It can run without the DuoPlus loader. Scenario data stays in probe-owned data
objects; it does not set Android location, radio, sensor or battery providers.
The optional legacy DuoPlus module is a different binary. The normal APK has no
hook diagnostics or bridge dependency.

Open **Hooking → Import JSON** and choose a scenario from the document picker. **Load demo** uses fictional bundled data.

## Build, validate and preview

The Python compiler reads local JSON and uses the existing route geometry and
WiGLE envelope readers. Install the repository's normal Python requirements
first. It does not load `.env`, contact WiGLE/DuoPlus, invoke ADB or read a token.

From the repository root:

```sh
python3 -B scenario_cli.py build \
  --route examples/probe/demo-route.geojson \
  --fixtures examples/probe/demo-fixtures.json \
  --name "Eleven-minute multisensor example" \
  --output examples/probe/demo-scenario.json

python3 -B scenario_cli.py validate --scenario examples/probe/demo-scenario.json
python3 -B scenario_cli.py preview --scenario examples/probe/demo-scenario.json --limit 5
```

`build` prints counts, provenance warnings and a SHA-256 of canonical scenario
JSON. It writes the complete scenario atomically with file mode `600`. `preview`
shows a bounded number of trajectory knots, not an expanded array of IMU ticks.

The bundled copy is
`plugin/app/src/main/assets/demo-scenario.json`. It is byte-identical to the
public example. Regenerating the example does not automatically update an
already built APK; update the bundled file and rebuild the probe when changing
the example. The demo is 660 seconds long:

| Scenario time | Fixture behavior |
| --- | --- |
| 0–60 s | Driving immediately toward the loop |
| 60–300 s | Driving around a small loop with turns |
| 150–180 s | Deliberate invalid GNSS fix |
| 300–360 s | Stopped; charging begins |
| 360–600 s | Walking; cadence 1.8 steps/s |
| 470–490 s | Another deliberate invalid GNSS fix |
| 600–660 s | Stopped; battery and thermal state update |

The demo's coordinates, heights, radios, satellites, activities, connection
states and power changes are examples. Optional noise is off by default.

## Route input

Input is one GeoJSON `LineString`, or a `Feature` containing one. Coordinates
are `[longitude, latitude]` or consistently
`[longitude, latitude, altitude]`. Mixed 2D/3D coordinates are rejected.

### Timestamped routes

A Feature may include `properties.times`, with exactly one UTC ISO 8601
timestamp per coordinate. Use `Z` or `+00:00` and at most millisecond precision:

```json
{
  "type": "Feature",
  "properties": {
    "times": ["2026-01-01T12:00:00Z", "2026-01-01T12:00:30Z", "2026-01-01T12:01:00Z"]
  },
  "geometry": {
    "type": "LineString",
    "coordinates": [[-73.97, 40.77], [-73.97, 40.77], [-73.969, 40.77]]
  }
}
```

Times must strictly increase. Equal consecutive positions with different times
encode a dwell; the example waits for 30 seconds before moving. The first
timestamp becomes `utc_origin_ms`. Omit `--speed-mps` and `--utc-origin` for
timestamped routes because those values are already defined by the file.

### Untimed routes

An untimed route requires a positive `--speed-mps`:

```sh
python3 -B scenario_cli.py build \
  --route examples/hooks/date-line.geojson --speed-mps 2 \
  --utc-origin 2026-01-01T12:00:00Z \
  --output .local/probe/date-line-scenario.json
```

The compiler uses cumulative geodesic distance from `hooks_cli.Route` and
preserves every original vertex, including intermediate corners. Each arrival
time is distance divided by speed, rounded to the nearest millisecond. This
small time quantization can slightly change individual segment speeds. GNSS is
still delivered at 1 Hz; its delivery cadence does not determine route vertices.

If two vertex arrivals round to the same millisecond, compilation fails instead
of deleting a corner. Lower the speed or supply explicit `properties.times`.
Repeated untimed coordinates cannot encode dwell; use timestamped input for
that case. A two-point entirely stationary untimed route retains the explicit
one-second example duration. If UTC is omitted, the example origin is
`2026-01-01T12:00:00Z`.

### Altitudes

Three-dimensional routes must declare `properties.altitude_datum` or
`--altitude-datum` as `msl` or `ellipsoid`. Conflicting declarations are rejected.

- `alt_msl_m` is mean-sea-level altitude in metres.
- `geoid_sep_m` is ellipsoidal height minus MSL height, in metres.
- Ellipsoid input requires `properties.geoid_sep_m` or
  `--geoid-separation-m`; conversion is `MSL = ellipsoid − separation`.
- MSL input without geoid separation receives a labeled example separation of
  zero. This is not a geographic geoid model.
- A 2D route receives a labeled example MSL height of 25 m; override with
  `--example-height-m`. Its missing height is never described as surveyed.

The supported model domain is MSL height `−12000..20000 m`, geoid separation
`−1000..1000 m`, and sea-level reference pressure `300..1200 hPa`. Inputs beyond
those limits are rejected rather than producing undefined pressure values.

## Import saved survey data

Import your saved survey catalog together with your own route:

```sh
python3 scenario_cli.py build --route my-route.geojson --speed-mps 5 \
  --catalog my-survey-library.json --output my-scenario.json
```

Supply files you are authorized to use. Imported survey identifiers and provider downloads are not bundled. If you use `--site`, also supply your own `--sites-file PATH` containing that site's saved `wigle_library`. Keep imported catalogs and scenarios private.

Alternatively, use `--catalog PATH` for either supported local format:

1. A saved WiGLE library: `query_envelopes_v1`, or legacy `wifi`, `cell`, `bt`
   arrays read through `wigle.library_rows`.
2. The normalized scenario `catalog` object: `source`, `complete`,
   `observed_at`, `records`, and optional `catalog_checked_at`. A standalone
   wrapper may additionally declare `schema:"hooking.catalog", version:1`.
3. Observatory's normalized `WigleUploadData` version 1, or the saved-upload GET
   response `{ "upload": { "data": ... } }` (including its upload summary).

In Observatory, open the device's saved WiGLE uploads, select a file, wait for
its observations to load, then choose **Download for Hooking**. The download
includes every saved observation regardless of the table's filter or page. It
preserves original observation/query dates and incomplete-coverage warnings;
downloading does not refresh WiGLE or change the device.

Use that download with a local route:

```sh
python3 -B scenario_cli.py build \
  --route examples/probe/demo-route.geojson \
  --catalog observatory-upload.json --output my-survey-scenario.json
```

The resulting file uses the same `hooking.scenario` v1 schema and the probe's
existing **Import JSON** action. The timestamped example route is a simulated
journey; replace it with your own route where needed. Untimed routes still need
`--speed-mps`. A catalog download by itself is not a playable scenario.

The adapter maps `WIFI/CELL/BLUETOOTH` to `wifi/cell/ble`, `identifier` to the opaque
radio ID, and `lng` to longitude. It retains SSIDs, frequency, channel, observation
dates, radio/technology, encryption, comments, and opaque attributes with survey
provenance. Bluetooth metadata is preserved under `fields.bluetooth`; an observed
Bluetooth name also supplies `fields.name`. Missing sensor fields/channels continue
to receive explicitly labeled examples. Raw provider envelopes and saved site IDs
are not copied into the scenario.

Observatory `queriedAt` supplies `catalog_checked_at`; unknown query time remains
unknown. Record observation dates remain `firsttime`, `lasttime`, and `lastupdt`.
Wrapper `importedAt` is retained in a warning as file-import metadata, never used
as a query/observation timestamp. Coverage always remains `complete:false`;
pagination, rejection, and duplicate warnings are retained. The adapter accepts at
most 1,000 records per Observatory upload before deduplication, matching that
format's limit. Existing 16 MiB input and 10,000-record scenario limits still apply.
Malformed versions, fields, coordinates, dates, numeric values, or Bluetooth
metadata are rejected. This import reads local files and never fetches more pages.

A bare provider `results` response without a declared record kind is not
accepted; wrap it in a supported WiGLE library envelope first.

Each normalized record contains `kind`, opaque `id`, `lat`, `lon`, `fields`
and a `provenance` map. Kinds are `wifi`, `cell`, `ble`. Provenance values are
`survey`, `example`, or `modeled`, including provenance for `id`, `lat`, `lon`
and every field. Optional source/date metadata may also be retained on a record.

Import rules:

- Preserve `complete:false`; missing completeness becomes false. Supplements
  never convert incomplete survey coverage into complete coverage.
- Preserve source observation dates in fields. `catalog_checked_at` is download
  time and remains separate from `observed_at`; unknown observation dates stay
  null. Survey coordinates are catalog observations, not verified transmitter
  locations.
- Deduplicate by `(kind, opaque ID)` with first occurrence winning. Conflicts
  are reported and make the catalog incomplete. Cellular attributes remain
  unparsed strings; structured `gentype` is retained as source technology.
- Preserve an observed empty SSID. Fill absent fields with individually labeled
  examples, such as frequency or propagation-model calibration.
- If a channel has no imported records, add one explicitly example radio at
  the route start. This is how a survey without BLE gets a BLE test fixture.
- Generated RSSI always has modeled provenance. Catalog QoS is never treated
  as RSSI. A source `fields.rssi_dbm` measurement keeps its own field provenance;
  the engine's separate outer `rssi_dbm` has `rssi_provenance:"modeled"`.

Before playback, the Android adapter builds a temporary SQLite spatial index
and loads the route corridor into memory. It first tries an R-tree. If the
device's SQLite lacks that module, it uses ordinary SQLite bounding-box tables
with B-tree indexes (`sqlite_bbox_btree`). Both paths apply the same exact
spherical point-to-route distance checks after the bounding-box lookup; the
fallback changes the index, not the selection radius or distance calculation.
The temporary database is closed and deleted after preparation.

Visibility radii are 300 m for Wi-Fi, 100 m for BLE and 5 km for cells. Both
index paths stop preparation if more than 2,000,000 candidate checks are needed.
The B-tree fallback also rejects `(trajectory points − 1) × catalog records`
above 2,000,000 before building the index, bounding its worst-case work. Rejected
imports must be split or shortened; records are not silently dropped to fit.
The selected index and fallback warning are shown by the service. These details
are implemented in [ScenarioCatalog.java](../plugin/app/src/main/java/com/example/duoplus_probe/ScenarioCatalog.java).

Playback uses the prepared in-memory catalog; it does not issue per-tick
database queries or live scans. Visibility and scripted connections are
separate outputs, and connection fixtures may refer to radios outside the
currently visible set.

## Sensor profiles and events

`--fixtures` accepts a local `hooking.fixtures` version 1 object containing
`profiles`, `events` and optional `warnings`. These settings are examples/model
inputs. See `examples/probe/demo-fixtures.json` for an editable source file.
The compiler emits explicit initial scripts for all event channels, including
GNSS satellites and radio connections; it labels any generated defaults.

| Channel | Default rate | Units and behavior |
| --- | ---: | --- |
| `pose` | 10 Hz, fixed | Dispatcher publication for the phone and Auto map |
| `gnss` | 1 Hz, fixed | GPS position, NMEA and satellite status share one epoch |
| `wifi` | 0.1 Hz | Catalog visibility; RSSI in dBm |
| `cell`, `ble` | 1 Hz | Catalog visibility and independent connection fixtures |
| `imu` | 50 Hz | Gravity/linear/accelerometer in m/s²; gyro in rad/s |
| `magnetic` | 10 Hz | Mounted field in µT, true heading and baseline declination |
| `pressure` | 1 Hz | Synthetic pressure in hPa from MSL altitude and QNH |
| `power` | 1 Hz | Held battery percentage, charging and thermal state |
| `activity` | 1 Hz | Held activity and cumulative scripted steps |

Configurable rates are `0.001..100 Hz`. Event offsets are integer `t_ms`, start
at zero, strictly increase within each channel, and remain within the route.
Discrete events hold over `[event time, next event time)`.

### Position and IMU math

Between knots the model follows the shortest great-circle arc on a sphere of
radius `6371008.8 m`, with linear progress in time and linear MSL/geoid height.
Horizontal speed is segment distance divided by segment duration. Longitude
wraps correctly at the date line; antipodal endpoints require an intermediate
waypoint. Heading is clockwise from true north and follows the local tangent;
a stop retains its arrival heading.

The IMU model uses a segment-local 20 ms derivative window, one-sided near
segment boundaries. It does not smooth over corners. At an abrupt velocity or
heading change, the affected instantaneous derivative is undefined: the output
is null with validity/discontinuity flags. Terminal instantaneous stops follow
the same rule. Gravity remains available. There is no fabricated suspension,
pitch/roll, gait oscillation or physical handset motion model.

The frame transform is true east/north/up → vehicle right/forward/up → the
configured device mount. `mount` is a row-major proper 3×3 rotation: orthonormal
and determinant +1. With heading θ, the vehicle transform is:

```text
right   =  cos(θ) × east − sin(θ) × north
forward =  sin(θ) × east + cos(θ) × north
up      =  up
```

The stationary accelerometer support component is +Up `9.80665 m/s²` before
the mount transform. The engine publishes `gravity_mps2`,
`linear_accel_mps2`, `accelerometer_mps2` and `gyro_rads` separately. V1 IMU noise is zero; accelerometer equals gravity plus linear
acceleration wherever the derivative is defined. Yaw rate about +Up is
`−d(heading)/dt`, because heading increases clockwise.

### Magnetometer, pressure and radios

`magnetic_enu_ut` is one configured field vector in true east/north/up, in
microtesla. Its declination is `atan2(east, north)`; the vector is rotated using
the same heading and mount. There is no geographic magnetic-field lookup.

Pressure uses the declared test model:

```text
pressure_hpa = qnh_hpa × (1 − alt_msl_m / 44330)^5.2559
```

QNH defaults to the example `1013.25 hPa`. This is a synthetic standard-lapse
relation within the supported height domain, not measured weather.

Radio strength uses a distance model with a 1 m floor:

```text
rssi_dbm = reference_rssi_dbm − 10 × path_loss_exponent × log10(max(1, distance_m))
```

The engine bounds generated RSSI to `−160..−10 dBm`. Default reference/exponent
pairs are Wi-Fi `−45/2.2`, cell `−70/3.0`, and BLE `−55/2.0`, all example
calibration values. These are neither propagation measurements nor guarantees
that a device would detect a transmitter.

Noise defaults to false. If explicitly enabled, variation is keyed by seed,
channel, sample index and component. A skipped delivery therefore does not
change future fixture values.

### GNSS status and NMEA

`events.gnss` supplies at most 64 unique `(constellation, svid)` records per
epoch. Supported constellations are GPS, GLONASS, GALILEO, BEIDOU, QZSS, SBAS and
IRNSS. Each record supplies C/N₀, elevation, azimuth, used-in-fix, ephemeris and
almanac flags, with optional carrier frequency. Validity requires both the
requested `events.fix` state and at least four distinct eligible used satellites.

GNSS status is produced from these records, never reconstructed from NMEA.
GGA and RMC share the same position, validity and synthetic UTC. Valid GGA uses
simulation quality 8 and RMC uses simulator mode S; invalid epochs mark the fix
invalid and omit valid-position fields. HDOP/accuracy defaults are explicitly
example values. No pseudorange, Doppler, carrier-phase or raw receiver logs are
modeled or exported.

### Connections, power and steps

`events.connections` contains nullable `wifi_id`, nullable `cell_id` and a
`ble_ids` list. References must match existing catalog records of the correct
kind. They represent explicit test states, not Android association, serving-cell
selection, received BLE advertisements or GATT connections.

`events.power` holds `battery_pct`, `charging` and `thermal_status` until the next
event. `events.activity` holds an activity. A supplied STILL interval must have no
positive-duration overlap with a moving route segment: horizontal distance above
the `1e-8 m` numerical-zero tolerance or any changed MSL altitude counts as movement.
Intervals are half-open, so a stop ending exactly when movement starts is valid;
a terminal STILL event has no interval beyond the scenario. Dwells remain valid.
Omitting an activity script keeps the existing IN_VEHICLE example default.

An optional explicit step script supplies the counter independently from activity
cadence and IMU values:

```json
"steps": {
  "start": 100,
  "deltas": [{ "t_ms": 500, "delta": 1 }, { "t_ms": 1000, "delta": 2 }]
}
```

Place this object under `events` in a scenario or fixtures file. At scene time
`t`, the counter is `start + sum(delta where t_ms <= t)`: the example shows 100
before 500 ms, 101 at 500 ms, and 103 at 1000 ms. `start` is a nonnegative integer;
each delta is a positive integer. Delta offsets are strictly increasing within
`0..duration_ms`; the first delta may occur after zero, and an empty delta array
is valid. At most 100,000 deltas are accepted, and the total including `start`
must remain at most `9007199254740991` (`2^53−1`). No steps are inferred between
explicit offsets. Presence of `events.steps` makes it the sole counter source:
every activity `cadence_hz` must be zero, otherwise validation fails as ambiguous.
The CLI event summary reports the number of delta entries.

When `events.steps` is absent, legacy cadence remains supported: only WALKING and
RUNNING can have nonzero `cadence_hz`. The counter integrates cadence over
half-open activity intervals. Delivery gaps do not reset either counter source
or invent a new walking session. All counters remain scene data rendered/logged
inside Hooking; they are not published to Android step sensors.

## Playback, background behavior and clocks

The probe imports a complete validated scenario before playback. The service
owns one monotonic scheduler; the UI displays snapshots and has Import, Start,
Pause, Stop and Restart controls. Background playback uses a persistent
foreground-service notification, with a partial wake lock only while playing.
Pause/Stop and completion release it. Process death does not automatically
restart or resume playback. See [dispatcher routing](DISPATCHER.md) and
[normal Android packaging](PACKAGING.md).

There are three explicit clock values:

1. `scenario_ms`: position in the test timeline, frozen while paused.
2. `sample_elapsed_ns`: the scheduled sample deadline mapped into Android
   elapsed time using the current start/resume anchor.
3. `delivered_elapsed_ns`: actual monotonic delivery time; `lateness_ms` is
   measured from the deadline.

Synthetic UTC is `utc_origin_ms + scenario_ms` and supplies NMEA timestamps.
It never changes the device clock. On lateness, the engine counts skipped
samples instead of replaying a burst. High-frequency values are generated
on demand from in-memory data; tick handlers do not read files, parse JSON or
access the network. A wake lock and timer do not establish hard real-time rates.

## Limits, schemas and verification

- Scenario UTF-8 input/output: 16 MiB. Duplicate JSON keys and non-finite numbers fail.
- Trajectory: 2–100000 points and at most 24 hours.
- Catalog: at most 10000 input records, checked before deduplication and after
  supplements. IDs remain opaque and unique within kind.
- UTC origin: `0..253402214399999` Unix ms, preserving 24-hour headroom within
  the compiler's supported date range.
- Scenario name: nonempty, at most 200 characters. Radio IDs: at most 512.
- JSON nesting: at most 32 levels overall and 16 within a radio fields object.
- State-event arrays: nonempty if present, starting at zero and strictly ordered.
- Optional step object: at most 100,000 strictly ordered positive deltas within
  the scenario, with a nonnegative start and safe-integer cumulative total.
- In-memory delivered-frame history: at most 2048 frames; exports describe
  delivered/skipped counts and model assumptions.

Report exports stream directly to the selected document. Their size depends on
the bounded history and imported metadata; the scenario's 16 MiB limit is not
a report-file size limit.

The machine-readable contract is
[`scenario-v1.schema.json`](../schemas/scenario-v1.schema.json). Exported playback
reports use [`report-v1.schema.json`](../schemas/report-v1.schema.json). Runtime
validators additionally check finite values, timestamps, proper rotations,
unique identities, referenced radio kinds, dimensions and practical size limits.
Unknown schema versions and undeclared fields are rejected. The standalone file
schema is independent of the existing hook bridge's protocol version 1.

Reports exported through the Android service additionally include
`models.spatial_index` and the optional top-level `catalog_preparation` object:

| Field | Meaning |
| --- | --- |
| `source_records` | Input scenario catalog size, including normalized survey records and labeled supplements |
| `corridor_records` | Number retained in memory after exact route-corridor filtering |
| `index` | `sqlite_rtree` or `sqlite_bbox_btree` |
| `warning` | Fallback explanation, or an empty string when no warning applies |

Pure engine/JVM reports can omit this preparation object because they do not
run the Android database adapter. A smaller corridor count is spatial filtering,
not a claim that the source survey was complete.

```sh
python3 -B -m unittest discover -s tests -p 'test_scenario_cli.py' -v
python3 -B -m unittest discover -s tests -p 'test_hooks_cli.py' -v

# JSON Schema development tests use requirements-dev.txt (jsonschema).
.venv/bin/python -B -m unittest discover -s tests -p 'test_artifact_schemas.py' -v

# Android/JVM checks are local builds; no device installation is implied.
./plugin/build.sh :app:assembleProbeDebug :app:testProbeDebugUnitTest :app:lintProbeDebug
```

Host/schema tests do not prove on-device background delivery. Device acceptance
separately checks notification controls, screen-off continuation, UI reattachment,
process-death behavior, per-channel delivery/skip counters, import failures and
report export. A passing check never establishes acceptance by a consumer app or
provider; playback data is confined to this probe.

The fixed 10 Hz `pose` publication is shared by phone and Auto. Matching GPS, NMEA
and satellite epochs come from one `renderGps(scene)` evaluation. New report
frames include `scene_elapsed_ns` (original boot origin plus scene offset),
`synthetic_utc_ms` and shared fix validity. Device deadlines remain pause-adjusted.
Cellular RSSI is unavailable in v1; source cellular fields stay opaque.
