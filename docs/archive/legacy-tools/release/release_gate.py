"""Fail-closed release signing gates. Never generate keys or print secret values."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

REQUIRED_SECRETS = (
    "INSFAV_KEYSTORE_BASE64", "INSFAV_STORE_PASSWORD",
    "INSFAV_KEY_ALIAS", "INSFAV_KEY_PASSWORD",
)


class GateError(Exception):
    pass


def fingerprint(value):
    value = re.sub(r"[\s:]", "", value).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise GateError("A valid INSFAV_RELEASE_CERT_SHA256 certificate pin is required.")
    return value


def configuration(env):
    missing = [name for name in REQUIRED_SECRETS if not env.get(name)]
    if missing:
        raise GateError("Missing repository secrets: " + ", ".join(missing))
    return fingerprint(env.get("INSFAV_RELEASE_CERT_SHA256", ""))


def captured(command, env=None):
    result = subprocess.run(command, env=env, capture_output=True, text=True)
    if result.returncode:
        # Tool errors can contain inputs. Do not forward stdout, stderr or arguments.
        raise GateError("A release verification tool failed; private tool output was suppressed.")
    return result.stdout


def prepare(env):
    expected = configuration(env)
    destination = Path(env["INSFAV_KEYSTORE"])
    if destination.exists():
        raise GateError("Refusing to overwrite an existing signing file.")
    encoded = "".join(env["INSFAV_KEYSTORE_BASE64"].split())
    if len(encoded) > 14_000_000:
        raise GateError("Signing file exceeds the allowed size.")
    data = base64.b64decode(encoded, validate=True)
    if not data:
        raise GateError("The supplied signing file is empty.")
    with destination.open("xb") as output:
        os.chmod(destination, 0o600)
        output.write(data)
    certificate = captured([
        "keytool", "-list", "-rfc", "-keystore", str(destination),
        "-storepass:env", "INSFAV_STORE_PASSWORD", "-alias", env["INSFAV_KEY_ALIAS"],
    ], env)
    match = re.search(r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----", certificate, re.S)
    if not match:
        raise GateError("The supplied key has no readable signing certificate.")
    actual = hashlib.sha256(base64.b64decode(match.group(1))).hexdigest()
    if actual != expected:
        raise GateError("Signing certificate mismatch. No release will be built or published.")
    print("The supplied existing signing certificate matches the configured certificate pin.")


def apk_certificate(apksigner, apk):
    output = captured([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)])
    # SDK versions can annotate signer labels or repeat the same certificate
    # for multiple APK signature schemes. Reject distinct signers/certificates.
    certificates = {fingerprint(value) for value in re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", output)}
    signers = set(re.findall(r"Signer #(\d+)", output))
    counts = re.findall(r"Number of signers:\s*(\d+)", output)
    if len(certificates) != 1 or len(signers) > 1 or any(count != '1' for count in counts):
        raise GateError("Exactly one verified APK signer is required.")
    return next(iter(certificates))


def check_manifest(output):
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output)
    if not match or match.groups() != ("com.instagram.insfav", "7", "4.0.0"):
        raise GateError("Release package, version name or build number is incorrect.")
    if "application-debuggable" in output:
        raise GateError("A debuggable APK cannot be published as the release.")


def verify(apk, output, env):
    expected = fingerprint(env.get("INSFAV_RELEASE_CERT_SHA256", ""))
    sdk = Path(env.get("ANDROID_HOME") or env["ANDROID_SDK_ROOT"])
    candidates = [p for p in (sdk / "build-tools").iterdir() if (p / "apksigner").is_file() and (p / "aapt").is_file()]
    if not candidates:
        raise GateError("Android APK verification tools are unavailable.")
    tools = sorted(candidates, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))[-1]
    actual = apk_certificate(tools / "apksigner", apk)
    if actual != expected:
        raise GateError("Built APK certificate mismatch. Publication is blocked.")
    check_manifest(captured([str(tools / "aapt"), "dump", "badging", str(apk)]))
    output.mkdir(parents=True, exist_ok=False)
    target = output / "INSFAV-4.0.0.apk"
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(digest + "  " + target.name + "\n")
    (output / "signing-certificate.txt").write_text("Signing certificate SHA-256: " + actual + "\n")
    record = {"version": "4.0.0", "build": 7, "sourceCommit": env["GITHUB_SHA"],
              "apkSha256": digest, "certificateSha256": actual,
              "previousApkCertificateComparison": "Not Verified"}
    (output / "verification.json").write_text(json.dumps(record, indent=2) + "\n")
    notes = Path("docs/RELEASE-4.0.0.md").read_text()
    notes += ("\n## Verified release artifact\n\nSource commit: `" + env["GITHUB_SHA"] + "`\n\n"
              "APK SHA-256: `" + digest + "`\n\nSigning certificate SHA-256: `" + actual + "`\n\n"
              "The APK signature matches the explicitly configured release certificate. "
              "A certificate comparison against your previously installed APK has not been performed. "
              "In-place update compatibility is therefore not claimed. Do not uninstall or clear app data to bypass a signature mismatch.\n")
    (output / "RELEASE-NOTES.md").write_text(notes)
    print("Signed release APK verified. APK SHA-256: " + digest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["configuration", "prepare", "verify"])
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "configuration":
            configuration(os.environ)
            print("Required signing secrets and the explicit certificate pin are configured.")
        elif args.command == "prepare":
            prepare(os.environ)
        else:
            if not args.apk or not args.output:
                raise GateError("APK and output paths are required.")
            verify(args.apk, args.output, os.environ)
    except GateError as error:
        print("Release blocked: " + str(error), file=sys.stderr)
        return 1
    except Exception:
        print("Release blocked: signing configuration or artifact verification failed; private details suppressed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
