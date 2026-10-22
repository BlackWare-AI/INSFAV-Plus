import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("evidence", Path(__file__).parents[1] / "build9_evidence.py")
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)


class EvidenceTests(unittest.TestCase):
    def fixture(self, root, failures=0, marker=True):
        results = root / "results"
        results.mkdir()
        sample = "FAST_SKIP_FIXTURE_DIAGNOSTICS_BEGIN\nRESULT_DETAIL code=PROFILE_PAGE_UNAVAILABLE reason=TEXT_MATCH elapsedMs=0\nFAST_SKIP_FIXTURE_DIAGNOSTICS_END" if marker else ""
        (results / "TEST-fixture.xml").write_text(
            f'<testsuite tests="2" failures="{failures}" errors="0" skipped="0"><system-out>{sample}</system-out></testsuite>')
        return results

    def test_exports_actual_counts_and_labels_fixture(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            record = evidence.collect(self.fixture(root), root / "output", "source")
            self.assertEqual(record["nativeTests"]["tests"], 2)
            self.assertEqual(record["build"], 9)
            self.assertFalse(record["liveInstagramTested"])
            self.assertTrue((root / "output/diagnostics-offline-fixture.txt").read_text().startswith("OFFLINE UNIT TEST FIXTURE"))

    def test_failed_tests_cannot_be_reported_as_verified(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                evidence.collect(self.fixture(root, failures=1), root / "output", "source")

    def test_missing_diagnostic_export_is_not_fabricated(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                evidence.collect(self.fixture(root, marker=False), root / "output", "source")


if __name__ == "__main__":
    unittest.main()
