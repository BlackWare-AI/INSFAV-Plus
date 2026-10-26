#!/usr/bin/env python3
"""Exercise the real publication guard with a local fake gh; no network or uploads."""
import hashlib
import io
import json
import zipfile
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("publish-plus.py")
REPO = "BlackWare-AI/INSFAV-Plus"
APPROVED_CERTIFICATE = "cffc756bf3d517b02780093e7d0d80ae737ce9e3b035a86a094f5698ffab35c8"
LEGACY_CERTIFICATE = "9090544d4da9428880aa032a35e44de61a83dd46eca88e4cc04cf16a0da22f0f"


class PublishGuardTest(unittest.TestCase):
    def run_case(self, previous=None, multiple_pages=False, source_sha=None, release_changes=None, unsigned_payload=None, candidate_payload=None, **changes):
        prior = dict(package="com.instagram.insfav.plus", versionCode=1,
                     certificateSha256=["ab12"])
        prior.update(previous or {})
        current = dict(package="com.instagram.insfav.plus", version="1.1.0", versionCode=2,
                       certificateSha256=["ab12"], sha256="fixture-apk", officialRelease=True,
                       debuggable=False, signatureVerified=True)
        current.update(changes)
        def archive(contents):
            output=io.BytesIO()
            with zipfile.ZipFile(output,'w') as z:
                for name,data in contents.items():z.writestr(name,data)
            return output.getvalue()
        source_apk=archive(unsigned_payload or {'AndroidManifest.xml':b'manifest','classes.dex':b'code'})
        signed_apk=archive(candidate_payload or {'AndroidManifest.xml':b'manifest','classes.dex':b'code','META-INF/CERT.SF':b'signature'})
        if prior.get('unsigned'):
            prior.setdefault('file','source-unsigned.apk')
            prior.setdefault('sha256',hashlib.sha256(source_apk).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'app/build/outputs/apk/release').mkdir(parents=True)
            (root/'app/build/outputs/apk/release/app-release.apk').write_bytes(signed_apk)
            (root/'unsigned-source.apk').write_bytes(source_apk)
            (root / "bin").mkdir()
            (root / "artifacts").mkdir()
            (root / "artifacts/apk.json").write_text(json.dumps(current))
            (root / "prior.json").write_text(json.dumps(prior))
            release = {"assets": [{"name": "app-release.apk"}, {"name": "apk.json", "id": 10, "size": 512}]}
            if prior.get('unsigned'):
                release['assets'][0]=dict(name=prior['file'],id=11,size=len(source_apk))
            release.update(release_changes or {})
            pages = ([{"assets": []}] * 100, [release]) if multiple_pages else ([release],)
            (root / "pages.json").write_text("\n".join(json.dumps(page) for page in pages))
            fake = root / "bin/gh"
            fake.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args=sys.argv[1:]
if args[:1]==['api']:
    if '--paginate' in args:
        print(Path('pages.json').read_text())
    elif args[1].endswith('/11'):
        sys.stdout.buffer.write(Path('unsigned-source.apk').read_bytes())
    else:
        print(Path('prior.json').read_text())
elif args[:2]==['release','create']:
    assert args[args.index('--target')+1]==(os.environ.get('PLUS_SOURCE_SHA') or os.environ['GITHUB_SHA'])
    Path('published').write_text('created')
else:
    raise SystemExit('Unexpected fake gh command')
''')
            fake.chmod(0o700)
            env = {**os.environ, "GITHUB_REPOSITORY": REPO, "GITHUB_SHA": "fixture-commit",
                   "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"]}
            if source_sha is not None:
                env["PLUS_SOURCE_SHA"] = source_sha
            result = subprocess.run(["python3", str(SCRIPT)], cwd=root, env=env,
                                    text=True, capture_output=True)
            return result.returncode, (root / "published").exists(), result.stderr

    def test_same_certificate_and_higher_version_publish(self):
        code, published, error = self.run_case(previous={"certificateSha256": ["AB12"]})
        self.assertEqual(0, code, error)
        self.assertTrue(published)

    def test_owner_approved_identity_can_replace_legacy_before_build_ten(self):
        code, published, error = self.run_case(versionCode=10, certificateSha256=[APPROVED_CERTIFICATE],
            previous=dict(versionCode=9, certificateSha256=[LEGACY_CERTIFICATE]))
        self.assertEqual(0, code, error)
        self.assertTrue(published)

    def test_build_ten_rejects_unknown_and_legacy_current_signers(self):
        for certificate in ["unknown", LEGACY_CERTIFICATE]:
            with self.subTest(certificate=certificate):
                code, published, error = self.run_case(versionCode=10, certificateSha256=[certificate])
                self.assertNotEqual(0, code)
                self.assertFalse(published)
                self.assertIn("owner-approved signing certificate", error)

    def test_future_builds_keep_the_approved_identity(self):
        code, published, error = self.run_case(versionCode=11, certificateSha256=[APPROVED_CERTIFICATE],
            previous=dict(versionCode=10, certificateSha256=[APPROVED_CERTIFICATE.upper()]))
        self.assertEqual(0, code, error)
        self.assertTrue(published)

    def test_legacy_signer_exception_does_not_extend_to_build_ten(self):
        code, published, error = self.run_case(versionCode=11, certificateSha256=[APPROVED_CERTIFICATE],
            previous=dict(versionCode=10, certificateSha256=[LEGACY_CERTIFICATE]))
        self.assertNotEqual(0, code)
        self.assertFalse(published)
        self.assertIn("signing certificate must match", error)

    def test_migration_rejects_unknown_historical_identity(self):
        code, published, error = self.run_case(versionCode=10, certificateSha256=[APPROVED_CERTIFICATE],
            previous=dict(versionCode=9, certificateSha256=["unknown"]))
        self.assertNotEqual(0, code)
        self.assertFalse(published)
        self.assertIn("signing certificate must match", error)

    def test_migration_still_requires_an_increasing_signed_build(self):
        code, published, error = self.run_case(versionCode=10, certificateSha256=[APPROVED_CERTIFICATE],
            previous=dict(versionCode=10, certificateSha256=[APPROVED_CERTIFICATE]))
        self.assertNotEqual(0, code)
        self.assertFalse(published)
        self.assertIn("versionCode must increase", error)

    def test_changed_certificate_is_rejected_before_upload(self):
        code, published, error = self.run_case(certificateSha256=["different"])
        self.assertNotEqual(0, code)
        self.assertFalse(published)
        self.assertIn("signing certificate must match", error)

    def test_nonincreasing_version_is_rejected_before_upload(self):
        code, published, error = self.run_case(versionCode=1)
        self.assertNotEqual(0, code)
        self.assertFalse(published)
        self.assertIn("versionCode must increase", error)

    def test_missing_previous_certificate_is_rejected(self):
        code, published, error = self.run_case(previous={"certificateSha256": []})
        self.assertNotEqual(0, code)
        self.assertFalse(published)
        self.assertIn("Cannot verify the previous", error)

    def test_second_page_certificate_is_checked_with_older_gh_output(self):
        code, published, error = self.run_case(multiple_pages=True, certificateSha256=["different"])
        self.assertNotEqual(0, code)
        self.assertFalse(published)
        self.assertIn("signing certificate must match", error)

    def test_multiple_pages_with_matching_certificate_publish(self):
        code, published, error = self.run_case(multiple_pages=True)
        self.assertEqual(0, code, error)
        self.assertTrue(published)

    def test_reviewed_source_can_differ_from_workflow_revision(self):
        code, published, error = self.run_case(source_sha="f" * 40)
        self.assertEqual(0, code, error)
        self.assertTrue(published)

    def test_explicit_unsigned_prerelease_keeps_future_official_signing_guard_usable(self):
        code, published, error = self.run_case(
            previous=dict(unsigned=True,officialRelease=False,signatureVerified=False,certificateSha256=[]),
            release_changes=dict(prerelease=True,tag_name='plus-v1.0.0-build.1-unsigned'))
        self.assertEqual(0, code, error)
        self.assertTrue(published)

    def test_unsigned_metadata_cannot_hide_a_certificate(self):
        code, published, _ = self.run_case(
            previous=dict(unsigned=True,officialRelease=False,signatureVerified=False),
            release_changes=dict(prerelease=True,tag_name='plus-v1.0.0-build.1-unsigned'))
        self.assertNotEqual(0, code)
        self.assertFalse(published)

    def test_unsigned_metadata_requires_an_unsigned_prerelease(self):
        code, published, _ = self.run_case(
            previous=dict(unsigned=True,officialRelease=False,signatureVerified=False,certificateSha256=[]))
        self.assertNotEqual(0, code)
        self.assertFalse(published)

    def same_build(self, **overrides):
        previous=dict(unsigned=True,officialRelease=False,signatureVerified=False,certificateSha256=[],version='1.1.0')
        previous.update(overrides.pop('previous',{}))
        return self.run_case(previous=previous,
            release_changes=dict(prerelease=True,tag_name='plus-v1.1.0-build.1-unsigned'),versionCode=1,**overrides)

    def test_identical_unsigned_payload_can_receive_its_official_signature_at_the_same_build(self):
        code,published,error=self.same_build()
        self.assertEqual(0,code,error)
        self.assertTrue(published)

    def test_same_build_unsigned_promotion_rejects_changed_application_payload(self):
        code,published,error=self.same_build(candidate_payload={'AndroidManifest.xml':b'manifest','classes.dex':b'changed'})
        self.assertNotEqual(0,code)
        self.assertFalse(published)
        self.assertIn('entire APK payload',error)

    def test_same_build_unsigned_promotion_rejects_changed_version_name(self):
        code,published,error=self.same_build(previous={'version':'0.9.0'})
        self.assertNotEqual(0,code)
        self.assertFalse(published)
        self.assertIn('versionName',error)

    def test_same_build_unsigned_promotion_rejects_a_digest_mismatch(self):
        code,published,error=self.same_build(previous={'sha256':'incorrect'})
        self.assertNotEqual(0,code)
        self.assertFalse(published)
        self.assertIn('SHA-256 mismatch',error)

    def test_unsigned_prerelease_cannot_decrease_version_code(self):
        code,published,error=self.same_build(previous={'versionCode':2})
        self.assertNotEqual(0,code)
        self.assertFalse(published)
        self.assertIn('versionCode cannot decrease',error)

    def test_debug_artifact_is_rejected(self):
        code, published, _ = self.run_case(debuggable=True)
        self.assertNotEqual(0, code)
        self.assertFalse(published)


if __name__ == "__main__":
    unittest.main()
