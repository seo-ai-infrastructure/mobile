"""Geodesy helpers: meters, bearings, boxes, jitter, elevation."""

from __future__ import annotations

import math
import random
from typing import Any

import requests

EARTH_M = 6371000.0


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_M * math.asin(math.sqrt(a))


def destination(lat: float, lon: float, bearing_deg: float, distance_m: float) -> tuple[float, float]:
    br = math.radians(bearing_deg)
    ang = distance_m / EARTH_M
    p1 = math.radians(lat)
    l1 = math.radians(lon)
    p2 = math.asin(math.sin(p1) * math.cos(ang) + math.cos(p1) * math.sin(ang) * math.cos(br))
    l2 = l1 + math.atan2(
        math.sin(br) * math.sin(ang) * math.cos(p1),
        math.cos(ang) - math.sin(p1) * math.sin(p2),
    )
    return math.degrees(p2), (math.degrees(l2) + 540) % 360 - 180


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def bbox(lat: float, lon: float, radius_m: float) -> dict[str, float]:
    dlat = radius_m / 111_320.0
    safe_cos = max(math.cos(math.radians(lat)), 0.01)
    dlon = radius_m / (111_320.0 * safe_cos)
    return {
        "latrange1": max(-90.0, lat - dlat),
        "latrange2": min(90.0, lat + dlat),
        "longrange1": max(-180.0, lon - dlon),
        "longrange2": min(180.0, lon + dlon),
    }


def jitter(lat: float, lon: float, radius_m: float) -> tuple[float, float]:
    return destination(lat, lon, random.uniform(0, 360), random.uniform(0, radius_m))


def densify(points: list[tuple[float, float]], spacing_m: float = 30.0) -> list[tuple[float, float]]:
    if len(points) < 2:
        return points
    out: list[tuple[float, float]] = [points[0]]
    for i in range(1, len(points)):
        a, b = points[i - 1], points[i]
        d = haversine_m(a[0], a[1], b[0], b[1])
        n = max(1, int(d / spacing_m))
        for k in range(1, n + 1):
            t = k / n
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    return out


def sample_speeds(
    points: list[tuple[float, float]],
    cruise_mps: float,
    stop_every_n: int = 18,
    stop_s: tuple[float, float] = (4.0, 28.0),
) -> list[dict[str, Any]]:
    samples = []
    t = 0.0
    for i, p in enumerate(points):
        if i == 0:
            samples.append({
                "t": 0.0, "lat": p[0], "lon": p[1],
                "speed_mps": 0.0, "bearing": 0.0, "state": "start",
            })
            continue
        prev = points[i - 1]
        d = haversine_m(prev[0], prev[1], p[0], p[1])
        br = bearing_deg(prev[0], prev[1], p[0], p[1])
        stopped = i % stop_every_n == 0
        if stopped:
            hold = random.uniform(*stop_s)
            t += hold
            samples.append({
                "t": t, "lat": prev[0], "lon": prev[1],
                "speed_mps": 0.0, "bearing": br, "state": "stop",
            })
        speed = max(2.5, cruise_mps * random.uniform(0.85, 1.15))
        dt = max(d / speed, 0.4)
        t += dt
        samples.append({
            "t": t, "lat": p[0], "lon": p[1],
            "speed_mps": speed, "bearing": br, "state": "move",
        })
    if samples:
        samples[-1]["speed_mps"] = 0.0
        samples[-1]["state"] = "arrive"
    return samples


def fetch_elevation_m(lat: float, lon: float) -> float | None:
    try:
        r = requests.get(
            "https://api.open-meteo.com/v1/elevation",
            params={"latitude": lat, "longitude": lon},
            timeout=10,
        )
        r.raise_for_status()
        vals = r.json().get("elevation") or []
        return float(vals[0]) if vals else None
    except Exception:
        return None


def mph_to_mps(mph: float) -> float:
    return mph * 0.44704


def mps_to_mph(mps: float) -> float:
    return mps / 0.44704
