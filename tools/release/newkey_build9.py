"""Explicitly authorized new-key release. Never claim compatibility with build 8."""
SOURCE = "e33c09d224628ae50bbae5bba3bba43d6a91210c"
TESTED_HEAD = "4b0adcdb63545a32a64e78ccb2a036799feb97a8"
APK_SHA256 = "3618347371a20f88954cec4e3992c3db8118cd3c068d7d6bf881b493489e4c2a"
NEW_CERTIFICATE = "705e090084a4e50c47a8eb122d1605963276e5bb76f873d6f185b965c01e8641"
OLD_CERTIFICATE = "a3058fab8d03c1549abb04ae4660ff2256296967e6291bd7a751ecb9b32e82c2"


def validate_record(record):
    expected = dict(version="5.0.0", build=9, sourceCommit=SOURCE,
                    testedHead=TESTED_HEAD, apkSha256=APK_SHA256,
                    certificateSha256=NEW_CERTIFICATE,
                    previousCertificateSha256=OLD_CERTIFICATE)
    if any(record.get(key) != value for key, value in expected.items()):
        raise ValueError("Unexpected release identity, source, APK or certificate")
    if record.get("newSigningKey") is not True or record.get("inPlaceUpdateCompatibleWithBuild8") is not False or record.get("debuggable") is not False:
        raise ValueError("New key and incompatible previous signature must be explicit")
    if record.get("testedTreeMatchesReleaseSource") is not True:
        raise ValueError("Release source must match the tested tree")
    if record.get("nativeTests") != dict(tests=196, failures=0, errors=0, skipped=0):
        raise ValueError("Successful test evidence is required")

