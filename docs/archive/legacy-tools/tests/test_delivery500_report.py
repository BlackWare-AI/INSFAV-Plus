"""Packaging integration uses isolated mock evidence, never presented as measured performance."""
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / "delivery500-report.py"


class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, self.old)
        self.write("version.properties", "VERSION_NAME=5.0.0\nVERSION_CODE=9\n")
        self.write("builds/app/build/test-results/testDebugUnitTest/TEST-fixture.xml", '<testsuite tests="1"><system-out>FETCHER_13500 first_ms=1 warm_ms=1 first_listings=1 first_opens=27000 first_decodes=27000 warm_listings=0 warm_opens=0 warm_decodes=0</system-out></testsuite>')
        self.write("builds/app/build/reports/lint-results-debug.xml", '<issues/>')
        self.write("builds/app/build/reports/lint-results-debug.txt", "0 errors\n")
        self.write("builds/browser-results.txt", "1 UI checks passed\n")
        for name in ("probe-results.txt", "counter-results.txt"):
            self.write("builds/" + name, "fixture")
        self.write("builds/delivery/INSFAV-debug.apk", "mock debug bytes")
        self.write("builds/app/build/outputs/apk/release/app-release-unsigned.apk", "mock unsigned bytes")
        self.write_json("emulator-pool/pool-matrix.json", [dict(sdk=34, errors=0, requested=5, intervalOverlap=True, maxActiveWebViews=2)])
        self.write_json("emulator-pool/background-fixture.json", dict(validFiles=8, notificationDownloaded=8, completed=8, completionNotificationRemoved=True, screenWasOff=True, screenOffBefore=0, screenOffAfter=1))
        self.rescan = dict(coldOpens=15000, warmOpens=0, sourceWrites=0, sourceBytesAndDatesUnchanged=True, restartRelistedFolder=False, coldMs=12000, warmMs=1000, pssBeforeKiB=50000, pssAfterKiB=60000)
        self.write_json("emulator-pool/rescan-15000.json", self.rescan)
        self.write_json("builds/measurements/rescan-before-after.json", dict(before=dict(coldMs=40000, warmMs=1500), after=dict(coldMs=12000, warmMs=1000)))

    def write(self, name, text):
        p = self.root / name;p.parent.mkdir(parents=True, exist_ok=True);p.write_text(text)

    def write_json(self, name, value):
        self.write(name, json.dumps(value))

    def package(self):
        with patch.dict(os.environ, SOURCE_COMMIT="fixture-commit"):
            runpy.run_path(str(SCRIPT), run_name="__main__")

    def test_includes_actual_rescan_fields_and_raw_measurements(self):
        self.package()
        record = json.loads((self.root / "final-delivery/Verification.json").read_text())
        self.assertEqual(self.rescan, record["thumbnailRescan"])
        self.assertFalse(record["physicalDeviceTested"])
        self.assertTrue((self.root / "final-delivery/verification/measurements/rescan-before-after.json").exists())
        self.assertIn("peak bellek ölçümü değildir", (self.root / "final-delivery/Development-Report-TR.md").read_text())

    def test_rejects_warm_source_reads_and_source_mutation(self):
        for change in ({"warmOpens": 1}, {"sourceWrites": 1}, {"sourceBytesAndDatesUnchanged": False}):
            self.write_json("emulator-pool/rescan-15000.json", {**self.rescan, **change})
            with self.assertRaises(AssertionError):self.package()


if __name__ == "__main__":unittest.main()
