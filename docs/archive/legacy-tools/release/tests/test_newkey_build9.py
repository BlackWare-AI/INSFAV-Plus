import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("newkey_build9", Path(__file__).parents[1] / "newkey_build9.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class NewKeyReleaseTest(unittest.TestCase):
    def record(self):
        return dict(version="5.0.0", build=9, sourceCommit=gate.SOURCE, testedHead=gate.TESTED_HEAD,
                    apkSha256=gate.APK_SHA256, certificateSha256=gate.NEW_CERTIFICATE,
                    previousCertificateSha256=gate.OLD_CERTIFICATE, newSigningKey=True,
                    inPlaceUpdateCompatibleWithBuild8=False, debuggable=False,
                    testedTreeMatchesReleaseSource=True,
                    nativeTests=dict(tests=196, failures=0, errors=0, skipped=0))

    def test_accepts_only_explicit_new_key_and_known_tested_source(self):
        gate.validate_record(self.record())

    def test_rejects_false_update_compatibility_and_missing_new_key_disclosure(self):
        for change in (dict(inPlaceUpdateCompatibleWithBuild8=True), dict(newSigningKey=False), dict(debuggable=True)):
            with self.assertRaises(ValueError):gate.validate_record({**self.record(), **change})

    def test_rejects_unknown_certificate_source_or_apk(self):
        for key in ("sourceCommit", "apkSha256", "certificateSha256", "previousCertificateSha256"):
            with self.assertRaises(ValueError):gate.validate_record({**self.record(), key:"unexpected"})

    def test_rejects_failed_or_different_test_evidence(self):
        for field in ("failures", "errors", "skipped"):
            record = copy.deepcopy(self.record());record["nativeTests"][field] = 1
            with self.assertRaises(ValueError):gate.validate_record(record)


if __name__ == "__main__":unittest.main()
