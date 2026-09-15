"""Local compiler/schema behavior; no saved production data or device calls."""

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import scenario_cli as scenarios
from hooks_cli import HooksError


def route(times=None, coordinates=None, **properties):
    if times is not None:
        properties["times"] = times
    return {"type": "Feature", "properties": properties, "geometry": {
        "type": "LineString", "coordinates": coordinates or [[0, 0], [0.0001, 0]]}}


def basic():
    return scenarios.compile_scenario(route(), speed_mps=2)


def library():
    wifi = {"netid": "survey-wifi", "ssid": "", "trilat": 0, "trilong": 0,
            "frequency": 2412, "channel": 1, "lasttime": "2025-10-01T00:00:00Z",
            "secret": "DO_NOT_EXPORT", "road": "private address omitted"}
    cell = {"id": "opaque:café:001/02", "trilat": 0, "trilong": 0,
            "attributes": "MCC?=undocumented, CID=do-not-decode", "gentype": "LTE",
            "lastupdt": "2025-11-01T00:00:00Z"}
    return {"row_storage": "query_envelopes_v1", "source": "Local survey fixture", "complete": False,
            "checked_at": "2026-01-01T00:00:00Z", "queries": [
                {"endpoint": "network/search", "response": {"success": True, "results": [wifi]}},
                {"endpoint": "cell/search", "response": {"success": True, "results": [cell, deepcopy(cell)]}}]}


class TrajectoryTests(unittest.TestCase):
    def test_timestamped_route_preserves_dwell_and_utc(self):
        data = route(["2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z", "2026-01-01T00:00:15Z"],
                     [[0, 0], [0, 0], [0.0001, 0]])
        value = scenarios.compile_scenario(data)
        self.assertEqual([knot["t_ms"] for knot in value["trajectory"]], [0, 5000, 15000])
        self.assertEqual(value["trajectory"][0]["lon"], value["trajectory"][1]["lon"])
        self.assertEqual(value["utc_origin_ms"], 1767225600000)
        self.assertIn("Example MSL", value["altitude_provenance"])

    def test_untimed_requires_speed_and_times_original_vertices(self):
        with self.assertRaisesRegex(HooksError, "requires --speed"):
            scenarios.compile_scenario(route())
        value = basic()
        self.assertEqual(value["trajectory"][-1]["lon"], 0.0001)
        self.assertEqual([knot["t_ms"] for knot in value["trajectory"]], [0, 5560])
        self.assertTrue(any("example" in warning for warning in value["warnings"]))

    def test_untimed_corner_is_retained_at_its_distance_based_arrival(self):
        coordinates = [[0, 0], [0.00005, 0], [0.00005, 0.0001]]
        value = scenarios.compile_scenario(route(coordinates=coordinates), speed_mps=10)
        self.assertEqual([[knot["lon"], knot["lat"]] for knot in value["trajectory"]], coordinates)
        self.assertEqual([knot["t_ms"] for knot in value["trajectory"]], [0, 556, 1668])
        # The engine reaches the actual corner, rather than a chord across two 1Hz fixes.
        self.assertEqual(value["trajectory"][1]["lat"], 0)
        self.assertEqual(value["trajectory"][1]["lon"], 0.00005)

    def test_untimed_millisecond_collisions_fail_without_dropping_vertices(self):
        for coordinates in ([[0, 0], [0.000000001, 0], [0.001, 0]],
                            [[0, 0], [0, 0], [0.001, 0]], [[0, 0], [0, 0], [0, 0]]):
            with self.subTest(coordinates=coordinates), self.assertRaisesRegex(HooksError, "collide"):
                scenarios.compile_scenario(route(coordinates=coordinates), speed_mps=10)

    def test_altitude_datum_and_conversion_are_explicit(self):
        data = route(coordinates=[[0, 0, 50], [0.0001, 0, 55]])
        with self.assertRaisesRegex(HooksError, "explicitly declare"):
            scenarios.compile_scenario(data, speed_mps=2)
        with self.assertRaisesRegex(HooksError, "explicit geoid"):
            scenarios.compile_scenario(data, speed_mps=2, altitude_datum="ellipsoid")
        value = scenarios.compile_scenario(data, speed_mps=2, altitude_datum="ellipsoid", geoid_separation_m=30)
        self.assertEqual(value["trajectory"][0]["alt_msl_m"], 20)
        self.assertEqual(value["trajectory"][-1]["alt_msl_m"], 25)
        self.assertTrue(all(knot["geoid_sep_m"] == 30 for knot in value["trajectory"]))
        with self.assertRaisesRegex(HooksError, "conflicts"):
            scenarios.compile_scenario(route(coordinates=[[0, 0, 5], [0.001, 0, 5]], altitude_datum="msl"),
                                       speed_mps=2, altitude_datum="ellipsoid")

    def test_height_and_pressure_domain_is_bounded(self):
        for height in (-12001, 20001, float("inf")):
            with self.subTest(height=height), self.assertRaises(HooksError):
                scenarios.compile_scenario(route(), speed_mps=2, example_height_m=height)
        with self.assertRaises(HooksError):
            scenarios.compile_scenario(route(), speed_mps=2, geoid_separation_m=1001)

    def test_timestamps_reject_ambiguity_bad_order_and_excess_duration(self):
        for times in (["2026-01-01T00:00:00", "2026-01-01T00:00:05"],
                      ["2026-01-01T00:00:00-04:00", "2026-01-01T00:00:05-04:00"],
                      ["2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"],
                      ["2026-01-01T00:00:00.0001Z", "2026-01-01T00:00:05Z"],
                      ["2026-01-01T00:00:00Z", "2026-01-02T00:00:00.001Z"]):
            with self.subTest(times=times), self.assertRaises(HooksError):
                scenarios.compile_scenario(route(times))
        with self.assertRaises(HooksError):
            scenarios.compile_scenario(route(["2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z"]), speed_mps=2)
        with self.assertRaises(HooksError):
            scenarios.compile_scenario(route(), speed_mps=0.00001)

    def test_date_line_and_stationary_routes_remain_valid(self):
        value = scenarios.compile_scenario(route(coordinates=[[179.99995, 0], [-179.99995, 0]]), speed_mps=2)
        self.assertTrue(all(abs(knot["lon"]) > 179 for knot in value["trajectory"]))
        stationary = scenarios.compile_scenario(route(coordinates=[[0, 0], [0, 0]]), speed_mps=1)
        self.assertEqual([knot["t_ms"] for knot in stationary["trajectory"]], [0, 1000])

    def test_nonfinite_boolean_coordinates_and_point_limit_rejected(self):
        for value in (True, float("nan"), float("inf"), 181):
            with self.subTest(value=value), self.assertRaises(HooksError):
                scenarios.compile_scenario(route(coordinates=[[0, 0], [value, 0]]), speed_mps=2)
        with self.assertRaises(HooksError):
            scenarios.compile_scenario(route(coordinates=[[0, 0]] * 100001), speed_mps=2)


class CatalogTests(unittest.TestCase):
    def test_envelope_import_preserves_incomplete_dates_and_opaque_cell_fields(self):
        value = scenarios.compile_scenario(route(), speed_mps=2, catalog_document=library())
        catalog = value["catalog"]
        self.assertFalse(catalog["complete"])
        self.assertIsNone(catalog["observed_at"])
        self.assertEqual(catalog["catalog_checked_at"], "2026-01-01T00:00:00Z")
        self.assertEqual(len(catalog["records"]), 3)
        cell = next(item for item in catalog["records"] if item["kind"] == "cell")
        self.assertEqual(cell["id"], "opaque:café:001/02")
        self.assertEqual(cell["fields"]["attributes"], "MCC?=undocumented, CID=do-not-decode")
        self.assertEqual(cell["provenance"]["attributes"], "survey")
        self.assertEqual(cell["provenance"]["frequency_mhz"], "example")
        self.assertEqual(cell["provenance"]["technology"], "survey")
        self.assertNotIn("DO_NOT_EXPORT", json.dumps(value))
        self.assertNotIn("private address", json.dumps(value))

    def test_missing_ble_is_example_and_empty_observed_ssid_is_preserved(self):
        value = scenarios.compile_scenario(route(), speed_mps=2, catalog_document=library())
        wifi = next(item for item in value["catalog"]["records"] if item["kind"] == "wifi")
        ble = next(item for item in value["catalog"]["records"] if item["kind"] == "ble")
        self.assertEqual(wifi["fields"]["ssid"], "")
        self.assertEqual(wifi["provenance"]["ssid"], "survey")
        self.assertEqual(ble["provenance"]["id"], "example")
        for record in value["catalog"]["records"]:
            self.assertNotIn("rssi_dbm", record["provenance"])
            self.assertTrue(all(key in record["provenance"] for key in record["fields"]))

    def test_conflicting_duplicate_uses_first_and_reports_incomplete(self):
        data = library()
        data["complete"] = True
        data["queries"][1]["response"]["results"][1]["attributes"] = "different source text"
        result, warnings = scenarios.import_catalog(data)
        self.assertFalse(result["complete"])
        self.assertTrue(any("differing" in warning for warning in warnings))
        self.assertEqual(result["records"][1]["fields"]["attributes"], "MCC?=undocumented, CID=do-not-decode")

    def test_normalized_import_and_provenance_validation(self):
        catalog = basic()["catalog"]
        imported, _ = scenarios.import_catalog({"schema": "hooking.catalog", "version": 1, **catalog})
        self.assertEqual(imported, catalog)
        invalid = deepcopy(catalog)
        del invalid["records"][0]["provenance"]["lat"]
        with self.assertRaisesRegex(HooksError, "provenance"):
            scenarios.import_catalog(invalid)
        observed = deepcopy(catalog)
        observed["records"][0]["fields"]["rssi_dbm"] = -60
        observed["records"][0]["provenance"]["rssi_dbm"] = "survey"
        compiled = scenarios.compile_scenario(route(), speed_mps=2, catalog_document=observed)
        self.assertEqual(compiled["catalog"]["records"][0]["fields"]["rssi_dbm"], -60)
        self.assertEqual(compiled["catalog"]["records"][0]["provenance"]["rssi_dbm"], "survey")

    def test_invalid_catalog_format_or_oversize_is_rejected(self):
        for data in ({"success": True, "results": []}, {"row_storage": "unknown", "queries": []}):
            with self.subTest(data=data), self.assertRaises(HooksError):
                scenarios.import_catalog(data)
        data = library()
        data["queries"][0]["response"]["results"] *= 10001
        with self.assertRaisesRegex(HooksError, "10000"):
            scenarios.import_catalog(data)


class EventAndProfileTests(unittest.TestCase):
    def test_every_built_scenario_has_explicit_epoch_zero_scripts(self):
        value = basic()
        self.assertEqual(set(value["events"]), {"fix", "activity", "power", "gnss", "connections"})
        self.assertTrue(all(events[0]["t_ms"] == 0 for events in value["events"].values()))
        self.assertFalse(value["profiles"]["noise"])
        self.assertEqual(value["profiles"]["rates_hz"]["gnss"], 1)

    def test_gnss_loss_fixture_is_explicit_and_unique(self):
        value = scenarios.compile_scenario(route(), speed_mps=2, fixtures={"events": {"fix": [
            {"t_ms": 0, "valid": True}, {"t_ms": 2000, "valid": False}]}})
        self.assertEqual(sum(sat["used_in_fix"] for sat in value["events"]["gnss"][0]["satellites"]), 6)
        self.assertEqual(sum(sat["used_in_fix"] for sat in value["events"]["gnss"][1]["satellites"]), 2)
        value["events"]["gnss"][0]["satellites"].append(deepcopy(value["events"]["gnss"][0]["satellites"][0]))
        with self.assertRaisesRegex(HooksError, "duplicate satellite"):
            scenarios.validate_scenario(value)

    def test_malformed_source_events_fail_before_dependent_fixture_generation(self):
        for bad in (None, [None], [{"valid": True}], [{"t_ms": 0}], [{"t_ms": 0, "valid": "true"}]):
            with self.subTest(bad=bad), self.assertRaises(HooksError):
                scenarios.compile_scenario(route(), speed_mps=2, fixtures={"events": {"fix": bad}})

    def test_connection_ids_are_checked_against_radio_kind(self):
        value = basic()
        cell_id = next(record["id"] for record in value["catalog"]["records"] if record["kind"] == "cell")
        value["events"]["connections"][0]["wifi_id"] = cell_id
        with self.assertRaisesRegex(HooksError, "existing wifi"):
            scenarios.validate_scenario(value)

    def test_bad_rotation_rates_height_and_typed_events_rejected(self):
        changes = [
            lambda value: value["profiles"].update(mount=[1, 0, 0, 0, 1, 0, 0, 0, -1]),
            lambda value: value["profiles"]["rates_hz"].update(imu=0.000001),
            lambda value: value["profiles"]["rates_hz"].update(gnss=2),
            lambda value: value["profiles"].update(qnh_hpa=1201),
            lambda value: value["events"]["activity"][0].update(cadence_hz=2),
            lambda value: value["events"]["fix"][0].update(valid=1),
            lambda value: value["events"]["power"][0].update(thermal_status="UNKNOWN"),
            lambda value: value["events"]["gnss"][0]["satellites"][0].update(azimuth_deg=360),
            lambda value: value["trajectory"][0].update(alt_msl_m=20001),
            lambda value: value["trajectory"][0].update(geoid_sep_m=-1001),
        ]
        for change in changes:
            value = basic()
            change(value)
            with self.subTest(change=change), self.assertRaises(HooksError):
                scenarios.validate_scenario(value)

    def test_optional_defaults_allowed_but_present_epochs_need_zero_and_order(self):
        value = basic()
        for key in ("events", "profiles", "warnings"):
            del value[key]
        scenarios.validate_scenario(value)
        for epochs in ([], [{"t_ms": 1, "valid": True}], [{"t_ms": 0, "valid": True}, {"t_ms": 0, "valid": False}]):
            value["events"] = {"fix": epochs}
            with self.subTest(epochs=epochs), self.assertRaises(HooksError):
                scenarios.validate_scenario(value)

    def test_unknown_version_and_keys_fail_closed(self):
        for change in (
            lambda value: value.update(version=2), lambda value: value.update(version=True),
            lambda value: value.update(unrecognized=True),
            lambda value: value["profiles"].update(unrecognized=True),
            lambda value: value["events"]["fix"][0].update(unrecognized=True),
            lambda value: value["catalog"]["records"][0].update(unrecognized=True),
        ):
            value = basic()
            change(value)
            with self.subTest(change=change), self.assertRaises(HooksError):
                scenarios.validate_scenario(value)


class InputAndArtifactTests(unittest.TestCase):
    def test_json_rejects_duplicate_keys_nonfinite_and_size_overflow(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for text in ('{"x":1,"x":2}', '{"x":NaN}', '{"x":1e400}', '[]'):
                path.write_text(text)
                with self.subTest(text=text), self.assertRaises(HooksError):
                    scenarios.read_json(path)
            path.write_text('{"padding":"123456789"}')
            with patch.object(scenarios, "MAX_BYTES", 10), self.assertRaises(HooksError):
                scenarios.read_json(path)

    def test_writes_are_private_and_oversize_failure_preserves_previous_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "output.json"
            scenarios.write_json(path, {"previous": True})
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            with patch.object(scenarios, "MAX_BYTES", 4), self.assertRaises(HooksError):
                scenarios.write_json(path, {"replace": True})
            self.assertEqual(json.loads(path.read_text()), {"previous": True})

    def test_build_from_selected_site_uses_only_its_catalog_and_no_network(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            route_path, sites_path, output_path = (directory / name for name in ("route.json", "sites.json", "scenario.json"))
            route_path.write_text(json.dumps(route()))
            sites_path.write_text(json.dumps({"sites": {"chosen": {"wigle_library": library(), "provider_baseline": "DO_NOT_EXPORT"}}}))
            stdout = io.StringIO()
            with patch("requests.sessions.Session.request", side_effect=AssertionError("network call")), redirect_stdout(stdout):
                code = scenarios.main(["build", "--route", str(route_path), "--speed-mps", "2", "--site", "chosen",
                                       "--sites-file", str(sites_path), "--output", str(output_path)])
            self.assertEqual(code, 0)
            self.assertNotIn("DO_NOT_EXPORT", output_path.read_text())
            self.assertNotIn("survey-wifi", stdout.getvalue())
            scenarios.validate_scenario(scenarios.read_json(output_path))

    def test_preview_bounds_output_and_reports_validation_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "scenario.json"
            scenarios.write_json(path, basic())
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                self.assertEqual(scenarios.main(["preview", "--scenario", str(path), "--limit", "1"]), 0)
            result = json.loads(stdout.getvalue())
            self.assertEqual(len(result["trajectory_preview"]), 1)
            self.assertTrue(result["truncated"])
            path.write_text('{"schema":"not-our-schema"}')
            with redirect_stderr(io.StringIO()):
                self.assertEqual(scenarios.main(["validate", "--scenario", str(path)]), 1)

    def test_bundled_demo_is_identical_and_contains_required_journey_stages(self):
        public = scenarios.ROOT / "examples/probe/demo-scenario.json"
        bundled = scenarios.ROOT / "plugin/probe/src/main/assets/demo-scenario.json"
        self.assertEqual(public.read_bytes(), bundled.read_bytes())
        value = scenarios.validate_scenario(scenarios.read_json(public))
        self.assertGreaterEqual(value["trajectory"][-1]["t_ms"], 600000)
        self.assertEqual({event["type"] for event in value["events"]["activity"]}, {"STILL", "IN_VEHICLE", "WALKING"})
        self.assertTrue(any(not event["valid"] for event in value["events"]["fix"]))
        self.assertTrue(any(event["charging"] for event in value["events"]["power"]))
        self.assertTrue(all(record["provenance"]["id"] == "example" for record in value["catalog"]["records"]))
        self.assertFalse(value["profiles"]["noise"])


if __name__ == "__main__":
    unittest.main()
