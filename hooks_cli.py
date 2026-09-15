#!/usr/bin/env python3
"""Local controller for the DuoPlus hook module and its location probe.

The wire protocol uses authenticated NDJSON over an explicit adb loopback
forward. Location simulation is restricted to our own probe application.
This module does not load .env, call provider APIs, or fetch routes online.
"""

from __future__ import annotations

import argparse
from bisect import bisect_right
from dataclasses import dataclass
import json
import math
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import time
from typing import Any, Callable, Iterator


ROOT = Path(__file__).resolve().parent
DEFAULT_TOKEN_FILE = ROOT / "plugin" / ".local" / "auth-token"
PROBE_PACKAGE = "com.example.duoplus_probe"
DEVICE_PORT = 9999
PROTOCOL = 1
UPDATE_INTERVAL = 1.0
MAX_REQUEST_BYTES = 8192
MAX_RESPONSE_BYTES = 262144
MAX_ROUTE_BYTES = 16 * 1024 * 1024
MAX_ROUTE_POINTS = 100000
MAX_SEQUENCE = 9007199254740991
MAX_FLOAT = 3.4028234663852886e38
EARTH_RADIUS_M = 6371008.8


class HooksError(Exception):
    """An actionable input, bridge, or scheduling error."""


class StreamInterrupted(HooksError):
    """A keyboard interruption after acknowledged cleanup."""


def finite_number(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise HooksError(f"{name} must be a finite number")
    try:
        result = float(value)
    except (ValueError, OverflowError) as exc:
        raise HooksError(f"{name} must be a finite number") from exc
    if not math.isfinite(result):
        raise HooksError(f"{name} must be a finite number")
    return result


def bounded_number(value: Any, name: str, low: float, high: float) -> float:
    result = finite_number(value, name)
    if not low <= result <= high:
        raise HooksError(f"{name} must be between {low:g} and {high:g}")
    return result


def positive_number(value: Any, name: str, maximum: float = MAX_FLOAT) -> float:
    result = finite_number(value, name)
    if result <= 0 or result > maximum:
        raise HooksError(f"{name} must be positive and at most {maximum:g}")
    return result


def float32(value: float) -> float:
    return struct.unpack("!f", struct.pack("!f", value))[0]


def accuracy_m(value: Any) -> float:
    result = positive_number(value, "accuracy")
    if float32(result) <= 0:
        raise HooksError("accuracy is too small to represent as an Android location accuracy")
    return result


def integer(value: Any, name: str, low: int, high: int) -> int:
    if type(value) is not int or not low <= value <= high:
        raise HooksError(f"{name} must be an integer between {low} and {high}")
    return value


def package_name(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", value, re.ASCII):
        raise HooksError("package must be an exact Android application package name")
    return value


def require_probe(package: str) -> None:
    if package != PROBE_PACKAGE:
        raise HooksError(f"location stream and clear are restricted to {PROBE_PACKAGE}")


def reject_json_constant(value: str) -> None:
    raise HooksError(f"non-finite JSON value {value} is not allowed")


def read_token(path: Path) -> str:
    try:
        with path.open("r", encoding="utf-8") as handle:
            raw = handle.read(1026)
    except (OSError, UnicodeError) as exc:
        raise HooksError(f"cannot read the module token file: {path}") from exc
    value = raw.strip()
    if len(raw) > 1025 or not value or len(value) > 1024 or any(char.isspace() for char in value):
        raise HooksError("module token must be a single nonempty value of at most 1024 characters")
    return value


def validate_fix(fix: dict[str, Any]) -> None:
    if fix.get("type") != "fix" or fix.get("simulated") is not True:
        raise HooksError("fix must have type=fix and simulated=true")
    integer(fix.get("seq"), "seq", 1, MAX_SEQUENCE)
    bounded_number(fix.get("lat"), "lat", -90, 90)
    bounded_number(fix.get("lon"), "lon", -180, 180)
    bounded_number(fix.get("speed_mps"), "speed_mps", 0, MAX_FLOAT)
    accuracy_m(fix.get("accuracy"))
    bearing = finite_number(fix.get("bearing"), "bearing")
    if not 0 <= bearing < 360 or float32(bearing) >= 360:
        raise HooksError("bearing must be at least 0 and below 360")
    if "alt" in fix:
        finite_number(fix["alt"], "alt")


@dataclass(frozen=True)
class Point:
    lat: float
    lon: float
    alt: float | None = None


def distance_m(a: Point, b: Point) -> float:
    """Great-circle distance using the short arc, including across the date line."""
    lat1, lat2 = math.radians(a.lat), math.radians(b.lat)
    dlat = lat2 - lat1
    dlon = math.radians(b.lon - a.lon)
    hav = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(min(1.0, max(0.0, hav))))


def bearing_degrees(a: Point, b: Point) -> float:
    lat1, lat2 = math.radians(a.lat), math.radians(b.lat)
    dlon = math.radians(b.lon - a.lon)
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return math.degrees(math.atan2(y, x)) % 360


def interpolate(a: Point, b: Point, fraction: float, length: float) -> Point:
    if fraction <= 0:
        return a
    if fraction >= 1:
        return b
    lat1, lon1 = math.radians(a.lat), math.radians(a.lon)
    angle = length * fraction / EARTH_RADIUS_M
    direction = math.radians(bearing_degrees(a, b))
    lat2 = math.asin(max(-1.0, min(1.0,
        math.sin(lat1) * math.cos(angle) + math.cos(lat1) * math.sin(angle) * math.cos(direction))))
    lon2 = lon1 + math.atan2(
        math.sin(direction) * math.sin(angle) * math.cos(lat1),
        math.cos(angle) - math.sin(lat1) * math.sin(lat2),
    )
    altitude = None if a.alt is None else a.alt * (1 - fraction) + b.alt * fraction  # type: ignore[operator]
    return Point(math.degrees(lat2), (math.degrees(lon2) + 180) % 360 - 180, altitude)


class Route:
    """A local LineString, parameterized by cumulative horizontal distance."""

    def __init__(self, coordinates: Any):
        if not isinstance(coordinates, list) or not 2 <= len(coordinates) <= MAX_ROUTE_POINTS:
            raise HooksError(f"LineString must contain 2 to {MAX_ROUTE_POINTS} coordinates")
        self.points: list[Point] = []
        self.cumulative = [0.0]
        dimension = None
        for index, coordinate in enumerate(coordinates):
            if not isinstance(coordinate, list) or len(coordinate) not in (2, 3):
                raise HooksError(f"coordinate {index} must be [longitude, latitude] with optional altitude")
            if dimension is not None and dimension != len(coordinate):
                raise HooksError("all coordinates must use the same number of dimensions")
            dimension = len(coordinate)
            point = Point(
                bounded_number(coordinate[1], f"coordinate {index} latitude", -90, 90),
                bounded_number(coordinate[0], f"coordinate {index} longitude", -180, 180),
                finite_number(coordinate[2], f"coordinate {index} altitude") if dimension == 3 else None,
            )
            if self.points:
                length = distance_m(self.points[-1], point)
                if length >= math.pi * EARTH_RADIUS_M - 0.001:
                    raise HooksError("antipodal route vertices need an intermediate waypoint")
                self.cumulative.append(self.cumulative[-1] + length)
            self.points.append(point)
        self.length_m = self.cumulative[-1]

    @classmethod
    def load(cls, path: Path) -> Route:
        try:
            with path.open("rb") as handle:
                raw = handle.read(MAX_ROUTE_BYTES + 1)
            if len(raw) > MAX_ROUTE_BYTES:
                raise HooksError(f"route file exceeds {MAX_ROUTE_BYTES} bytes")
            data = json.loads(raw.decode("utf-8"), parse_constant=reject_json_constant)
        except (OSError, UnicodeError, ValueError, RecursionError) as exc:
            raise HooksError(f"cannot read a valid GeoJSON route: {path}") from exc
        if not isinstance(data, dict):
            raise HooksError("route must be a GeoJSON LineString or a Feature containing one")
        if data.get("type") == "Feature":
            data = data.get("geometry")
        if not isinstance(data, dict) or data.get("type") != "LineString":
            raise HooksError("route must be a GeoJSON LineString or a Feature containing one")
        return cls(data.get("coordinates"))

    def at(self, distance: float) -> Point:
        if distance >= self.length_m:
            return self.points[-1]
        if distance <= 0:
            return self.points[0]
        index = bisect_right(self.cumulative, distance) - 1
        length = self.cumulative[index + 1] - self.cumulative[index]
        return interpolate(self.points[index], self.points[index + 1],
                           (distance - self.cumulative[index]) / length, length)

    def interval_count(self, speed_mps: float) -> int:
        speed_mps = positive_number(speed_mps, "speed_mps")
        ratio = self.length_m / speed_mps
        if not math.isfinite(ratio) or ratio >= MAX_SEQUENCE:
            raise HooksError("route duration exceeds the protocol sequence range; increase speed or shorten the route")
        return max(1, math.ceil(ratio - 1e-12)) if self.length_m > 0 else 0

    def fixes(self, speed_mps: float, accuracy: float = 3.0) -> Iterator[dict[str, Any]]:
        accuracy = accuracy_m(accuracy)
        intervals = self.interval_count(speed_mps)
        previous_bearing = 0.0
        for tick in range(intervals + 1):
            point = self.at(min(tick * speed_mps, self.length_m)) if tick < intervals else self.points[-1]
            if tick < intervals:
                next_point = self.at(min((tick + 1) * speed_mps, self.length_m))
                actual_speed = distance_m(point, next_point) / UPDATE_INTERVAL
                if actual_speed > 0:
                    previous_bearing = bearing_degrees(point, next_point)
                    # Android stores bearings as float32; near-360 values can round up.
                    if float32(previous_bearing) >= 360:
                        previous_bearing = 0.0
            else:
                actual_speed = 0.0
            fix: dict[str, Any] = {
                "type": "fix", "seq": tick + 1, "simulated": True,
                "lat": point.lat, "lon": point.lon,
                "speed_mps": actual_speed, "bearing": previous_bearing, "accuracy": accuracy,
            }
            if point.alt is not None:
                fix["alt"] = point.alt
            validate_fix(fix)
            yield fix


class ControlClient:
    """Single authenticated session; no request or fix is automatically replayed."""

    def __init__(self, package: str, token: str, port: int = DEVICE_PORT, timeout: float = 2.0):
        self.package = package_name(package)
        self._token = token
        self.port = integer(port, "port", 1, 65535)
        self.timeout = positive_number(timeout, "timeout", 60)
        self._socket: socket.socket | None = None
        self._buffer = bytearray()
        self.hello: dict[str, Any] | None = None

    def __enter__(self) -> ControlClient:
        self.connect()
        return self

    def __exit__(self, *unused: Any) -> None:
        self.close()

    def close(self) -> None:
        if self._socket is not None:
            self._socket.close()
        self._socket = None
        self._buffer.clear()

    def connect(self) -> None:
        if self._socket is not None:
            return
        try:
            self._socket = socket.create_connection(("127.0.0.1", self.port), timeout=self.timeout)
            response = self._exchange({"type": "hello", "protocol": PROTOCOL, "token": self._token})
            self._validate_ack(response, "hello")
            if type(response.get("protocol")) is not int or response["protocol"] != PROTOCOL:
                raise HooksError("module handshake has an unsupported protocol")
            if response.get("package") != self.package:
                raise HooksError("module handshake package does not match --package; check the target and adb forward")
            if not isinstance(response.get("process"), str) or not response["process"]:
                raise HooksError("module handshake is missing its process identity")
            self.hello = response
        except (OSError, HooksError) as exc:
            self.close()
            if isinstance(exc, HooksError):
                raise
            raise HooksError("cannot connect to the loopback module bridge; check adb forward and the running target") from exc

    def _exchange(self, request: dict[str, Any]) -> dict[str, Any]:
        if self._socket is None:
            raise HooksError("module bridge is not connected")
        try:
            frame = json.dumps(request, allow_nan=False, separators=(",", ":")).encode("utf-8") + b"\n"
            if len(frame) > MAX_REQUEST_BYTES:
                raise HooksError(f"request exceeds {MAX_REQUEST_BYTES} bytes")
            deadline = time.monotonic() + self.timeout
            self._socket.settimeout(self.timeout)
            self._socket.sendall(frame)
            while True:
                newline = self._buffer.find(b"\n")
                if newline >= 0:
                    if newline + 1 > MAX_RESPONSE_BYTES:
                        raise HooksError(f"module response exceeds {MAX_RESPONSE_BYTES} bytes")
                    raw = bytes(self._buffer[:newline])
                    del self._buffer[:newline + 1]
                    response = json.loads(raw.decode("utf-8"), parse_constant=reject_json_constant)
                    if not isinstance(response, dict):
                        raise HooksError("module response must be a JSON object")
                    return response
                if len(self._buffer) >= MAX_RESPONSE_BYTES:
                    raise HooksError(f"module response exceeds {MAX_RESPONSE_BYTES} bytes")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise HooksError("module response timed out")
                self._socket.settimeout(remaining)
                chunk = self._socket.recv(min(65536, MAX_RESPONSE_BYTES - len(self._buffer)))
                if not chunk:
                    raise HooksError("module disconnected before acknowledging the request")
                self._buffer.extend(chunk)
        except (OSError, ValueError, UnicodeError, RecursionError, HooksError) as exc:
            self.close()
            if isinstance(exc, HooksError):
                raise
            if isinstance(exc, (TimeoutError, socket.timeout)):
                raise HooksError("module response timed out") from exc
            raise HooksError("module bridge failed or returned invalid JSON") from exc

    @staticmethod
    def _validate_ack(response: dict[str, Any], kind: str, seq: int | None = None) -> None:
        if response.get("ok") is not True:
            # Never reflect arbitrary remote error text; it could contain credentials.
            raise HooksError(f"module rejected the {kind} request")
        if response.get("type") != kind:
            raise HooksError(f"module returned the wrong acknowledgement type for {kind}")
        if seq is not None and (type(response.get("seq")) is not int or response["seq"] != seq):
            raise HooksError("module fix acknowledgement sequence does not match the sent fix")

    def request(self, kind: str, **fields: Any) -> dict[str, Any]:
        if kind in ("fix", "clear"):
            require_probe(self.package)
        if "type" in fields:
            raise HooksError("request type must not be overridden")
        self.connect()
        try:
            response = self._exchange({"type": kind, **fields})
            self._validate_ack(response, kind, fields.get("seq") if kind == "fix" else None)
            return response
        except HooksError:
            self.close()
            raise

    def send_fix(self, fix: dict[str, Any]) -> dict[str, Any]:
        require_probe(self.package)
        validate_fix(fix)
        return self.request("fix", **{key: value for key, value in fix.items() if key != "type"})

    def clear(self) -> dict[str, Any]:
        require_probe(self.package)
        return self.request("clear")

    def status(self) -> dict[str, Any]:
        return self.request("status")

    def events(self, after: int = 0) -> dict[str, Any]:
        integer(after, "after", 0, MAX_SEQUENCE)
        response = self.request("events", after=after)
        if not isinstance(response.get("events"), list):
            raise HooksError("module events response is missing its event list")
        integer(response.get("last_id"), "last_id", 0, MAX_SEQUENCE)
        integer(response.get("dropped"), "dropped", 0, MAX_SEQUENCE)
        return response


def wait_for_deadline(deadline: float, clock: Callable[[], float], sleep: Callable[[float], None]) -> None:
    while True:
        remaining = deadline - clock()
        if remaining < -UPDATE_INTERVAL:
            raise HooksError("stream fell more than one second behind schedule; stopped without replaying old fixes")
        if remaining <= 0:
            return
        sleep(remaining)


def stream_route(client: ControlClient, route: Route, speed_mps: float, accuracy: float = 3.0,
                 clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], None] = time.sleep) -> dict[str, Any]:
    """Send at absolute monotonic deadlines and acknowledge cleanup on every exit."""
    require_probe(client.package)
    intervals = route.interval_count(speed_mps)
    accuracy_m(accuracy)
    started = clock()
    count = 0
    try:
        for tick, fix in enumerate(route.fixes(speed_mps, accuracy)):
            wait_for_deadline(started + tick * UPDATE_INTERVAL, clock, sleep)
            client.send_fix(fix)
            count += 1
        # Keep the terminal stationary fix visible for one full scheduled interval.
        wait_for_deadline(started + (intervals + 1) * UPDATE_INTERVAL, clock, sleep)
    except BaseException as original:
        try:
            client.clear()
        except Exception as cleanup_error:
            raise HooksError("stream stopped, but clear was not acknowledged; the module must expire or drop its writer session") from cleanup_error
        if isinstance(original, KeyboardInterrupt):
            raise StreamInterrupted("stream interrupted; clear acknowledged") from original
        raise
    else:
        client.clear()
    return {
        "ok": True, "type": "stream", "package": client.package, "simulated": True,
        "fixes_acknowledged": count, "distance_m": route.length_m,
        "travel_duration_s": intervals, "elapsed_s": clock() - started, "cleared": True,
    }


def run_adb(serial: str, arguments: list[str], timeout: float = 120) -> str:
    if not serial or serial != serial.strip() or any(ord(char) < 32 for char in serial):
        raise HooksError("serial must be an explicit adb device serial")
    try:
        completed = subprocess.run(["adb", "-s", serial, *arguments], check=True,
                                   capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError as exc:
        raise HooksError("adb was not found; install Android platform-tools and add adb to PATH") from exc
    except subprocess.TimeoutExpired as exc:
        raise HooksError("adb operation timed out; inspect the device before retrying") from exc
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout or "no diagnostic output").strip()
        raise HooksError(f"adb operation failed: {detail}") from exc
    return completed.stdout.strip()


def install_module(serial: str, apk: Path) -> dict[str, Any]:
    apk = apk.expanduser().resolve()
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        raise HooksError("--apk must point to an existing module APK file")
    remote = "/sdcard/Download/duoplus-hooks.apk"
    # Check the selected device's loader before uploading an otherwise unusable APK.
    run_adb(serial, ["shell", "dplus", "dump"], timeout=30)
    run_adb(serial, ["push", str(apk), remote])
    result = run_adb(serial, ["shell", "dplus", "install", f"patch:{remote}"])
    return {"ok": True, "type": "install", "serial": serial, "remote_apk": remote, "output": result}


def forward_port(serial: str, port: int, device_port: int = DEVICE_PORT) -> dict[str, Any]:
    integer(port, "port", 1, 65535)
    integer(device_port, "device_port", 1, 65535)
    run_adb(serial, ["forward", "--no-rebind", f"tcp:{port}", f"tcp:{device_port}"], timeout=30)
    return {"ok": True, "type": "forward", "serial": serial, "local_port": port, "device_port": device_port}


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)

    def add_route(command: argparse.ArgumentParser) -> None:
        command.add_argument("--route", type=Path, required=True, help="local GeoJSON LineString or Feature")
        command.add_argument("--speed-mps", type=float, required=True, help="positive route traversal speed in metres/second")
        command.add_argument("--accuracy", type=float, default=3.0, help="simulated accuracy in metres (default: 3)")

    def add_connection(command: argparse.ArgumentParser) -> None:
        command.add_argument("--package", required=True, help="exact package expected in the module handshake")
        command.add_argument("--token-file", type=Path, default=DEFAULT_TOKEN_FILE,
                             help="local module token file; its contents are never printed")
        command.add_argument("--port", type=int, default=DEVICE_PORT, help="local adb-forwarded port (default: 9999)")
        command.add_argument("--timeout", type=float, default=2.0, help="whole-response timeout, 0 < seconds <= 60 (default: 2)")

    preview = commands.add_parser("preview", help="inspect resampled simulated fixes locally without a device")
    add_route(preview)
    preview.add_argument("--limit", type=int, default=20, help="maximum fixes to display, 1..10000 (default: 20)")
    stream = commands.add_parser("stream", help=f"stream at 1 Hz to {PROBE_PACKAGE} only")
    add_route(stream)
    add_connection(stream)
    for name, help_text in (("status", "read module status"), ("events", "read observed URL metadata"),
                            ("clear", f"clear simulated location in {PROBE_PACKAGE} only")):
        command = commands.add_parser(name, help=help_text)
        add_connection(command)
        if name == "events":
            command.add_argument("--after", type=int, default=0, help="last observed event ID (default: 0)")
    install = commands.add_parser("install", help="check the DuoPlus loader, then push and install a built module on one selected device")
    install.add_argument("--serial", required=True)
    install.add_argument("--apk", type=Path, required=True)
    forward = commands.add_parser("forward", help="explicitly create the selected device's loopback adb forward")
    forward.add_argument("--serial", required=True)
    forward.add_argument("--port", type=int, default=DEVICE_PORT, help="local forwarded port (default: 9999)")
    forward.add_argument("--device-port", type=int, default=DEVICE_PORT,
                         help="module listener port on the selected device (default: 9999)")
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command in ("preview", "stream"):
            route = Route.load(args.route.expanduser())
            intervals = route.interval_count(args.speed_mps)
            accuracy_m(args.accuracy)
        if args.command == "preview":
            limit = integer(args.limit, "limit", 1, 10000)
            fixes = []
            for index, fix in enumerate(route.fixes(args.speed_mps, args.accuracy)):
                if index >= limit:
                    break
                fixes.append({"at_s": index, "fix": fix})
            value = {
                "ok": True, "type": "preview", "simulated": True, "distance_m": route.length_m,
                "travel_duration_s": intervals, "clear_at_s": intervals + 1,
                "sample_count": intervals + 1, "truncated": intervals + 1 > limit, "fixes": fixes,
                "speed_convention": "displacement to the next one-second fix; terminal speed is zero",
            }
        elif args.command == "install":
            value = install_module(args.serial, args.apk)
        elif args.command == "forward":
            value = forward_port(args.serial, args.port, args.device_port)
        else:
            if args.command in ("stream", "clear"):
                require_probe(args.package)
            if args.command == "events":
                integer(args.after, "after", 0, MAX_SEQUENCE)
            token = read_token(args.token_file.expanduser())
            with ControlClient(args.package, token, args.port, args.timeout) as client:
                if args.command == "stream":
                    value = stream_route(client, route, args.speed_mps, args.accuracy)
                elif args.command == "status":
                    value = client.status()
                elif args.command == "events":
                    value = client.events(args.after)
                else:
                    value = client.clear()
        print(json.dumps(value, indent=2, allow_nan=False))
        return 0
    except StreamInterrupted as exc:
        print(json.dumps({"ok": False, "error": str(exc)}), file=sys.stderr)
        return 130
    except (HooksError, OSError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print(json.dumps({"ok": False, "error": "interrupted"}), file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
