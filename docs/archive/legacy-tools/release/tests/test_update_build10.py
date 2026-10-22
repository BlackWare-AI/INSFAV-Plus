import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('update_build10', Path(__file__).parents[1] / 'update_build10.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class Build10GateTest(unittest.TestCase):
    def record(self):
        return dict(version='5.0.0', build=10, sourceCommit=gate.SOURCE, apkSha256=gate.APK_SHA256,
                    certificateSha256=gate.CERTIFICATE, sameCertificateAsBuild9=True,
                    inPlaceUpdateCompatibleWithBuild9=True, inPlaceUpdateCompatibleWithBuild8=False,
                    debuggable=False, nativeTests=dict(tests=213, failures=0, errors=0, skipped=0),
                    browserChecks=118, probeFixtureCases=35, lintErrors=0,
                    physicalDeviceTested=False, liveInstagramTested=False, signedReleaseInstalledOnDevice=False)

    def test_valid_record_and_non_debuggable_manifest(self):
        gate.validate_record(self.record())
        gate.validate_manifest("package: name='com.instagram.insfav' versionCode='10' versionName='5.0.0'\napplication: label='INSFAV'")

    def test_rejects_changed_signer_source_and_compatibility_claims(self):
        for change in (dict(certificateSha256='wrong'), dict(sourceCommit='wrong'), dict(apkSha256='wrong'),
                       dict(inPlaceUpdateCompatibleWithBuild8=True), dict(sameCertificateAsBuild9=False),
                       dict(liveInstagramTested=True), dict(debuggable=True)):
            with self.assertRaises(ValueError):gate.validate_record({**self.record(), **change})

    def test_rejects_failed_or_missing_test_evidence_and_bad_manifest(self):
        for change in (dict(nativeTests=dict(tests=213, failures=1, errors=0, skipped=0)), dict(browserChecks=0)):
            with self.assertRaises(ValueError):gate.validate_record({**self.record(), **change})
        for value in ('', "package: name='other' versionCode='10' versionName='5.0.0'", "package: name='com.instagram.insfav' versionCode='9' versionName='5.0.0'", "package: name='com.instagram.insfav' versionCode='10' versionName='5.0.0'\napplication-debuggable"):
            with self.assertRaises(ValueError):gate.validate_manifest(value)

    def test_rejects_modified_artifact_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'changed.apk';path.write_bytes(b'changed artifact')
            with self.assertRaises(ValueError):gate.verify_hash(path, gate.APK_SHA256)

    def test_draft_identity_does_not_depend_on_a_published_tag(self):
        draft=dict(draft=True, tag_name='v5.0.0-build.10', target_commitish=gate.SOURCE, assets=[])
        gate.validate_draft(draft, {'INSFAV-5.0.0.apk'})
        for change in (dict(draft=False),dict(tag_name='v5.0.0-build.9'),dict(target_commitish='other'),dict(assets=[dict(name='unexpected.apk')])):
            with self.assertRaises(ValueError):gate.validate_draft({**draft, **change}, {'INSFAV-5.0.0.apk'})


if __name__ == '__main__':unittest.main()
