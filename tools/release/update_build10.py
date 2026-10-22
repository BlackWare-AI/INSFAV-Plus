"""Verify the build 10 update against its reviewed source and build 9 signer."""
import hashlib
import re
from pathlib import Path

SOURCE = '6fcaf3f61304b0e573c634cb80d6627c11cfdb97'
APK_SHA256 = '1918ee03378597605f3a00c6132ac53e5478d79f37be615a3b2ac61b3b2fbf5d'
CERTIFICATE = '705e090084a4e50c47a8eb122d1605963276e5bb76f873d6f185b965c01e8641'
PREVIOUS_APK = '3618347371a20f88954cec4e3992c3db8118cd3c068d7d6bf881b493489e4c2a'


def validate_record(record):
    expected = dict(version='5.0.0', build=10, sourceCommit=SOURCE, apkSha256=APK_SHA256,
                    certificateSha256=CERTIFICATE, sameCertificateAsBuild9=True,
                    inPlaceUpdateCompatibleWithBuild9=True, inPlaceUpdateCompatibleWithBuild8=False,
                    debuggable=False, nativeTests=dict(tests=213, failures=0, errors=0, skipped=0),
                    browserChecks=118, probeFixtureCases=35, lintErrors=0,
                    physicalDeviceTested=False, liveInstagramTested=False,
                    signedReleaseInstalledOnDevice=False)
    if any(key not in record or record[key] != value or type(record[key]) is not type(value)
           for key, value in expected.items()):
        raise ValueError('Release identity, compatibility disclosure or test evidence does not match')


def validate_manifest(output):
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output)
    if not match or match.groups() != ('com.instagram.insfav', '10', '5.0.0') or 'application-debuggable' in output:
        raise ValueError('Incorrect release package, build, version or debuggable flag')


def verify_hash(path, expected):
    if hashlib.sha256(Path(path).read_bytes()).hexdigest() != expected:
        raise ValueError('Artifact digest mismatch')


def validate_draft(record, asset_names):
    if (record.get('draft') is not True or record.get('tag_name') != 'v5.0.0-build.10'
            or record.get('target_commitish') != SOURCE
            or any(a['name'] not in asset_names for a in record.get('assets', []))):
        raise ValueError('Refusing to modify an unrelated or already published release')
