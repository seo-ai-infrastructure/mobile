"""Bounded WiGLE library searches, not live radio measurements.

SSID filtering is a heuristic. QoS is catalogue quality, not RSSI.
"""

from __future__ import annotations

import json
import math
import os
import re
import stat
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests

from geo import bbox, haversine_m

API_BASE = "https://api.wigle.net/api/v2"
MAX_PAGES = 3
PAGE_SIZE = 100
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_COLLECTION_BYTES = 8 * 1024 * 1024
MAX_AGE_DAYS = 730
ROW_STORAGE = "query_envelopes_v1"
QUERY_KINDS = {
    "network/search": "wifi",
    "wifi/search": "wifi",
    "cell/search": "cell",
    "bluetooth/search": "bt",
}
PUBLIC_SSIDS = {
    "xfinitywifi",
    "xfinity mobile",
    "attwifi",
    "spectrumwifi",
    "spectrum wifi",
    "google starbucks",
    "starbucks",
    "boingo",
    "optimumwifi",
    "free wifi",
    "public wifi",
    "guest wifi",
}
MOBILE_PREFIXES = ("iphone", "androidap", "direct-", "galaxy", "pixel hotspot")
MAC_RE = re.compile(r"^[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}$")
WIFI_TYPES = {"wifi", "infra", "infrastructure", "wlan", ""}


def _query_rows(library: dict[str, Any]) -> dict[str, list[dict]]:
    queries = library.get("queries")
    if not isinstance(queries, list):
        raise ValueError("WiGLE query envelopes must be a list")
    rows: dict[str, list[dict]] = {"wifi": [], "cell": [], "bt": []}
    for query in queries:
        if not isinstance(query, dict) or not isinstance(query.get("endpoint"), str) or query["endpoint"] not in QUERY_KINDS:
            raise ValueError("WiGLE library contains an unsupported query entry")
        response = query.get("response")
        if not isinstance(response, dict) or response.get("success") is not True:
            raise ValueError("WiGLE library query is not a successful response envelope")
        results = response.get("results")
        if not isinstance(results, list) or any(not isinstance(row, dict) for row in results):
            raise ValueError("WiGLE library query results must be a list of records")
        rows[QUERY_KINDS[query["endpoint"]]].extend(results)
    return rows


def library_rows(library: dict[str, Any], kind: str) -> list[dict]:
    if not isinstance(library, dict) or kind not in {"wifi", "cell", "bt"}:
        raise ValueError("Expected a WiGLE library and kind wifi, cell, or bt")
    if "row_storage" not in library:
        rows = library.get(kind, [])
        if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
            raise ValueError(f"WiGLE library {kind} must be a list of records")
        return list(rows)
    if library["row_storage"] != ROW_STORAGE:
        raise ValueError("Unsupported WiGLE library row storage format")
    return _query_rows(library)[kind]


def pack_library(library: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(library, dict):
        raise ValueError("Expected a WiGLE library object")
    packed = dict(library)
    if "row_storage" in library:
        if library["row_storage"] != ROW_STORAGE:
            raise ValueError("Unsupported WiGLE library row storage format")
        query_rows = _query_rows(library)
        for kind in ("wifi", "cell", "bt"):
            if kind in library and library[kind] != query_rows[kind]:
                raise ValueError("WiGLE packed library contains conflicting duplicate rows")
            packed.pop(kind, None)
        return packed
    if not library.get("queries"):
        return packed
    try:
        query_rows = _query_rows(library)
        if any(library_rows(library, kind) != query_rows[kind] for kind in ("wifi", "cell", "bt")):
            return packed
    except ValueError:
        return packed
    for kind in ("wifi", "cell", "bt"):
        packed.pop(kind, None)
    packed["row_storage"] = ROW_STORAGE
    return packed


def _invalid_json_constant(value: str) -> None:
    raise ValueError("Non-finite JSON number")


def _envelope_size(envelope: dict[str, Any]) -> int:
    return len(json.dumps(envelope, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8"))


def _auth() -> tuple[str, str]:
    name = os.environ.get("WIGLE_API_NAME", "").strip()
    token = os.environ.get("WIGLE_API_TOKEN", "").strip()
    if not name or not token:
        raise RuntimeError("Set WIGLE_API_NAME and WIGLE_API_TOKEN")
    return name, token


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None or value == "":
        return None
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return number if math.isfinite(number) else None


def _anchor(lat: Any, lon: Any, radius_m: Any) -> tuple[float, float, float]:
    latitude, longitude, radius = _number(lat), _number(lon), _number(radius_m)
    if latitude is None or not -90 <= latitude <= 90:
        raise ValueError("Latitude must be finite and within [-90, 90]")
    if longitude is None or not -180 <= longitude <= 180:
        raise ValueError("Longitude must be finite and within [-180, 180]")
    if radius is None or not 0 < radius <= 10_000:
        raise ValueError("WiGLE radius must be finite and within (0, 10000] meters")
    return latitude, longitude, radius


def read_airgrid_import(path: str) -> dict[str, list[dict[str, Any]]]:
    file = Path(path).expanduser()
    if not file.is_file():
        raise ValueError("AirGrid import must be a regular JSON file")
    with file.open("rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError("AirGrid import must be a regular file")
        raw = stream.read(16 * 1024 * 1024 + 1)
    if len(raw) > 16 * 1024 * 1024:
        raise ValueError("AirGrid import exceeds 16 MiB limit")
    document = json.loads(raw.decode("utf-8"))
    if not isinstance(document, dict) or not document.get("success") or not isinstance(document.get("results"), list):
        raise ValueError("Invalid AirGrid JSON structure: requires success:true and results array")
    classified: dict[str, list[dict[str, Any]]] = {"wifi": [], "cell": [], "bt": []}
    for row in document["results"]:
        if not isinstance(row, dict):
            continue
        kind = str(row.get("kind") or row.get("type") or "").lower()
        if kind in {"wifi", "infra", "infrastructure", "wlan"}:
            classified["wifi"].append(row)
        elif kind in {"cell", "gsm", "lte", "nr", "umts", "cdma"}:
            classified["cell"].append(row)
        elif kind in {"bluetooth", "bt"}:
            classified["bt"].append(row)
    return classified


def _bounds(lat: float, lon: float, radius_m: float) -> dict[str, float]:
    box = bbox(lat, lon, radius_m)
    if box["latrange1"] < -90 or box["latrange2"] > 90 or box["longrange1"] < -180 or box["longrange2"] > 180:
        raise ValueError("This search crosses a pole or the date line; choose a smaller area")
    return box


def _search(path: str, params: dict[str, Any]) -> dict[str, Any]:
    if path not in {"network/search", "cell/search"}:
        raise ValueError("Unsupported WiGLE search endpoint")
    try:
        response = requests.get(
            f"{API_BASE}/{path}",
            params=params,
            auth=_auth(),
            timeout=(10, 30),
            headers={"Accept": "application/json"},
            allow_redirects=False,
            stream=True,
        )
    except requests.RequestException:
        raise RuntimeError(f"WiGLE {path} connection failed; no further queries were sent") from None
    try:
        if response.status_code == 429:
            raise RuntimeError("WiGLE rate or daily query limit reached; no further queries were sent")
        if response.status_code in {401, 403}:
            raise RuntimeError("WiGLE authentication or API access failed")
        if not 200 <= response.status_code < 300:
            raise RuntimeError(f"WiGLE {path} returned HTTP {response.status_code}; redirects are not followed")
        chunks: list[bytes] = []
        size = 0
        for chunk in response.iter_content(chunk_size=64 * 1024):
            size += len(chunk)
            if size > MAX_RESPONSE_BYTES:
                raise RuntimeError("WiGLE response exceeded the bounded cache size")
            chunks.append(chunk)
        try:
            data = json.loads(b"".join(chunks), parse_constant=_invalid_json_constant)
            if _envelope_size(data) > MAX_RESPONSE_BYTES:
                raise RuntimeError("WiGLE normalized response exceeded the bounded cache size")
        except (ValueError, UnicodeError, RecursionError):
            raise RuntimeError(f"WiGLE {path} returned invalid JSON") from None
        if not isinstance(data, dict) or data.get("success") is not True or not isinstance(data.get("results"), list):
            raise RuntimeError(f"WiGLE {path} did not return a successful search envelope")
        if len(data["results"]) > PAGE_SIZE or any(not isinstance(row, dict) for row in data["results"]):
            raise RuntimeError(f"WiGLE {path} returned an invalid or oversized results page")
        return data
    except requests.RequestException:
        raise RuntimeError(f"WiGLE {path} response could not be read completely") from None
    finally:
        response.close()


def _sim_codes(mcc: Any, mnc: Any) -> tuple[str, str] | None:
    if isinstance(mcc, bool) or isinstance(mnc, bool):
        return None
    country, network = str(mcc).strip(), str(mnc).strip()
    if not re.fullmatch(r"[0-9]{3}", country) or not re.fullmatch(r"[0-9]{2,3}", network):
        return None
    if country == "000":
        return None
    return country, network


def search_area(
    lat: float,
    lon: float,
    radius_m: float = 80.0,
    lastupdt: str | None = None,
    *,
    mcc: str | None = None,
    mnc: str | None = None,
    cell_radius_m: float = 2500.0,
) -> dict[str, Any]:
    lat, lon, radius_m = _anchor(lat, lon, radius_m)
    _, _, cell_radius_m = _anchor(lat, lon, cell_radius_m)
    now = datetime.now(timezone.utc)
    cutoff = lastupdt if lastupdt is not None else (now - timedelta(days=MAX_AGE_DAYS)).strftime("%Y%m%d")
    try:
        parsed_cutoff = datetime.strptime(cutoff, "%Y%m%d").replace(tzinfo=timezone.utc)
        if not re.fullmatch(r"[0-9]{8}", cutoff) or parsed_cutoff > now:
            raise ValueError
    except (TypeError, ValueError):
        raise ValueError("lastupdt must be a non-future YYYYMMDD date") from None

    common: dict[str, Any] = {"resultsPerPage": PAGE_SIZE, "onlymine": "false", "lastupdt": cutoff}
    searches = [("network/search", "wifi", {**common, **_bounds(lat, lon, radius_m)})]
    sim = _sim_codes(mcc, mnc)
    warnings: list[str] = []
    if sim:
        searches.append(
            (
                "cell/search",
                "cell",
                {**common, **_bounds(lat, lon, cell_radius_m), "operator": "".join(sim)},
            )
        )
    else:
        warnings.append("Cell search omitted: the device's exact SIM MCC/MNC is unavailable.")
    output: dict[str, Any] = {
        "wifi": [],
        "cell": [],
        "bt": [],
        "queries": [],
        "checked_at": now.isoformat(),
        "warnings": warnings,
        "complete": True,
        "errors": [],
    }
    collected_bytes = 0
    for endpoint, kind, params in searches:
        cursor: str | None = None
        seen: set[str] = set()
        for page in range(MAX_PAGES):
            page_params = {**params, **({"searchAfter": cursor} if cursor else {})}
            fetched_at = datetime.now(timezone.utc).isoformat()
            try:
                envelope = _search(endpoint, page_params)
            except RuntimeError:
                if not output["queries"]:
                    raise
                message = f"{endpoint}: query failed; earlier successful pages were retained and all further queries stopped."
                output["complete"] = False
                warnings.append(message)
                output["errors"].append(
                    {
                        "endpoint": endpoint,
                        "params": page_params,
                        "attempted_at": fetched_at,
                        "code": "QUERY_FAILED",
                        "message": message,
                    }
                )
                return output
            output["queries"].append(
                {"endpoint": endpoint, "params": page_params, "fetched_at": fetched_at, "response": envelope}
            )
            output[kind].extend(envelope["results"])
            collected_bytes += _envelope_size(envelope)
            if collected_bytes > MAX_COLLECTION_BYTES:
                message = f"{endpoint}: aggregate response budget exceeded; last successful page retained."
                output["complete"] = False
                warnings.append(message)
                output["errors"].append(
                    {
                        "endpoint": endpoint,
                        "code": "COLLECTION_SIZE_LIMIT",
                        "attempted_at": fetched_at,
                        "response_bytes": collected_bytes,
                        "budget_bytes": MAX_COLLECTION_BYTES,
                    }
                )
                return output
            next_cursor = envelope.get("searchAfter")
            if next_cursor is None or next_cursor == "" or not envelope["results"]:
                break
            if not isinstance(next_cursor, str) or len(next_cursor) > 2048:
                output["complete"] = False
                warnings.append(f"{endpoint}: invalid pagination cursor; collection is incomplete.")
                output["errors"].append({"endpoint": endpoint, "code": "INVALID_CURSOR", "attempted_at": fetched_at})
                break
            if next_cursor in seen:
                output["complete"] = False
                warnings.append(f"{endpoint}: repeated pagination cursor; collection is incomplete.")
                output["errors"].append({"endpoint": endpoint, "code": "REPEATED_CURSOR", "attempted_at": fetched_at})
                break
            seen.add(next_cursor)
            cursor = next_cursor
            if page == MAX_PAGES - 1:
                output["complete"] = False
                warnings.append(f"{endpoint}: stopped at {MAX_PAGES} pages; more records may exist.")
                output["errors"].append({"endpoint": endpoint, "code": "PAGE_LIMIT", "attempted_at": fetched_at})
    return output


def _parse_wigle_time(value: Any) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        raw = str(int(value))
        for fmt, n in (("%Y%m%d%H%M%S", 14), ("%Y%m%d", 8)):
            if len(raw) == n:
                try:
                    return datetime.strptime(raw, fmt).replace(tzinfo=timezone.utc)
                except ValueError:
                    continue
        return None
    if not isinstance(value, str):
        return None
    text = value.strip().replace("Z", "+00:00")
    if " " in text and "T" not in text:
        text = text.replace(" ", "T", 1)
    try:
        parsed = datetime.fromisoformat(text)
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
    except ValueError:
        pass
    digits = re.sub(r"[^0-9]", "", value)
    for fmt, n in (("%Y%m%d%H%M%S", 14), ("%Y%m%d", 8)):
        if len(digits) >= n:
            try:
                return datetime.strptime(digits[:n], fmt).replace(tzinfo=timezone.utc)
            except ValueError:
                continue
    return None


def _observed_at(row: dict[str, Any]) -> datetime | None:
    for key in ("lasttime", "lastupdt"):
        parsed = _parse_wigle_time(row.get(key))
        if parsed is not None:
            return parsed
    return None


def _distance_and_age(row: dict[str, Any], lat: float, lon: float, radius_m: float, now: datetime) -> tuple[float, float] | None:
    rlat, rlon = _number(row.get("trilat")), _number(row.get("trilong"))
    if rlat is None or rlon is None or not -90 <= rlat <= 90 or not -180 <= rlon <= 180:
        return None
    observed = _observed_at(row)
    if observed is None:
        return None
    age = (now - observed).total_seconds() / 86400
    if not 0 <= age <= MAX_AGE_DAYS:
        return None
    distance = haversine_m(lat, lon, rlat, rlon)
    return (distance, age) if distance <= radius_m else None


def _qos(row: dict[str, Any]) -> int | None:
    value = row.get("qos")
    if value is None or value == "":
        return 0
    number = _number(value)
    if number is None or not number.is_integer() or not 0 <= number <= 7:
        return None
    return int(number)


def _address_ok(row: dict[str, Any], street: str, zipcode: str) -> bool:
    expected_zip = str(zipcode).strip().split("-")[0]
    actual_zip = str(row.get("postalcode") or "").strip().split("-")[0]
    if expected_zip and actual_zip and expected_zip != actual_zip:
        return False

    def road_name(value: str) -> str:
        text = re.sub(r"[^a-z0-9 ]", " ", value.lower())
        text = re.sub(r"^\s*\d+[a-z]?\s+", "", text)
        aliases = {
            "street": "st", "avenue": "ave", "road": "rd", "drive": "dr",
            "boulevard": "blvd", "lane": "ln", "court": "ct", "place": "pl",
            "north": "n", "south": "s", "east": "e", "west": "w",
        }
        return " ".join(aliases.get(word, word) for word in text.split())

    actual_road = row.get("road")
    return not street or not isinstance(actual_road, str) or not actual_road.strip() or road_name(street) == road_name(actual_road)


def valid_bssid(value: Any) -> bool:
    if not isinstance(value, str) or not MAC_RE.fullmatch(value.strip()):
        return False
    octets = bytes.fromhex(value.replace(":", ""))
    return not (octets[0] & 1) and octets != b"\x00" * 6 and octets != b"\xff" * 6


def _wifi_type_ok(row: dict[str, Any]) -> bool:
    return str(row.get("type") or "wifi").strip().lower() in WIFI_TYPES


def diagnose_wifi(
    rows: list[dict],
    lat: float,
    lon: float,
    street: str = "",
    zipcode: str = "",
    radius_m: float = 80.0,
) -> dict[str, Any]:
    lat, lon, radius_m = _anchor(lat, lon, radius_m)
    now = datetime.now(timezone.utc)
    reasons: dict[str, Any] = {
        "total": len(rows),
        "not_wifi_type": 0,
        "bad_ssid": 0,
        "public_or_hidden": 0,
        "mobile_hotspot": 0,
        "bad_bssid": 0,
        "address_mismatch": 0,
        "no_time_or_too_old": 0,
        "too_far": 0,
        "bad_qos": 0,
        "eligible": 0,
        "nearest_m": None,
        "sample_ssids": [],
        "pick_radius_m": radius_m,
    }
    nearest = None
    for row in rows:
        if not isinstance(row, dict):
            continue
        if not _wifi_type_ok(row):
            reasons["not_wifi_type"] += 1
            continue
        ssid, bssid = row.get("ssid"), row.get("netid")
        if not isinstance(ssid, str) or not ssid.strip() or len(ssid.encode("utf-8")) > 32:
            reasons["bad_ssid"] += 1
            continue
        if any(ord(char) < 32 or ord(char) == 127 for char in ssid):
            reasons["bad_ssid"] += 1
            continue
        normalized = ssid.strip().lower()
        if normalized in PUBLIC_SSIDS or normalized in {"[hidden]", "<hidden>", "hidden", "<unknown ssid>"}:
            reasons["public_or_hidden"] += 1
            continue
        if normalized.startswith(MOBILE_PREFIXES):
            reasons["mobile_hotspot"] += 1
            continue
        comment = str(row.get("comment") or "").lower()
        if any(marker in comment for marker in ("in-motion", "in motion", "mobile hotspot", "vehicle hotspot")):
            reasons["mobile_hotspot"] += 1
            continue
        if not valid_bssid(bssid):
            reasons["bad_bssid"] += 1
            continue
        if not _address_ok(row, street, zipcode):
            reasons["address_mismatch"] += 1
            continue
        if _qos(row) is None:
            reasons["bad_qos"] += 1
            continue
        rlat, rlon = _number(row.get("trilat")), _number(row.get("trilong"))
        dist = haversine_m(lat, lon, rlat, rlon) if rlat is not None and rlon is not None else None
        if dist is not None and (nearest is None or dist < nearest):
            nearest = dist
        observed = _observed_at(row)
        if observed is None:
            reasons["no_time_or_too_old"] += 1
            continue
        age = (now - observed).total_seconds() / 86400
        if not 0 <= age <= MAX_AGE_DAYS:
            reasons["no_time_or_too_old"] += 1
            continue
        if dist is None or dist > radius_m:
            reasons["too_far"] += 1
            continue
        reasons["eligible"] += 1
        if len(reasons["sample_ssids"]) < 8:
            reasons["sample_ssids"].append({"ssid": ssid, "bssid": bssid, "m": round(dist, 1)})
    reasons["nearest_m"] = None if nearest is None else round(nearest, 1)
    return reasons


def pick_home_wifi(
    rows: list[dict],
    lat: float,
    lon: float,
    street: str = "",
    zipcode: str = "",
    radius_m: float = 80.0,
) -> dict | None:
    lat, lon, radius_m = _anchor(lat, lon, radius_m)
    now = datetime.now(timezone.utc)
    scored: list[tuple[tuple[Any, ...], dict]] = []
    for index, row in enumerate(rows):
        if not isinstance(row, dict) or not _wifi_type_ok(row):
            continue
        ssid, bssid = row.get("ssid"), row.get("netid")
        if not isinstance(ssid, str) or not ssid.strip() or not valid_bssid(bssid):
            continue
        normalized = ssid.strip().lower()
        if normalized in PUBLIC_SSIDS or normalized in {"[hidden]", "<hidden>", "hidden", "<unknown ssid>"}:
            continue
        if normalized.startswith(MOBILE_PREFIXES):
            continue
        if len(ssid.encode("utf-8")) > 32 or any(ord(char) < 32 or ord(char) == 127 for char in ssid):
            continue
        comment = str(row.get("comment") or "").lower()
        if any(marker in comment for marker in ("in-motion", "in motion", "mobile hotspot", "vehicle hotspot")):
            continue
        if not _address_ok(row, street, zipcode):
            continue
        stats, qos = _distance_and_age(row, lat, lon, radius_m, now), _qos(row)
        if stats is None or qos is None:
            continue
        distance, age = stats
        score = distance + radius_m * (0.35 * age / MAX_AGE_DAYS + 0.15 * (7 - qos) / 7)
        scored.append(((score, distance, age, -qos, str(bssid).lower(), ssid, index), row))
    return min(scored, key=lambda item: item[0])[1] if scored else None


def cell_matches_sim(row: dict, mcc: Any, mnc: Any) -> bool:
    expected = _sim_codes(mcc, mnc)
    if not isinstance(row, dict) or expected is None:
        return False
    explicit_mcc, explicit_mnc = row.get("mcc"), row.get("mnc")
    explicit_present = explicit_mcc not in (None, "") or explicit_mnc not in (None, "")
    if explicit_present and _sim_codes(explicit_mcc, explicit_mnc) != expected:
        return False
    operator = row.get("operator")
    operator_present = operator not in (None, "")
    if operator_present:
        if isinstance(operator, bool) or str(operator).strip() != "".join(expected):
            return False
    return explicit_present or operator_present


def _strict_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str) and re.fullmatch(r"[0-9]+", value) and len(value) <= 12:
        return int(value)
    return None


def parse_cell_ids(row: dict) -> tuple[int | None, int | None]:
    if not isinstance(row, dict):
        return None, None
    radio_type = str(row.get("type") or "").upper()
    lac, cid = _strict_int(row.get("lac")), _strict_int(row.get("cid"))
    if radio_type not in {"LTE", "NR"} or lac is None or cid is None:
        return None, None
    max_lac, max_cid = ((2**16 - 1, 2**28 - 1) if radio_type == "LTE" else (2**24 - 1, 2**36 - 1))
    if not 1 <= lac <= max_lac or not 1 <= cid <= max_cid:
        return None, None
    return lac, cid


def pick_cell(
    rows: list[dict],
    lat: float,
    lon: float,
    mcc: str,
    mnc: str,
    radius_m: float = 2500.0,
) -> dict | None:
    lat, lon, radius_m = _anchor(lat, lon, radius_m)
    now = datetime.now(timezone.utc)
    scored: list[tuple[tuple[Any, ...], dict]] = []
    for index, row in enumerate(rows):
        if not cell_matches_sim(row, mcc, mnc):
            continue
        lac, cid = parse_cell_ids(row)
        if lac is None or cid is None:
            continue
        stats, qos = _distance_and_age(row, lat, lon, radius_m, now), _qos(row)
        if stats is None or qos is None:
            continue
        distance, age = stats
        score = distance + radius_m * (0.35 * age / MAX_AGE_DAYS + 0.15 * (7 - qos) / 7)
        scored.append(((score, distance, age, -qos, str(row.get("type")), lac, cid, index), row))
    return min(scored, key=lambda item: item[0])[1] if scored else None
