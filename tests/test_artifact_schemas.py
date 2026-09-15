"""Cross-language artifact contracts; install requirements-dev.txt to run."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, ValidationError

ROOT = Path(__file__).resolve().parents[1]


class ArtifactSchemaTests(unittest.TestCase):
    def setUp(self):
        self.schema = json.loads((ROOT / "schemas/scenario-v1.schema.json").read_text())
        self.validator = Draft202012Validator(self.schema)
        self.demo = json.loads((ROOT / "plugin/probe/src/main/assets/demo-scenario.json").read_text())

    def test_schema_definitions_and_bundled_demo(self):
        Draft202012Validator.check_schema(self.schema)
        Draft202012Validator.check_schema(json.loads((ROOT / "schemas/report-v1.schema.json").read_text()))
        self.validator.validate(self.demo)

    def test_version_units_and_provenance_shapes_are_enforced(self):
        mutations = [
            lambda d: d.update(version=2),
            lambda d: d.update(simulated=False),
            lambda d: d["trajectory"][0].update(lat=91),
            lambda d: d["trajectory"][0].pop("alt_msl_m"),
            lambda d: d["profiles"].update(mount=[1, 0, 0]),
            lambda d: d["profiles"].update(qnh_hpa=0),
            lambda d: d["events"]["gnss"][0]["satellites"][0].update(cn0_dbhz="42"),
            lambda d: d["events"]["connections"][0].update(ble_ids="beacon"),
            lambda d: d["catalog"]["records"][0]["provenance"].update(rssi="observed-hardware"),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)):
                document = copy.deepcopy(self.demo)
                mutate(document)
                with self.assertRaises(ValidationError):
                    self.validator.validate(document)

    def test_java_export_matches_report_schema(self):
        report = ROOT / "plugin/probe/build/reports/contract-report.json"
        if not report.exists():
            self.skipTest("Run :probe:testDebugUnitTest to produce the Java export artifact")
        schema = json.loads((ROOT / "schemas/report-v1.schema.json").read_text())
        document = json.loads(report.read_text())
        Draft202012Validator(schema).validate(document)
        self.assertGreater(document["metrics"]["delivered"], 0)
        self.assertGreater(document["metrics"]["skipped"], 0)


if __name__ == "__main__":
    unittest.main()
