import importlib.util
import base64
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("gate", Path(__file__).parents[1] / "release_gate_500.py")
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class ReleaseGateTests(unittest.TestCase):
    def test_missing_secrets_block_without_values(self):
        with self.assertRaises(gate.GateError) as error:
            gate.configuration({"INSFAV_STORE_PASSWORD": "DO_NOT_PRINT_PASSWORD"})
        self.assertIn("INSFAV_KEYSTORE_BASE64", str(error.exception))
        self.assertNotIn("DO_NOT_PRINT_PASSWORD", str(error.exception))

    def test_explicit_certificate_pin_is_required(self):
        env = {key: "SECRET" for key in gate.REQUIRED_SECRETS}
        with self.assertRaises(gate.GateError):
            gate.configuration(env)
        env["INSFAV_RELEASE_CERT_SHA256"] = "ab:" * 31 + "ab"
        self.assertEqual(gate.configuration(env), "ab" * 32)

    def test_only_expected_non_debug_version_can_be_released(self):
        valid = "package: name='com.instagram.insfav' versionCode='9' versionName='5.0.0'"
        gate.check_manifest(valid)
        for value in [valid + "\napplication-debuggable", valid.replace("5.0.0", "5.0.0-debug"), valid.replace("'9'", "'8'"), valid.replace("com.instagram.insfav", "other.app")]:
            with self.assertRaises(gate.GateError):
                gate.check_manifest(value)

    def test_tool_failure_never_forwards_private_output(self):
        result = subprocess.CompletedProcess([], 1, "PRIVATE_KEY", "SECRET_PASSWORD")
        with patch.object(gate.subprocess, "run", return_value=result):
            with self.assertRaises(gate.GateError) as error:
                gate.captured(["tool"])
        self.assertNotIn("SECRET_PASSWORD", str(error.exception))
        self.assertNotIn("PRIVATE_KEY", str(error.exception))

    def test_unsigned_or_multiple_signers_are_rejected(self):
        for output in ["", "Signer #1 certificate SHA-256 digest: " + "ab" * 32 + "\nSigner #2 certificate SHA-256 digest: " + "cd" * 32]:
            with patch.object(gate, "captured", return_value=output):
                with self.assertRaises(gate.GateError):
                    gate.apk_certificate("apksigner", "app.apk")

    def test_verified_signer_is_normalized(self):
        with patch.object(gate, "captured", return_value="Signer #1 certificate SHA-256 digest: " + "AB" * 32):
            self.assertEqual(gate.apk_certificate("apksigner", "app.apk"), "ab" * 32)

    def test_annotated_and_repeated_scheme_labels_keep_one_signer(self):
        line = "Signer #1 (current signer) certificate SHA-256 digest: " + "ab" * 32
        with patch.object(gate, "captured", return_value="Number of signers: 1\n" + line + "\n" + line):
            self.assertEqual(gate.apk_certificate("apksigner", "app.apk"), "ab" * 32)
        with patch.object(gate, "captured", return_value="Number of signers: 2\n" + line):
            with self.assertRaises(gate.GateError):
                gate.apk_certificate("apksigner", "app.apk")

    def test_prepare_refuses_to_overwrite_an_existing_key(self):
        with tempfile.TemporaryDirectory() as folder:
            key = Path(folder) / "existing.p12"
            key.write_bytes(b"preserved")
            env = {name: "SECRET" for name in gate.REQUIRED_SECRETS}
            env.update(INSFAV_KEYSTORE=str(key), INSFAV_RELEASE_CERT_SHA256="ab" * 32)
            with self.assertRaises(gate.GateError):
                gate.prepare(env)
            self.assertEqual(key.read_bytes(), b"preserved")

    def test_wrong_certificate_blocks_before_signing(self):
        with tempfile.TemporaryDirectory() as folder:
            env = {name: "SECRET" for name in gate.REQUIRED_SECRETS}
            env.update(INSFAV_KEYSTORE=str(Path(folder) / "provided.p12"),
                       INSFAV_KEYSTORE_BASE64=base64.b64encode(b"provided test key").decode(),
                       INSFAV_RELEASE_CERT_SHA256="ab" * 32)
            pem = "-----BEGIN CERTIFICATE-----\n" + base64.b64encode(b"different certificate").decode() + "\n-----END CERTIFICATE-----"
            with patch.object(gate, "captured", return_value=pem):
                with self.assertRaisesRegex(gate.GateError, "mismatch"):
                    gate.prepare(env)

    def test_cli_failure_does_not_expose_environment_values(self):
        env = {**os.environ, "INSFAV_STORE_PASSWORD": "SENSITIVE_SENTINEL_PASSWORD", "INSFAV_RELEASE_CERT_SHA256": "invalid"}
        result = subprocess.run([sys.executable, str(Path(__file__).parents[1] / "release_gate_500.py"), "configuration"], env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("SENSITIVE_SENTINEL_PASSWORD", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
