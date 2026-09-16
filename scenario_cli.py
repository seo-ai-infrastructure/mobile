#!/usr/bin/env python3
"""Compile and inspect local, visibly simulated multisensor probe scenarios.

This command reads local files only. It never loads .env, contacts a provider,
starts adb, or changes hardware sensors. The legacy hooks_cli v1 bridge is separate.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
from typing import Any

from hooks_cli import HooksError, Point, Route, bounded_number, distance_m, finite_number, integer
from wigle import library_rows


ROOT = Path(__file__).resolve().parent
MAX_BYTES = 16 * 1024 * 1024
MAX_POINTS = 100000
MAX_RADIOS = 10000
MAX_DURATION_MS = 24 * 60 * 60 * 1000
MAX_UTC_ORIGIN_MS = 253402214399999  # Keep a full 24-hour scenario inside year 9999.
MAX_STEP_COUNT = 9007199254740991
MAX_STEP_DELTAS = 100000
KINDS = {"wifi", "cell", "ble"}
PROVENANCE = {"survey", "example", "modeled"}
ACTIVITIES = {"STILL", "IN_VEHICLE", "WALKING", "RUNNING", "ON_BICYCLE"}
CONSTELLATIONS = {"GPS", "GLONASS", "GALILEO", "BEIDOU", "QZSS", "SBAS", "IRNSS"}
THERMAL_STATES = {"NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"}
DEFAULT_RATES = {"gnss": 1, "wifi": 0.1, "cell": 1, "ble": 1, "imu": 50,
                 "magnetic": 10, "pressure": 1, "power": 1, "activity": 1}
DEFAULT_PROFILES = {"mount": [1, 0, 0, 0, 0, 1, 0, -1, 0],
                    "magnetic_enu_ut": [0, 20, -40], "noise": False,
                    "qnh_hpa": 1013.25, "rates_hz": DEFAULT_RATES}
DEFAULT_UTC = "2026-01-01T12:00:00Z"


class ScenarioError(HooksError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ScenarioError(message)


def keys_match(value: dict[str, Any], required: set[str], optional: set[str], name: str) -> None:
    require(required <= set(value) and set(value) <= required | optional, f"{name} has missing or unknown fields")


def text_value(value: Any, name: str, maximum: int = 4096, allow_empty: bool = False) -> str:
    require(isinstance(value, str) and (allow_empty or bool(value.strip())) and len(value) <= maximum,
            f"{name} must be a {'possibly empty' if allow_empty else 'nonempty'} string of at most {maximum} characters")
    return value


def finite_tree(value: Any, depth: int = 0, limit: int = 32) -> None:
    require(depth <= limit, f"JSON nesting exceeds {limit} levels")
    if value is None or isinstance(value, (str, bool)):
        return
    if isinstance(value, (int, float)):
        finite_number(value, "JSON number")
    elif isinstance(value, list):
        for child in value:
            finite_tree(child, depth + 1, limit)
    elif isinstance(value, dict):
        for key, child in value.items():
            text_value(key, "JSON key", 512, allow_empty=True)
            finite_tree(child, depth + 1, limit)
    else:
        raise ScenarioError("unsupported JSON value")


def no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON object keys are not supported")
        result[key] = value
    return result


def read_json(path: Path) -> dict[str, Any]:
    try:
        with path.expanduser().open("rb") as handle:
            require(stat.S_ISREG(os.fstat(handle.fileno()).st_mode), "input must be a regular file")
            raw = handle.read(MAX_BYTES + 1)
        require(len(raw) <= MAX_BYTES, "input exceeds 16 MiB")
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=no_duplicates,
                           parse_constant=lambda _: (_ for _ in ()).throw(ScenarioError("non-finite JSON number")))
        require(isinstance(value, dict), "input must be a JSON object")
        finite_tree(value)
        return value
    except (OSError, UnicodeError, ValueError, RecursionError) as exc:
        raise ScenarioError(f"cannot read valid UTF-8 JSON from {path}") from exc


def write_json(path: Path, value: dict[str, Any]) -> None:
    finite_tree(value)
    encoded = (json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")
    require(len(encoded) <= MAX_BYTES, "compiled scenario exceeds 16 MiB; shorten the route or catalog")
    destination = path.expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".scenario-", suffix=".json", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def utc_millis(value: Any, name: str) -> int:
    text = text_value(value, name, 64)
    try:
        stamp = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ScenarioError(f"{name} must be an ISO 8601 UTC timestamp") from exc
    require(stamp.tzinfo is not None and stamp.utcoffset().total_seconds() == 0,
            f"{name} must include Z or a zero UTC offset")
    require(stamp.microsecond % 1000 == 0, f"{name} must have millisecond precision or coarser")
    epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
    delta = stamp - epoch
    result = delta.days * 86400000 + delta.seconds * 1000 + delta.microseconds // 1000
    return integer(result, name, 0, 253402300799999)


def route_document(document: dict[str, Any], speed_mps: float | None = None,
                   altitude_datum: str | None = None, geoid_separation_m: float | None = None,
                   example_height_m: float = 25, utc_origin: str | None = None) -> tuple[list[dict[str, Any]], int, str, list[str]]:
    geometry = document.get("geometry") if document.get("type") == "Feature" else document
    properties = document.get("properties", {}) if document.get("type") == "Feature" else {}
    require(isinstance(properties, dict), "GeoJSON properties must be an object")
    require(isinstance(geometry, dict) and geometry.get("type") == "LineString", "route must contain one GeoJSON LineString")
    route = Route(geometry.get("coordinates"))
    times = properties.get("times")
    warnings = []
    if times is not None:
        require(speed_mps is None, "timestamped routes already define speed; omit --speed-mps")
        require(utc_origin is None, "timestamped routes already define UTC origin; omit --utc-origin")
        require(isinstance(times, list) and len(times) == len(route.points), "properties.times must match every coordinate")
        stamps = [utc_millis(value, f"times[{index}]") for index, value in enumerate(times)]
        require(all(a < b for a, b in zip(stamps, stamps[1:])), "route timestamps must strictly increase; repeated coordinates may encode dwell")
        origin = stamps[0]
        offsets = [stamp - origin for stamp in stamps]
        require(offsets[-1] <= MAX_DURATION_MS, "route duration exceeds 24 hours")
        points = route.points
    else:
        require(speed_mps is not None, "untimed route requires --speed-mps")
        route.interval_count(speed_mps)  # Reuse the route speed/finite-duration validation.
        duration_ms = route.length_m / speed_mps * 1000
        require(duration_ms <= MAX_DURATION_MS, "route duration exceeds 24 hours")
        origin = utc_millis(utc_origin or DEFAULT_UTC, "utc_origin")
        if route.length_m == 0:
            require(len(route.points) == 2, "untimed vertex arrivals collide; use explicit properties.times for repeated positions")
            offsets, points = [0, 1000], [route.points[0], route.points[-1]]
            warnings.append("Stationary untimed route given an explicit one-second example duration.")
        else:
            # Keep each input vertex so local interpolation follows the original turns.
            # The scenario format stores integer milliseconds, so any unrepresentable
            # distinct arrival is rejected instead of silently deleting a vertex.
            offsets = [math.floor(distance / speed_mps * 1000 + 0.5) for distance in route.cumulative]
            require(all(a < b for a, b in zip(offsets, offsets[1:])),
                    "untimed vertex arrivals collide at millisecond precision; lower --speed-mps or supply explicit properties.times")
            points = route.points
            warnings.append("Untimed original vertices are preserved; cumulative-distance arrival times are rounded to the nearest millisecond.")
        if utc_origin is None:
            warnings.append(f"Scenario UTC origin is an example ({DEFAULT_UTC}); Android clocks are unchanged.")
    datum = altitude_datum or properties.get("altitude_datum")
    if altitude_datum and properties.get("altitude_datum"):
        require(altitude_datum == properties["altitude_datum"], "CLI altitude datum conflicts with GeoJSON properties")
    if geoid_separation_m is None and "geoid_sep_m" in properties:
        geoid_separation_m = finite_number(properties["geoid_sep_m"], "geoid_sep_m")
    if geoid_separation_m is not None:
        geoid_separation_m = finite_number(geoid_separation_m, "geoid_sep_m")
    if route.points[0].alt is not None:
        require(datum in ("msl", "ellipsoid"), "3D routes must explicitly declare altitude_datum=msl or ellipsoid")
        if datum == "ellipsoid":
            require(geoid_separation_m is not None, "ellipsoid heights require an explicit geoid separation for MSL conversion")
        geoid_was_example = geoid_separation_m is None
        geoid = 0.0 if geoid_was_example else geoid_separation_m
        provenance = (f"Route heights declared {datum}; constant geoid separation {geoid:g} m "
                      f"({'example' if geoid_was_example else 'supplied'}). No geoid model is implied.")
        supplied_provenance = properties.get("altitude_provenance")
        if supplied_provenance:
            provenance = text_value(supplied_provenance, "altitude_provenance") + " " + provenance
        if geoid_was_example:
            warnings.append("Missing geoid separation supplemented with an explicitly example value of 0 m.")
        heights = [point.alt - geoid if datum == "ellipsoid" else point.alt for point in points]
    else:
        height = finite_number(example_height_m, "example_height_m")
        geoid = 0.0 if geoid_separation_m is None else geoid_separation_m
        heights = [height] * len(points)
        provenance = f"Example MSL height {height:g} m for a 2D route; geoid separation {geoid:g} m is {'example' if geoid_separation_m is None else 'supplied'}."
        warnings.append("Route has no altitude; MSL height is an example supplement, not an elevation survey.")
    trajectory = [{"t_ms": offset, "lat": point.lat, "lon": point.lon,
                   "alt_msl_m": height, "geoid_sep_m": geoid}
                  for offset, point, height in zip(offsets, points, heights)]
    bounded_number(geoid, "geoid separation", -1000, 1000)
    for height in heights:
        bounded_number(height, "MSL altitude", -12000, 20000)
    warnings.append("Trajectory uses piecewise geodesic positions and linear altitude; corners and speed changes can create abrupt motion transitions.")
    return trajectory, origin, provenance, warnings


def survey_number(value: Any, name: str) -> float:
    if isinstance(value, str):
        try:
            value = float(value)
        except ValueError as exc:
            raise ScenarioError(f"invalid survey {name}") from exc
    return finite_number(value, name)


def observatory_catalog(document: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    """Adapt the saved Observatory v1 payload; provider responses are never exported."""
    warnings: list[str] = []
    summary_queried_at = None

    def count(value: Any, name: str, maximum: int = 9007199254740991) -> int:
        return integer(value, name, 0, maximum)

    def text(value: Any, name: str, maximum: int = 4096) -> str:
        value = text_value(value, name, maximum, allow_empty=True)
        try:
            size = len(value.encode("utf-8"))
        except UnicodeError as exc:
            raise ScenarioError(f"invalid Observatory {name}") from exc
        require(size <= maximum and not re.search(r"[\x00-\x1f\x7f-\x9f]", value),
                f"invalid Observatory {name}")
        return value

    def date(value: Any, name: str) -> str | None:
        if value is None:
            return None
        value = text_value(value, name, 64)
        require(re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})", value) is not None,
                f"invalid Observatory {name}")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            require(parsed.utcoffset() is not None, f"Observatory {name} requires a timezone")
        except ValueError as exc:
            raise ScenarioError(f"invalid Observatory {name}") from exc
        return value

    if "upload" in document:
        keys_match(document, {"upload"}, set(), "Observatory response")
        upload = document["upload"]
        require(isinstance(upload, dict), "Observatory upload must be an object")
        keys_match(upload, {"data"}, {"id", "filename", "importedAt", "wifiCount", "cellCount", "bluetoothCount",
                                      "rejectedCount", "duplicateCount", "source", "queriedAt"}, "Observatory upload")
        if "source" in upload:
            require(upload["source"] is None or (isinstance(upload["source"], str)
                    and upload["source"] in ("UPLOAD", "WIGLE_QUERY")), "invalid Observatory upload source")
            if upload["source"] is not None:
                label = "WiGLE API query" if upload["source"] == "WIGLE_QUERY" else "manual JSON upload"
                warnings.append(f"Observatory saved source: {label}; radio fields retain survey provenance.")
        if "queriedAt" in upload:
            summary_queried_at = date(upload["queriedAt"], "summary query time")
        for name in ("id", "filename"):
            if name in upload:
                text_value(upload[name], f"upload {name}", 512)
        for name in ("wifiCount", "cellCount", "bluetoothCount", "rejectedCount", "duplicateCount"):
            if name in upload:
                count(upload[name], f"upload {name}")
        if "importedAt" in upload:
            imported_at = date(upload["importedAt"], "import time")
            require(imported_at is not None, "Observatory import time must not be null")
            warnings.append(f"Observatory file import time: {imported_at}; not an observation or query time.")
        document = upload["data"]

    require(isinstance(document, dict), "Observatory data must be an object")
    keys_match(document, {"version", "records", "rejectedCount", "duplicateCount", "warnings", "page", "queriedAt"},
               {"query", "rawResponses"}, "Observatory data")
    require(type(document["version"]) is int and document["version"] == 1, "unsupported Observatory data version")
    rows = document["records"]
    require(isinstance(rows, list) and len(rows) <= 1000, "Observatory upload must have at most 1000 records before deduplication")
    rejected = count(document["rejectedCount"], "rejectedCount")
    duplicates = count(document["duplicateCount"], "duplicateCount")
    supplied_warnings = document["warnings"]
    require(isinstance(supplied_warnings, list) and len(supplied_warnings) <= 1000, "invalid Observatory warnings")
    warnings.extend("Observatory: " + text(value, "warning") for value in supplied_warnings)
    warnings.append(f"Observatory source retained {len(rows)} records; previously rejected {rejected} and deduplicated {duplicates}. Coverage remains incomplete.")
    page = document["page"]
    require(isinstance(page, dict), "Observatory page must be an object")
    keys_match(page, {"totalResults", "resultCount", "first", "last", "hasCursor"}, set(), "Observatory page")
    require(type(page["hasCursor"]) is bool, "Observatory hasCursor must be boolean")
    for name in ("totalResults", "resultCount", "first", "last"):
        if page[name] is not None:
            count(page[name], f"page {name}")
    if page["hasCursor"] or (page["totalResults"] is not None and page["totalResults"] > len(rows)):
        warnings.append("Observatory saved data is a partial search/page; no missing pages were fetched.")
    queried_at = date(document["queriedAt"], "query time")
    if summary_queried_at is not None:
        require(summary_queried_at == queried_at, "Observatory summary and payload query timestamps disagree")
    if "query" in document:
        query = document["query"]
        require(isinstance(query, dict), "Observatory query must be an object")
        keys_match(query, {"queriedAt", "endpoint", "anchor"}, {"siteId", "radiusM"}, "Observatory query")
        if "siteId" in query:
            text_value(query["siteId"], "query site ID", 200)
        if "radiusM" in query:
            radius = finite_number(query["radiusM"], "Observatory query radiusM")
            require(radius > 0, "Observatory query radiusM must be positive")
            warnings.append(f"Observatory saved search radius: {radius:g} m; a query boundary, not verified radio coverage.")
        require(query["endpoint"] in ("network/search", "cell/search", "bluetooth/search"), "unsupported Observatory query endpoint")
        require(date(query["queriedAt"], "saved query time") == queried_at and queried_at is not None,
                "Observatory query timestamps disagree")
        anchor = query["anchor"]
        require(isinstance(anchor, dict), "Observatory query anchor must be an object")
        keys_match(anchor, {"lat", "lng"}, set(), "Observatory query anchor")
        bounded_number(anchor["lat"], "query latitude", -90, 90)
        bounded_number(anchor["lng"], "query longitude", -180, 180)
    if "rawResponses" in document:
        responses = document["rawResponses"]
        require(isinstance(responses, list) and 1 <= len(responses) <= 3, "invalid Observatory raw response count")
        for response in responses:
            require(isinstance(response, dict) and response.get("success") is True and isinstance(response.get("results"), list)
                    and len(response["results"]) <= 1000, "invalid Observatory raw response envelope")
            finite_tree(response, limit=16)

    records = []
    required = {"kind", "identifier", "ssid", "lat", "lng", "qos", "firstSeen", "lastSeen", "lastUpdated",
                "radio", "attributes", "channel", "encryption"}
    kinds = {"WIFI": "wifi", "CELL": "cell", "BLUETOOTH": "ble"}
    for row in rows:
        require(isinstance(row, dict), "Observatory record must be an object")
        keys_match(row, required, {"wifiType", "frequencyMHz", "comment", "bluetooth"}, "Observatory record")
        require(isinstance(row["kind"], str) and row["kind"] in kinds, "unsupported Observatory radio kind")
        kind = kinds[row["kind"]]
        identity = text(row["identifier"], "radio identifier", 512)
        require(bool(identity.strip()), "Observatory radio identifier must not be empty")
        latitude = bounded_number(row["lat"], "Observatory latitude", -90, 90)
        longitude = bounded_number(row["lng"], "Observatory longitude", -180, 180)
        fields: dict[str, Any] = {}
        for source, target, limit in (("ssid", "ssid", 32 if kind == "wifi" else 256), ("radio", "radio", 256),
                                      ("attributes", "attributes", 1024), ("encryption", "encryption", 256)):
            if row[source] is not None:
                fields[target] = text(row[source], source, limit)
        if kind == "cell" and row["radio"] is not None:
            fields["technology"] = fields["radio"]
        for source, target in (("firstSeen", "firsttime"), ("lastSeen", "lasttime"), ("lastUpdated", "lastupdt")):
            value = date(row[source], source)
            if value is not None:
                fields[target] = value
        for name, maximum in (("qos", 7), ("channel", 9007199254740991)):
            if row[name] is not None:
                fields[name] = count(row[name], name, maximum)
        require(kind == "wifi" or not ({"wifiType", "frequencyMHz", "comment"} & set(row)), "Wi-Fi fields on a non-Wi-Fi record")
        for source, target, maximum in (("wifiType", "type", 256), ("comment", "comment", 1024)):
            if source in row:
                fields[target] = text(row[source], source, maximum)
        if row.get("frequencyMHz") is not None:
            fields["frequency_mhz"] = count(row["frequencyMHz"], "frequencyMHz", 100000)
        if "bluetooth" in row:
            require(kind == "ble" and isinstance(row["bluetooth"], dict), "invalid Observatory Bluetooth metadata")
            bluetooth = row["bluetooth"]
            keys_match(bluetooth, {"name", "manufacturerId", "deviceClass", "capabilities"}, set(), "Observatory Bluetooth")
            if bluetooth["name"] is not None:
                fields["name"] = text(bluetooth["name"], "Bluetooth name", 256)
            for name, maximum in (("manufacturerId", 65535), ("deviceClass", 0xffffff)):
                if bluetooth[name] is not None:
                    count(bluetooth[name], f"Bluetooth {name}", maximum)
            if bluetooth["capabilities"] is not None:
                require(isinstance(bluetooth["capabilities"], list) and len(bluetooth["capabilities"]) <= 64,
                        "invalid Observatory Bluetooth capabilities")
                for capability in bluetooth["capabilities"]:
                    text(capability, "Bluetooth capability", 256)
            fields["bluetooth"] = deepcopy(bluetooth)
        record = {"kind": kind, "id": identity, "lat": latitude, "lon": longitude, "fields": fields,
                  "provenance": {key: "survey" for key in ("id", "lat", "lon", *fields)}}
        if queried_at is not None:
            record["catalog_checked_at"] = queried_at
        records.append(record)
    catalog = {"source": "Observatory saved WiGLE observations", "complete": False, "observed_at": None, "records": records}
    if queried_at is not None:
        catalog["catalog_checked_at"] = queried_at
    else:
        warnings.append("Observatory query time is unknown; file import time is not used as a substitute.")
    warnings.append("Survey coordinates are catalog observations, not verified transmitter positions. Cellular identifiers and attributes remain opaque.")
    validate_catalog(catalog)
    return deduplicate_catalog(catalog, warnings), warnings


def import_catalog(document: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    require(isinstance(document, dict), "catalog must be an object")
    if "upload" in document or ("schema" not in document and any(key in document for key in ("queriedAt", "rejectedCount", "duplicateCount", "page"))):
        return observatory_catalog(document)
    warnings: list[str] = []
    if "records" in document:
        if "schema" in document:
            require(document.get("schema") == "hooking.catalog" and type(document.get("version")) is int and document["version"] == 1,
                    "unsupported catalog schema version")
        catalog = deepcopy(document)
        catalog.pop("schema", None)
        catalog.pop("version", None)
        validate_catalog(catalog)
        return deduplicate_catalog(catalog, warnings), warnings
    require("row_storage" in document or any(key in document for key in ("wifi", "cell", "bt")),
            "catalog must be normalized records or a WiGLE library (including query_envelopes_v1)")
    source = text_value(document.get("source") or "Imported local WiGLE catalog", "catalog source")
    complete = document.get("complete", False)
    require(type(complete) is bool, "catalog complete must be true or false")
    if "complete" not in document:
        warnings.append("Imported catalog did not declare completeness; retained as incomplete.")
    checked_at = document.get("checked_at")
    if checked_at is not None:
        text_value(checked_at, "catalog checked_at")
    try:
        rows_by_kind = [(kind, library_rows(document, raw_kind)) for kind, raw_kind in (("wifi", "wifi"), ("cell", "cell"), ("ble", "bt"))]
    except ValueError as exc:
        raise ScenarioError("invalid saved WiGLE library envelope") from exc
    require(sum(len(rows) for _, rows in rows_by_kind) <= MAX_RADIOS, "source catalog exceeds 10000 records before deduplication")
    records = []
    for kind, rows in rows_by_kind:
        for row in rows:
            identity = row.get("netid") if kind in ("wifi", "ble") else row.get("id")
            if identity is None:
                identity = row.get("id") if kind != "cell" else row.get("netid")
            identity = text_value(str(identity) if type(identity) is int else identity, "survey radio ID", 512)
            latitude = bounded_number(survey_number(row.get("trilat"), "latitude"), "survey latitude", -90, 90)
            longitude = bounded_number(survey_number(row.get("trilong"), "longitude"), "survey longitude", -180, 180)
            fields: dict[str, Any] = {}
            # Deliberately exclude address/provider/account data and never decode opaque IDs or attributes.
            for key in ("ssid", "channel", "attributes", "gentype", "type", "encryption", "firsttime", "lasttime", "lastupdt", "qos", "name"):
                if key in row and row[key] is not None:
                    fields[key] = deepcopy(row[key])
            if row.get("frequency") is not None:
                fields["frequency_mhz"] = deepcopy(row["frequency"])
            if kind == "cell" and row.get("gentype") is not None:
                fields["technology"] = deepcopy(row["gentype"])
            if checked_at is not None:
                fields["catalog_checked_at"] = checked_at
            records.append({"kind": kind, "id": identity, "lat": latitude, "lon": longitude,
                            "fields": fields, "provenance": {key: "survey" for key in ("id", "lat", "lon", *fields)}})
    observed_at = document.get("observed_at")
    if observed_at is not None:
        text_value(observed_at, "catalog observed_at")
    catalog = {"source": source, "complete": complete, "observed_at": observed_at, "records": records}
    if checked_at is not None:
        catalog["catalog_checked_at"] = checked_at
    for warning in document.get("warnings", []):
        if isinstance(warning, str):
            warnings.append("Survey catalog: " + warning[:4096])
    warnings.append("Survey coordinates are catalog observations, not verified transmitter positions. Per-record dates are preserved; catalog_checked_at is download time.")
    return deduplicate_catalog(catalog, warnings), warnings


def deduplicate_catalog(catalog: dict[str, Any], warnings: list[str]) -> dict[str, Any]:
    unique = {}
    duplicates = conflicts = 0
    for record in catalog["records"]:
        key = (record["kind"], record["id"])
        if key in unique:
            duplicates += 1
            if record != unique[key]:
                conflicts += 1
        else:
            unique[key] = record
    catalog["records"] = list(unique.values())
    if duplicates:
        warnings.append(f"Deduplicated {duplicates} repeated kind/opaque-ID records; first occurrence wins without parsing cellular identifiers or attributes.")
    if conflicts:
        catalog["complete"] = False
        warnings.append(f"{conflicts} duplicate records had differing source values; catalog remains incomplete.")
    return catalog


def supplement_catalog(catalog: dict[str, Any], anchor: Point, warnings: list[str]) -> dict[str, Any]:
    result = deepcopy(catalog)
    defaults = {
        "wifi": {"ssid": "PROBE EXAMPLE WIFI", "frequency_mhz": 2412, "channel": 1,
                 "reference_rssi_dbm": -45, "path_loss_exponent": 2.2},
        "cell": {"technology": "LTE", "frequency_mhz": 1900, "reference_rssi_dbm": -70, "path_loss_exponent": 3.0},
        "ble": {"name": "PROBE EXAMPLE BEACON", "frequency_mhz": 2402,
                "reference_rssi_dbm": -55, "path_loss_exponent": 2.0},
    }
    added_fields = 0
    for kind in ("wifi", "cell", "ble"):
        selected = [record for record in result["records"] if record["kind"] == kind]
        if not selected:
            require(len(result["records"]) < MAX_RADIOS, "example channel supplements would exceed 10000 catalog records")
            record = {"kind": kind, "id": f"example-{kind}-1", "lat": anchor.lat, "lon": anchor.lon,
                      "fields": {}, "provenance": {"id": "example", "lat": "example", "lon": "example"}}
            result["records"].append(record)
            selected = [record]
            warnings.append(f"No imported {kind} records; added a clearly labeled example {kind} fixture at the route start.")
        for record in selected:
            for field, default in defaults[kind].items():
                if field not in record["fields"] or record["fields"][field] is None:
                    record["fields"][field] = deepcopy(default)
                    record["provenance"][field] = "example"
                    added_fields += 1
    if added_fields:
        warnings.append(f"Added {added_fields} explicitly example radio fields. Empty surveyed SSIDs are preserved. Generated RSSI is modeled, never surveyed.")
    if result["complete"] is not True:
        warnings.append("Imported radio coverage is incomplete; example supplements do not make it complete.")
    return result


def default_satellites(valid: bool = True) -> list[dict[str, Any]]:
    return [{"constellation": "GPS", "svid": index + 1, "cn0_dbhz": 32.0 + index,
             "elevation_deg": 20.0 + index * 10, "azimuth_deg": float(index * 60),
             "used_in_fix": valid or index < 2, "has_ephemeris": True,
             "has_almanac": True, "carrier_hz": 1575420000.0} for index in range(6)]


def populate_events(fixtures: dict[str, Any], catalog: dict[str, Any], anchor: Point, warnings: list[str], duration_ms: int) -> dict[str, Any]:
    events = deepcopy(fixtures.get("events", {}))
    require(isinstance(events, dict), "fixtures events must be an object")
    defaults = {"fix": [{"t_ms": 0, "valid": True}],
                "activity": [{"t_ms": 0, "type": "IN_VEHICLE", "cadence_hz": 0}],
                "power": [{"t_ms": 0, "battery_pct": 80, "charging": False, "thermal_status": "NONE"}]}
    for kind, default in defaults.items():
        if kind not in events or events[kind] == []:
            events[kind] = deepcopy(default)
            warnings.append(f"The {kind} script is an explicit example default.")
        else:
            require(isinstance(events[kind], list) and isinstance(events[kind][0], dict), "event epochs must be arrays of objects")
            initial_time = integer(events[kind][0].get("t_ms"), "event t_ms", 0, duration_ms)
            if initial_time > 0:
                events[kind].insert(0, deepcopy(default[0]))
                warnings.append(f"The initial {kind} state before the supplied events is an example default.")
    # Validate user epochs before deriving dependent satellite/connection fixtures.
    supplied_events = {key: epochs for key, epochs in events.items() if epochs != []}
    validate_events(supplied_events, duration_ms, catalog["records"])
    if "gnss" not in events or events["gnss"] == []:
        events["gnss"] = [{"t_ms": item["t_ms"], "satellites": default_satellites(item.get("valid") is True)}
                          for item in events["fix"]]
        warnings.append("GNSS status and satellite epochs are explicit examples; NMEA is derived from those epochs, not used to invent receiver logs.")
    if "connections" not in events or events["connections"] == []:
        nearest = {kind: min((record for record in catalog["records"] if record["kind"] == kind),
                             key=lambda record: (distance_m(anchor, Point(record["lat"], record["lon"])), record["id"]))["id"]
                   for kind in KINDS}
        events["connections"] = []
        for item in events["activity"]:
            stopped = item.get("type") == "STILL"
            events["connections"].append({"t_ms": item["t_ms"], "wifi_id": nearest["wifi"] if stopped else None,
                                           "cell_id": nearest["cell"], "ble_ids": [nearest["ble"]] if stopped else []})
        warnings.append("Connection states are example fixtures; radio visibility is modeled catalog visibility, not a scan or BLE GATT connection.")
    return events


def compile_scenario(route: dict[str, Any], *, name: str = "Probe scenario", seed: int = 42,
                     speed_mps: float | None = None, altitude_datum: str | None = None,
                     geoid_separation_m: float | None = None, example_height_m: float = 25,
                     utc_origin: str | None = None, catalog_document: dict[str, Any] | None = None,
                     fixtures: dict[str, Any] | None = None) -> dict[str, Any]:
    fixtures = {} if fixtures is None else fixtures
    require(isinstance(fixtures, dict), "fixtures must be an object")
    keys_match(fixtures, set(), {"schema", "version", "profiles", "events", "warnings"}, "fixtures")
    if "schema" in fixtures:
        require(fixtures.get("schema") == "hooking.fixtures" and type(fixtures.get("version")) is int and fixtures["version"] == 1,
                "unsupported fixture schema version")
    trajectory, origin, altitude_provenance, warnings = route_document(
        route, speed_mps, altitude_datum, geoid_separation_m, example_height_m, utc_origin)
    if catalog_document is None:
        catalog = {"source": "Explicit example fixtures; no survey imported", "complete": False,
                   "observed_at": None, "records": []}
    else:
        catalog, catalog_warnings = import_catalog(catalog_document)
        warnings.extend(catalog_warnings)
    anchor = Point(trajectory[0]["lat"], trajectory[0]["lon"])
    catalog = supplement_catalog(catalog, anchor, warnings)
    profiles = deepcopy(DEFAULT_PROFILES)
    supplied_profiles = fixtures.get("profiles", {})
    require(isinstance(supplied_profiles, dict), "profiles must be an object")
    profiles.update(deepcopy(supplied_profiles))
    if "rates_hz" in supplied_profiles:
        require(isinstance(supplied_profiles["rates_hz"], dict), "rates_hz must be an object")
        profiles["rates_hz"] = {**DEFAULT_RATES, **supplied_profiles["rates_hz"]}
    events = populate_events(fixtures, catalog, anchor, warnings, trajectory[-1]["t_ms"])
    fixture_warnings = fixtures.get("warnings", [])
    require(isinstance(fixture_warnings, list), "fixture warnings must be an array")
    warnings.extend(text_value(warning, "warning") for warning in fixture_warnings)
    warnings.append("All playback outputs are simulated; activity, power, mount, magnetic baseline, pressure reference and generated noise are example/model inputs.")
    scenario = {"schema": "hooking.scenario", "version": 1, "simulated": True,
                "name": name, "seed": seed, "utc_origin_ms": origin, "trajectory": trajectory,
                "altitude_provenance": altitude_provenance, "catalog": catalog,
                "profiles": profiles, "events": events, "warnings": list(dict.fromkeys(warnings))}
    validate_scenario(scenario)
    require(len(json.dumps(scenario, ensure_ascii=False, allow_nan=False).encode("utf-8")) <= MAX_BYTES,
            "compiled scenario exceeds 16 MiB")
    return scenario


def validate_catalog(catalog: Any) -> None:
    require(isinstance(catalog, dict), "catalog must be an object")
    keys_match(catalog, {"source", "complete", "observed_at", "records"}, {"catalog_checked_at"}, "catalog")
    text_value(catalog.get("source"), "catalog source")
    require(type(catalog.get("complete")) is bool, "catalog complete must be true or false")
    if catalog.get("observed_at") is not None:
        text_value(catalog["observed_at"], "catalog observed_at")
    if catalog.get("catalog_checked_at") is not None:
        text_value(catalog["catalog_checked_at"], "catalog_checked_at")
    records = catalog.get("records")
    require(isinstance(records, list) and len(records) <= MAX_RADIOS, "catalog must have at most 10000 records")
    for record in records:
        require(isinstance(record, dict), "catalog record must be an object")
        keys_match(record, {"kind", "id", "lat", "lon", "fields", "provenance"},
                   {"source", "observed_at", "catalog_checked_at"}, "radio")
        require(isinstance(record.get("kind"), str) and record["kind"] in KINDS, "unsupported radio kind")
        text_value(record.get("id"), "radio ID", 512)
        bounded_number(record.get("lat"), "radio latitude", -90, 90)
        bounded_number(record.get("lon"), "radio longitude", -180, 180)
        fields, provenance = record.get("fields"), record.get("provenance")
        require(isinstance(fields, dict) and isinstance(provenance, dict), "radio fields/provenance must be objects")
        require(all(isinstance(value, str) and value in PROVENANCE for value in provenance.values()), "invalid radio field provenance")
        require(all(field in provenance for field in ("id", "lat", "lon", *fields)), "every radio field and identity/coordinate needs provenance")
        finite_tree(fields, limit=16)
        for key in ("source", "observed_at", "catalog_checked_at"):
            if record.get(key) is not None:
                text_value(record[key], key)


def validate_profiles(profiles: Any) -> None:
    require(isinstance(profiles, dict), "profiles must be an object")
    require(set(profiles) <= set(DEFAULT_PROFILES), "unknown profile field")
    mount = profiles.get("mount", DEFAULT_PROFILES["mount"])
    require(isinstance(mount, list) and len(mount) == 9, "mount must contain nine rotation values")
    mount = [finite_number(value, "mount value") for value in mount]
    for row in range(3):
        for other in range(3):
            dot = sum(mount[row * 3 + k] * mount[other * 3 + k] for k in range(3))
            require(abs(dot - (1 if row == other else 0)) <= 1e-6, "mount must be orthonormal")
    determinant = (mount[0] * (mount[4] * mount[8] - mount[5] * mount[7])
                   - mount[1] * (mount[3] * mount[8] - mount[5] * mount[6])
                   + mount[2] * (mount[3] * mount[7] - mount[4] * mount[6]))
    require(abs(determinant - 1) <= 1e-6, "mount must be a right-handed rotation, not a reflection")
    magnetic = profiles.get("magnetic_enu_ut", DEFAULT_PROFILES["magnetic_enu_ut"])
    require(isinstance(magnetic, list) and len(magnetic) == 3, "magnetic_enu_ut must contain three values")
    for value in magnetic:
        finite_number(value, "magnetic field")
    require(type(profiles.get("noise", False)) is bool, "noise must be boolean")
    bounded_number(profiles.get("qnh_hpa", 1013.25), "qnh_hpa", 300, 1200)
    rates = profiles.get("rates_hz", {})
    require(isinstance(rates, dict) and set(rates) <= set(DEFAULT_RATES), "unknown sensor rate channel")
    for channel, rate in rates.items():
        bounded_number(rate, "sensor rate", 0.001, 100)
        if channel == "gnss":
            require(rate == 1, "GNSS/NMEA/status cadence is fixed at 1 Hz")


def validate_events(events: Any, duration: int, records: list[dict[str, Any]]) -> None:
    require(isinstance(events, dict), "events must be an object")
    require(set(events) <= {"fix", "activity", "power", "gnss", "connections", "steps"}, "unknown event type")
    ids = {kind: {record["id"] for record in records if record["kind"] == kind} for kind in KINDS}
    for kind, epochs in events.items():
        if kind == "steps":
            validate_steps(epochs, duration)
            continue
        require(isinstance(epochs, list) and 1 <= len(epochs) <= MAX_POINTS, "present event epochs must be a nonempty bounded array")
        previous = -1
        for epoch in epochs:
            require(isinstance(epoch, dict), "event epoch must be an object")
            moment = integer(epoch.get("t_ms"), "event t_ms", 0, duration)
            require(moment > previous, "each event channel must have strictly increasing timestamps")
            require(previous != -1 or moment == 0, "every present event channel must start at t_ms=0")
            previous = moment
            if kind == "fix":
                keys_match(epoch, {"t_ms", "valid"}, set(), "fix event")
                require(type(epoch.get("valid")) is bool, "fix valid must be boolean")
            elif kind == "activity":
                keys_match(epoch, {"t_ms", "type", "cadence_hz"}, set(), "activity event")
                require(isinstance(epoch.get("type"), str) and epoch["type"] in ACTIVITIES, "unsupported activity type")
                cadence = bounded_number(epoch.get("cadence_hz"), "cadence_hz", 0, 10)
                require(epoch["type"] in ("WALKING", "RUNNING") or cadence == 0,
                        "only WALKING and RUNNING have a step cadence")
                require("steps" not in events or cadence == 0,
                        "explicit steps and nonzero activity cadence are ambiguous")
            elif kind == "power":
                keys_match(epoch, {"t_ms", "battery_pct", "charging", "thermal_status"}, set(), "power event")
                bounded_number(epoch.get("battery_pct"), "battery_pct", 0, 100)
                require(type(epoch.get("charging")) is bool, "charging must be boolean")
                require(isinstance(epoch.get("thermal_status"), str) and epoch["thermal_status"] in THERMAL_STATES, "unsupported thermal status")
            elif kind == "connections":
                keys_match(epoch, {"t_ms", "wifi_id", "cell_id", "ble_ids"}, set(), "connection event")
                for key, radio_kind in (("wifi_id", "wifi"), ("cell_id", "cell")):
                    identity = epoch.get(key)
                    require(identity is None or isinstance(identity, str) and identity in ids[radio_kind],
                            f"{key} must reference an existing {radio_kind} record or be null")
                ble_ids = epoch.get("ble_ids")
                require(isinstance(ble_ids, list) and all(isinstance(identity, str) and identity in ids["ble"] for identity in ble_ids),
                        "ble_ids must reference existing BLE records")
                require(len(ble_ids) == len(set(ble_ids)), "ble_ids must not contain duplicates")
            else:
                keys_match(epoch, {"t_ms", "satellites"}, set(), "GNSS event")
                satellites = epoch.get("satellites")
                require(isinstance(satellites, list) and len(satellites) <= 64, "GNSS epoch must contain at most 64 satellites")
                seen = set()
                for satellite in satellites:
                    require(isinstance(satellite, dict), "satellite must be an object")
                    keys_match(satellite, {"constellation", "svid", "cn0_dbhz", "elevation_deg", "azimuth_deg", "used_in_fix", "has_ephemeris", "has_almanac"}, {"carrier_hz"}, "satellite")
                    require(isinstance(satellite.get("constellation"), str) and satellite["constellation"] in CONSTELLATIONS, "unknown satellite constellation")
                    svid = integer(satellite.get("svid"), "svid", 1, 999)
                    identity = (satellite["constellation"], svid)
                    require(identity not in seen, "duplicate satellite constellation/SVID in epoch")
                    seen.add(identity)
                    bounded_number(satellite.get("cn0_dbhz"), "cn0_dbhz", 0, 100)
                    bounded_number(satellite.get("elevation_deg"), "elevation_deg", -90, 90)
                    azimuth = bounded_number(satellite.get("azimuth_deg"), "azimuth_deg", 0, 360)
                    require(azimuth < 360, "azimuth must be below 360 degrees")
                    for key in ("used_in_fix", "has_ephemeris", "has_almanac"):
                        require(type(satellite.get(key)) is bool, f"{key} must be boolean")
                    if "carrier_hz" in satellite:
                        require(finite_number(satellite["carrier_hz"], "carrier_hz") > 0, "carrier_hz must be positive")


def validate_steps(script: Any, duration: int) -> None:
    require(isinstance(script, dict), "steps must be an object")
    keys_match(script, {"start", "deltas"}, set(), "steps")
    total = integer(script["start"], "steps start", 0, MAX_STEP_COUNT)
    deltas = script["deltas"]
    require(isinstance(deltas, list) and len(deltas) <= MAX_STEP_DELTAS,
            "steps deltas must be an array of at most 100000 events")
    previous = -1
    for event in deltas:
        require(isinstance(event, dict), "step delta must be an object")
        keys_match(event, {"t_ms", "delta"}, set(), "step delta")
        moment = integer(event["t_ms"], "step t_ms", 0, duration)
        require(moment > previous, "step delta times must strictly increase")
        previous = moment
        total += integer(event["delta"], "step delta", 1, MAX_STEP_COUNT)
        require(total <= MAX_STEP_COUNT, "cumulative steps exceed the safe integer limit")


def validate_still_trajectory(trajectory: list[dict[str, Any]], activities: list[dict[str, Any]]) -> None:
    """Check half-open overlaps in O(route knots + activity epochs), without sampling."""
    segment = 0
    for index, activity in enumerate(activities):
        if activity["type"] != "STILL":
            continue
        start = activity["t_ms"]
        end = activities[index + 1]["t_ms"] if index + 1 < len(activities) else trajectory[-1]["t_ms"]
        while segment < len(trajectory) - 2 and trajectory[segment + 1]["t_ms"] <= start:
            segment += 1
        while segment < len(trajectory) - 1 and start < end and trajectory[segment]["t_ms"] < end:
            a, b = trajectory[segment], trajectory[segment + 1]
            if max(start, a["t_ms"]) < min(end, b["t_ms"]):
                moved = distance_m(Point(a["lat"], a["lon"]), Point(b["lat"], b["lon"])) > 1e-8
                require(not moved and a["alt_msl_m"] == b["alt_msl_m"],
                        "STILL activity overlaps moving trajectory (position or MSL altitude)")
            if b["t_ms"] >= end:
                break
            segment += 1


def validate_scenario(scenario: dict[str, Any]) -> dict[str, Any]:
    finite_tree(scenario)
    require(isinstance(scenario, dict), "scenario must be an object")
    keys_match(scenario, {"schema", "version", "simulated", "name", "seed", "utc_origin_ms", "trajectory", "altitude_provenance", "catalog"},
               {"profiles", "events", "warnings"}, "scenario")
    require(scenario.get("schema") == "hooking.scenario" and type(scenario.get("version")) is int and scenario["version"] == 1,
            "unsupported scenario schema/version")
    require(scenario.get("simulated") is True, "scenario must explicitly set simulated:true")
    text_value(scenario.get("name"), "name", 200)
    integer(scenario.get("seed"), "seed", -2147483648, 2147483647)
    integer(scenario.get("utc_origin_ms"), "utc_origin_ms", 0, MAX_UTC_ORIGIN_MS)
    text_value(scenario.get("altitude_provenance"), "altitude_provenance")
    trajectory = scenario.get("trajectory")
    require(isinstance(trajectory, list) and 2 <= len(trajectory) <= MAX_POINTS, "trajectory requires 2..100000 knots")
    previous = -1
    coordinates = []
    for knot in trajectory:
        require(isinstance(knot, dict), "trajectory knot must be an object")
        keys_match(knot, {"t_ms", "lat", "lon", "alt_msl_m", "geoid_sep_m"}, set(), "trajectory knot")
        moment = integer(knot.get("t_ms"), "trajectory t_ms", 0, MAX_DURATION_MS)
        require(moment > previous, "trajectory times must strictly increase")
        previous = moment
        lat = bounded_number(knot.get("lat"), "latitude", -90, 90)
        lon = bounded_number(knot.get("lon"), "longitude", -180, 180)
        bounded_number(knot.get("alt_msl_m"), "MSL altitude", -12000, 20000)
        bounded_number(knot.get("geoid_sep_m"), "geoid separation", -1000, 1000)
        coordinates.append([lon, lat])
    require(trajectory[0]["t_ms"] == 0, "trajectory must start at t_ms=0")
    Route(coordinates)  # Apply the same short-arc and antipodal guard as legacy host routes.
    validate_catalog(scenario.get("catalog"))
    records = scenario["catalog"]["records"]
    require(len({(record["kind"], record["id"]) for record in records}) == len(records), "catalog IDs must be unique within each kind")
    validate_profiles(scenario.get("profiles", {}))
    validate_events(scenario.get("events", {}), previous, records)
    validate_still_trajectory(trajectory, scenario.get("events", {}).get("activity", []))
    warnings = scenario.get("warnings", [])
    require(isinstance(warnings, list), "warnings must be an array")
    for warning in warnings:
        text_value(warning, "warning")
    return scenario


def summary(scenario: dict[str, Any]) -> dict[str, Any]:
    records = scenario["catalog"]["records"]
    return {"ok": True, "schema": scenario["schema"], "version": scenario["version"],
            "name": scenario["name"], "simulated": True,
            "duration_s": scenario["trajectory"][-1]["t_ms"] / 1000,
            "trajectory_points": len(scenario["trajectory"]),
            "catalog": {"source": scenario["catalog"]["source"], "complete": scenario["catalog"]["complete"],
                        "observed_at": scenario["catalog"].get("observed_at"),
                        "records": {kind: sum(record["kind"] == kind for record in records) for kind in sorted(KINDS)},
                        "survey_records": sum(record["provenance"].get("id") == "survey" for record in records),
                        "example_records": sum(record["provenance"].get("id") == "example" for record in records)},
            "events": {kind: len(epochs["deltas"]) if kind == "steps" else len(epochs)
                       for kind, epochs in scenario.get("events", {}).items()},
            "rates_hz": {**DEFAULT_RATES, **scenario.get("profiles", {}).get("rates_hz", {})},
            "warnings": scenario.get("warnings", [])}


def site_catalog(path: Path, site_id: str) -> dict[str, Any]:
    document = read_json(path)
    sites = document.get("sites")
    require(isinstance(sites, dict) and site_id in sites and isinstance(sites[site_id], dict), "selected site was not found")
    library = sites[site_id].get("wigle_library")
    require(isinstance(library, dict), "selected site has no saved WiGLE library")
    return library


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="compile local GeoJSON and survey/example fixtures to a scenario")
    build.add_argument("--route", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--fixtures", type=Path, help="optional local hooking.fixtures version 1 JSON")
    build.add_argument("--name", default="Probe scenario")
    build.add_argument("--seed", type=int, default=42)
    build.add_argument("--speed-mps", type=float, help="required only for untimed routes")
    build.add_argument("--utc-origin", help="UTC ISO timestamp for untimed routes; otherwise a labeled example origin is used")
    build.add_argument("--altitude-datum", choices=("msl", "ellipsoid"))
    build.add_argument("--geoid-separation-m", type=float)
    build.add_argument("--example-height-m", type=float, default=25)
    source = build.add_mutually_exclusive_group()
    source.add_argument("--site", help="exact local saved site ID (imports only its WiGLE library)")
    source.add_argument("--catalog", type=Path, help="local normalized catalog or WiGLE library JSON")
    build.add_argument("--sites-file", type=Path, default=ROOT / "data" / "sites.json")
    for name in ("validate", "preview"):
        command = commands.add_parser(name, help=f"{name} a local compiled scenario without device or network access")
        command.add_argument("--scenario", type=Path, required=True)
        if name == "preview":
            command.add_argument("--limit", type=int, default=5, help="trajectory knots to display, 1..100")
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "build":
            catalog = site_catalog(args.sites_file, args.site) if args.site else (read_json(args.catalog) if args.catalog else None)
            scenario = compile_scenario(read_json(args.route), name=args.name, seed=args.seed,
                                        speed_mps=args.speed_mps, altitude_datum=args.altitude_datum,
                                        geoid_separation_m=args.geoid_separation_m, example_height_m=args.example_height_m,
                                        utc_origin=args.utc_origin, catalog_document=catalog,
                                        fixtures=read_json(args.fixtures) if args.fixtures else None)
            write_json(args.output, scenario)
            output = summary(scenario)
            output["output"] = str(args.output.expanduser().resolve())
        else:
            scenario = validate_scenario(read_json(args.scenario))
            output = summary(scenario)
            if args.command == "preview":
                limit = integer(args.limit, "limit", 1, 100)
                output["trajectory_preview"] = scenario["trajectory"][:limit]
                output["truncated"] = len(scenario["trajectory"]) > limit
        canonical = json.dumps(scenario, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")
        output["scenario_sha256"] = hashlib.sha256(canonical).hexdigest()
        print(json.dumps(output, indent=2, ensure_ascii=False, allow_nan=False))
        return 0
    except (HooksError, OSError, ValueError, TypeError, RecursionError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
