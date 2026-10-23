#!/usr/bin/env python3
"""Exercise the real publication guard with a local fake gh; no network or uploads."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("publish-plus.py")
REPO = "BlackWare-AI/INSFAV-Plus"


class PublishGuardTest(unittest.TestCase):
    def run_case(self, previous=None, **changes):
        prior = dict(package="com.instagram.insfav.plus", versionCode=1,
                     certificateSha256=["ab12"])
        prior.update(previous or {})
        current = dict(package="com.instagram.insfav.plus", version="1.1.0", versionCode=2,
                       certificateSha256=["ab12"], sha256="fixture-apk", officialRelease=True,
                       debuggable=False, signatureVerified=True)
        current.update(changes)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "bin").mkdir()
            (root / "artifacts").mkdir()
            (root / "artifacts/apk.json").write_text(json.dumps(current))
            (root / "prior.json").write_text(json.dumps(prior))
            fake = root / "bin/gh"
            fake.write_text('''#!/usr/bin/env python3
import json, sys
from pathlib import Path
args=sys.argv[1:]
if args[:1]==['api']:
    if '--paginate' in args:
        print(json.dumps([[{'assets':[{'name':'app-release.apk'}, {'name':'apk.json','id':10,'size':512}]}]]))
    else:
        print(Path('prior.json').read_text())
elif args[:2]==['release','create']:
    Path('published').write_text('created')
else:
    raise SystemExit('Unexpected fake gh command')
''')
            fake.chmod(0o700)
            env = {**os.environ, "GITHUB_REPOSITORY": REPO, "GITHUB_SHA": "fixture-commit",
                   "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"]}
            result = subprocess.run(["python3", str(SCRIPT)], cwd=root, env=env,
                                    text=True, capture_output=True)
            return result.returncode, (root / "published").exists(), result.stderr

    def test_same_certificate_and_higher_version_publish(self):
        code, published, error = self.run_case(previous={"certificateSha256": ["AB12"]})
        self.assertEqual(0, code, error)
        self.assertTrue(published)

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

    def test_debug_artifact_is_rejected(self):
        code, published, _ = self.run_case(debuggable=True)
        self.assertNotEqual(0, code)
        self.assertFalse(published)


if __name__ == "__main__":
    unittest.main()
