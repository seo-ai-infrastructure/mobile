"""Offline protocol and scheduling checks. Only ephemeral loopback sockets are used."""

from contextlib import contextmanager, redirect_stderr, redirect_stdout
import io
import json
import math
from pathlib import Path
import socketserver
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import hooks_cli as hooks


class FakeClock:
    def __init__(self):
        self.now = 100.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class FakeClient:
    package = hooks.PROBE_PACKAGE

    def __init__(self, clock, ack_delay=0.2):
        self.clock = clock
        self.ack_delay = ack_delay
        self.sent = []
        self.clear_times = []

    def send_fix(self, fix):
        self.sent.append((self.clock.now, fix))
        self.clock.now += self.ack_delay
        return {"ok": True, "type": "fix", "seq": fix["seq"]}

    def clear(self):
        self.clear_times.append(self.clock.now)
        return {"ok": True, "type": "clear"}


@contextmanager
def fake_server(reply=None, package=hooks.PROBE_PACKAGE):
    """Run a bounded fake module; reply may return raw bytes for malformed frames."""
    received = []
    connected = []

    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            connected.append(True)
            self.request.settimeout(2)
            while True:
                try:
                    line = self.rfile.readline(hooks.MAX_REQUEST_BYTES + 1)
                    if not line:
                        break
                    request = json.loads(line)
                    received.append(request)
                    if reply is not None:
                        response = reply(request)
                        if response is False:
                            break
                    else:
                        response = None
                    if response is None:
                        kind = request["type"]
                        response = {"ok": True, "type": kind}
                        if kind == "hello":
                            response.update(protocol=1, package=package, process=package)
                        elif kind == "fix":
                            response["seq"] = request["seq"]
                        elif kind == "events":
                            response.update(events=[{"id": 1, "host": "example.test", "path": "/demo"}], last_id=1, dropped=0)
                    wire = response if isinstance(response, bytes) else json.dumps(response).encode() + b"\n"
                    self.wfile.write(wire)
                    self.wfile.flush()
                except (OSError, ValueError):
                    break

    class Server(socketserver.ThreadingTCPServer):
        daemon_threads = True
        allow_reuse_address = True

    server = Server(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    try:
        yield server.server_address[1], received, connected
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def short_route():
    # An eastbound route with two full intervals and one short final interval.
    return hooks.Route([[0, 0], [0.000225, 0]])


class RouteTests(unittest.TestCase):
    def test_resampling_includes_endpoint_and_forward_displacement_speed(self):
        route = short_route()
        fixes = list(route.fixes(10))
        self.assertEqual(len(fixes), 4)
        self.assertEqual(fixes[-1]["lon"], 0.000225)
        self.assertEqual(fixes[-1]["speed_mps"], 0)
        self.assertLess(fixes[-2]["speed_mps"], 10)
        for first, second in zip(fixes, fixes[1:]):
            displacement = hooks.distance_m(hooks.Point(first["lat"], first["lon"]),
                                            hooks.Point(second["lat"], second["lon"]))
            self.assertAlmostEqual(first["speed_mps"], displacement, places=6)
            self.assertAlmostEqual(first["bearing"], 90, places=6)
        self.assertTrue(all(fix["simulated"] is True for fix in fixes))
        self.assertEqual([fix["seq"] for fix in fixes], [1, 2, 3, 4])

    def test_speed_at_corner_matches_next_fix_instead_of_cumulative_distance(self):
        route = hooks.Route([[0, 0], [0.00005, 0], [0.00005, 0.00010]])
        fixes = list(route.fixes(10))
        self.assertLess(fixes[0]["speed_mps"], 10)
        self.assertGreater(fixes[0]["bearing"], 0)
        self.assertLess(fixes[0]["bearing"], 90)

    def test_date_line_takes_short_arc(self):
        route = hooks.Route([[179.99995, 0], [-179.99995, 0]])
        self.assertLess(route.length_m, 12)
        midpoint = route.at(route.length_m / 2)
        self.assertAlmostEqual(abs(midpoint.lon), 180, places=7)
        fixes = list(route.fixes(3))
        self.assertTrue(all(abs(fix["lon"]) > 179 for fix in fixes))

    def test_duplicate_vertices_and_stationary_route_are_finite(self):
        route = hooks.Route([[0, 0], [0, 0], [0.0001, 0], [0.0001, 0]])
        self.assertTrue(all(math.isfinite(fix["speed_mps"]) for fix in route.fixes(3)))
        stationary = list(hooks.Route([[2, 3], [2, 3]]).fixes(1))
        self.assertEqual(len(stationary), 1)
        self.assertEqual(stationary[0]["speed_mps"], 0)

    def test_altitude_interpolates(self):
        route = hooks.Route([[0, 0, 5], [0.0001, 0, 15]])
        self.assertAlmostEqual(route.at(route.length_m / 2).alt, 10)

    def test_bad_geometry_and_values_are_rejected(self):
        for coordinates in (
            [], [[0, 0]], [[0, 0], [181, 0]], [[0, 0], [0, -91]],
            [[0, 0], [float("nan"), 0]], [[0, 0], [False, 0]],
            [[0, 0], [0, 0, 1]], [[0, 0], [180, 0]], [[0, 0], [0, 0, float("inf")]],
        ):
            with self.subTest(coordinates=coordinates), self.assertRaises(hooks.HooksError):
                hooks.Route(coordinates)
        for speed in (0, -1, float("inf"), float("nan"), True, 1e-320):
            with self.subTest(speed=speed), self.assertRaises(hooks.HooksError):
                list(short_route().fixes(speed))

    def test_geojson_feature_load_and_nan_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "route.geojson"
            path.write_text(json.dumps({"type": "Feature", "geometry": {
                "type": "LineString", "coordinates": [[1, 2], [1.001, 2]]}}))
            self.assertGreater(hooks.Route.load(path).length_m, 100)
            path.write_text('{"type":"LineString","coordinates":[[0,0],[NaN,0]]}')
            with self.assertRaises(hooks.HooksError):
                hooks.Route.load(path)


class ScheduleTests(unittest.TestCase):
    def test_ack_latency_does_not_accumulate_and_terminal_fix_is_held(self):
        clock = FakeClock()
        client = FakeClient(clock)
        result = hooks.stream_route(client, short_route(), 10, clock=clock.monotonic, sleep=clock.sleep)
        self.assertEqual([at for at, _ in client.sent], [100, 101, 102, 103])
        self.assertEqual(client.clear_times, [104])
        self.assertEqual(client.sent[-1][1]["speed_mps"], 0)
        self.assertEqual(result["fixes_acknowledged"], 4)
        self.assertTrue(result["cleared"])

    def test_late_stream_clears_without_burst_replay(self):
        clock = FakeClock()
        client = FakeClient(clock, ack_delay=2.1)
        with self.assertRaisesRegex(hooks.HooksError, "behind schedule"):
            hooks.stream_route(client, short_route(), 10, clock=clock.monotonic, sleep=clock.sleep)
        self.assertEqual(len(client.sent), 1)
        self.assertEqual(len(client.clear_times), 1)

    def test_keyboard_interrupt_clears(self):
        clock = FakeClock()
        client = FakeClient(clock)

        def interrupt(_seconds):
            raise KeyboardInterrupt()

        with self.assertRaisesRegex(hooks.StreamInterrupted, "clear acknowledged"):
            hooks.stream_route(client, short_route(), 10, clock=clock.monotonic, sleep=interrupt)
        self.assertEqual(len(client.clear_times), 1)

    def test_clear_failure_is_not_reported_as_success(self):
        clock = FakeClock()
        client = FakeClient(clock, ack_delay=2.1)
        with patch.object(client, "clear", side_effect=hooks.HooksError("offline")):
            with self.assertRaisesRegex(hooks.HooksError, "clear was not acknowledged"):
                hooks.stream_route(client, short_route(), 10, clock=clock.monotonic, sleep=clock.sleep)

    def test_consumer_package_never_receives_simulation(self):
        clock = FakeClock()
        client = FakeClient(clock)
        client.package = "com.example.consumer"
        with self.assertRaisesRegex(hooks.HooksError, "restricted"):
            hooks.stream_route(client, short_route(), 10, clock=clock.monotonic, sleep=clock.sleep)
        self.assertEqual(client.sent, [])
        self.assertEqual(client.clear_times, [])


class ProtocolTests(unittest.TestCase):
    def test_handshake_fix_ack_events_and_clear(self):
        with fake_server() as (port, received, _):
            with hooks.ControlClient(hooks.PROBE_PACKAGE, "private-token", port) as client:
                client.send_fix(next(short_route().fixes(10)))
                self.assertEqual(client.status()["type"], "status")
                self.assertEqual(client.events()["events"][0]["host"], "example.test")
                client.clear()
            self.assertEqual(received[0], {"type": "hello", "protocol": 1, "token": "private-token"})
            self.assertEqual([item["type"] for item in received], ["hello", "fix", "status", "events", "clear"])
            self.assertIsNone(client._socket)

    def test_wrong_package_stops_before_any_fix(self):
        with fake_server(package="com.example.wrong") as (port, received, _):
            with self.assertRaisesRegex(hooks.HooksError, "package does not match"):
                with hooks.ControlClient(hooks.PROBE_PACKAGE, "private-token", port):
                    self.fail("a mismatched handshake was accepted")
            self.assertEqual([item["type"] for item in received], ["hello"])

    def test_wrong_sequence_closes_then_cleanup_uses_fresh_authenticated_session(self):
        def reply(request):
            if request["type"] == "fix":
                return {"ok": True, "type": "fix", "seq": request["seq"] + 1}

        clock = FakeClock()
        with fake_server(reply) as (port, received, connected):
            with hooks.ControlClient(hooks.PROBE_PACKAGE, "private-token", port) as client:
                with self.assertRaisesRegex(hooks.HooksError, "sequence"):
                    hooks.stream_route(client, short_route(), 10, clock=clock.monotonic, sleep=clock.sleep)
            self.assertEqual(len(connected), 2)
            self.assertEqual([item["type"] for item in received], ["hello", "fix", "hello", "clear"])

    def test_frame_limits_and_invalid_json_are_rejected(self):
        for raw in (b"x" * hooks.MAX_RESPONSE_BYTES, b"[]\n", b'{"ok":true,"type":"status","bad":NaN}\n',
                    b"\xff\n", b'{"ok":true,"type":"fix","seq":1}\n'):
            with self.subTest(prefix=raw[:40]):
                def reply(request):
                    return raw if request["type"] == "status" else None

                with fake_server(reply) as (port, _, _):
                    with hooks.ControlClient(hooks.PROBE_PACKAGE, "private-token", port) as client:
                        with self.assertRaises(hooks.HooksError):
                            client.status()

    def test_request_frame_limit_does_not_send_oversized_frame(self):
        with fake_server() as (port, received, _):
            with hooks.ControlClient(hooks.PROBE_PACKAGE, "private-token", port) as client:
                with self.assertRaisesRegex(hooks.HooksError, "request exceeds"):
                    client.request("status", padding="x" * hooks.MAX_REQUEST_BYTES)
            self.assertEqual(len(received), 1)

    def test_response_timeout_is_finite(self):
        def reply(request):
            if request["type"] == "status":
                time.sleep(0.2)

        with fake_server(reply) as (port, _, _):
            with hooks.ControlClient(hooks.PROBE_PACKAGE, "private-token", port, timeout=0.05) as client:
                started = time.monotonic()
                with self.assertRaisesRegex(hooks.HooksError, "timed out"):
                    client.status()
                self.assertLess(time.monotonic() - started, 0.3)

    def test_ack_boolean_sequence_and_remote_auth_details_are_rejected(self):
        for response in ({"ok": True, "type": "fix", "seq": True},
                         {"ok": False, "type": "fix", "error": "private-token"}):
            with self.subTest(response=response):
                with fake_server(lambda request: response if request["type"] == "fix" else None) as (port, _, _):
                    with hooks.ControlClient(hooks.PROBE_PACKAGE, "private-token", port) as client:
                        with self.assertRaises(hooks.HooksError) as caught:
                            client.send_fix(next(short_route().fixes(10)))
                        self.assertNotIn("private-token", str(caught.exception))

    def test_invalid_fix_is_rejected_locally(self):
        fix = next(short_route().fixes(10))
        for field, value in (("lat", 91), ("lon", float("nan")), ("accuracy", 0),
                             ("accuracy", 1e-100), ("speed_mps", -1), ("bearing", 360),
                             ("bearing", 359.999999), ("simulated", False),
                             ("seq", True), ("seq", hooks.MAX_SEQUENCE + 1)):
            with self.subTest(field=field, value=value), self.assertRaises(hooks.HooksError):
                hooks.validate_fix({**fix, field: value})


class CommandTests(unittest.TestCase):
    def test_preview_never_reads_credentials_or_connects(self):
        output = io.StringIO()
        route = hooks.ROOT / "examples" / "hooks" / "probe-route.geojson"
        with patch.object(hooks, "read_token", side_effect=AssertionError("credentials read")), \
                patch.object(hooks.socket, "create_connection", side_effect=AssertionError("connected")), \
                redirect_stdout(output):
            self.assertEqual(hooks.main(["preview", "--route", str(route), "--speed-mps", "2"]), 0)
        result = json.loads(output.getvalue())
        self.assertTrue(result["simulated"])
        self.assertFalse(result["truncated"])
        self.assertEqual(result["fixes"][-1]["fix"]["speed_mps"], 0)

    def test_cli_rejects_consumer_clear_before_token_or_network_access(self):
        output = io.StringIO()
        with patch.object(hooks, "read_token", side_effect=AssertionError("credentials read")), redirect_stderr(output):
            self.assertEqual(hooks.main(["clear", "--package", "com.example.consumer"]), 1)
        self.assertIn("restricted", output.getvalue())

    def test_token_is_not_printed_by_status(self):
        with tempfile.TemporaryDirectory() as directory:
            token_file = Path(directory) / "token"
            token_file.write_text("secret-test-token\n")
            with fake_server(package="com.example.consumer") as (port, _, _):
                output = io.StringIO()
                with redirect_stdout(output):
                    result = hooks.main(["status", "--package", "com.example.consumer", "--token-file", str(token_file),
                                         "--port", str(port)])
                self.assertEqual(result, 0)
                self.assertNotIn("secret-test-token", output.getvalue())

    def test_device_helpers_use_explicit_serial_and_argv_without_executing_adb(self):
        completed = type("Completed", (), {"stdout": "installed\n"})()
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "module with spaces.apk"
            apk.write_bytes(b"test fixture")
            with patch.object(hooks.subprocess, "run", return_value=completed) as run:
                hooks.install_module("emulator-5554", apk)
                hooks.forward_port("emulator-5554", 10001)
            commands = [call.args[0] for call in run.call_args_list]
            self.assertEqual(commands[0], ["adb", "-s", "emulator-5554", "shell", "dplus", "dump"])
            self.assertEqual(commands[1], ["adb", "-s", "emulator-5554", "push", str(apk.resolve()), "/sdcard/Download/duoplus-hooks.apk"])
            self.assertEqual(commands[2], ["adb", "-s", "emulator-5554", "shell", "dplus", "install", "patch:/sdcard/Download/duoplus-hooks.apk"])
            self.assertEqual(commands[3], ["adb", "-s", "emulator-5554", "forward", "--no-rebind", "tcp:10001", "tcp:9999"])
            self.assertTrue(all(call.kwargs["timeout"] > 0 for call in run.call_args_list))
            self.assertTrue(all("shell" not in call.kwargs for call in run.call_args_list))

    def test_missing_device_loader_stops_install_before_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "module.apk"
            apk.write_bytes(b"test fixture")
            with patch.object(hooks, "run_adb", side_effect=hooks.HooksError("dplus not found")) as adb:
                with self.assertRaisesRegex(hooks.HooksError, "dplus not found"):
                    hooks.install_module("emulator-5554", apk)
            adb.assert_called_once_with("emulator-5554", ["shell", "dplus", "dump"], timeout=30)

    def test_forward_can_remap_an_explicit_device_listener(self):
        output = io.StringIO()
        with patch.object(hooks, "run_adb", return_value="") as adb, redirect_stdout(output):
            result = hooks.main(["forward", "--serial", "emulator-5554", "--port", "12000", "--device-port", "10000"])
        self.assertEqual(result, 0)
        adb.assert_called_once_with("emulator-5554", ["forward", "--no-rebind", "tcp:12000", "tcp:10000"], timeout=30)
        self.assertEqual(json.loads(output.getvalue())["device_port"], 10000)
        self.assertEqual(json.loads(output.getvalue())["local_port"], 12000)

    def test_forward_validates_both_ports_before_adb(self):
        with patch.object(hooks, "run_adb", side_effect=AssertionError("adb called")):
            for local, remote in ((0, 9999), (65536, 9999), (9999, 0), (9999, 65536), (9999, True)):
                with self.subTest(local=local, remote=remote), self.assertRaises(hooks.HooksError):
                    hooks.forward_port("emulator-5554", local, remote)


if __name__ == "__main__":
    unittest.main()
