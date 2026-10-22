import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("rescan_report", Path(__file__).parents[1] / "rescan-report.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, marker, data, **attrs):
        root = ET.Element("testsuite", tests="1", failures="0", errors="0", skipped="0")
        root.attrib.update(attrs)
        ET.SubElement(root, "system-out").text = marker + " " + json.dumps(data)
        p = self.root / name
        ET.ElementTree(root).write(p)
        return p

    def fixtures(self):
        row = dict(files=15000, coldOpens=30000, warmOpens=0, coldMs=40000, warmMs=1000, sourceWrites=0, sourceBytesAndDatesUnchanged=True)
        before = self.write("before.xml", "RESCAN_15000", row)
        row.update(coldOpens=15000, coldMs=12000, warmMs=250)
        after = self.write("after.xml", "RESCAN_15000", row)
        pool = self.write("pools.xml", "RESCAN_CONCURRENCY", {"results": [dict(workers=w, opens=1024, metadataQueries=1, peakValidations=w) for w in (1, 2, 4)]})
        return before, after, pool

    def test_uses_measured_output_and_labels_fixture_evidence(self):
        result = report.collect(*self.fixtures())
        self.assertAlmostEqual(40000 / 12000, result["coldSpeedup"])
        self.assertFalse(result["physicalDeviceTested"])
        self.assertIn("not device timing", result["evidence"])

    def test_rejects_failed_measurement_instead_of_hiding_it(self):
        before, after, pools = self.fixtures()
        root = ET.parse(after);root.getroot().set("failures", "1");root.write(after)
        with self.assertRaises(ValueError):report.collect(before, after, pools)

    def test_rejects_source_mutation_or_unexpected_io(self):
        before, after, pools = self.fixtures()
        for change in ({"sourceWrites": 1}, {"warmOpens": 1}, {"coldOpens": 30000}, {"sourceBytesAndDatesUnchanged": False}):
            row = dict(files=15000, coldOpens=15000, warmOpens=0, coldMs=12000, warmMs=250, sourceWrites=0, sourceBytesAndDatesUnchanged=True)
            row.update(change);self.write("after.xml", "RESCAN_15000", row)
            with self.assertRaises(ValueError):report.collect(before, after, pools)

    def test_rejects_missing_measurement_and_unbounded_pool(self):
        before, after, pools = self.fixtures()
        self.write("pools.xml", "RESCAN_CONCURRENCY", {"results": [dict(workers=w, opens=1024, metadataQueries=1, peakValidations=5) for w in (1, 2, 4)]})
        with self.assertRaises(ValueError):report.collect(before, after, pools)
        after.write_text('<testsuite tests="1"/>')
        with self.assertRaises(ValueError):report.collect(before, after, pools)


if __name__ == "__main__":unittest.main()
