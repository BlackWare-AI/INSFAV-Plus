#!/usr/bin/env python3
"""Sign with an existing private Plus key; never create or print credentials."""
import json, os, stat, subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
private = root / '.private' / 'signing'
credentials = private / 'release-credentials.json'
if not credentials.is_file():
    raise SystemExit('Restore the owner-held Plus signing backup into .private/signing first')
if stat.S_IMODE(credentials.stat().st_mode) & 0o077:
    raise SystemExit('Signing credentials must have owner-only permissions (chmod 600)')
data = json.loads(credentials.read_text())
required = ['INSFAV_STORE_PASSWORD', 'INSFAV_KEY_PASSWORD', 'INSFAV_KEY_ALIAS']
if any(not isinstance(data.get(name), str) or not data[name] for name in required):
    raise SystemExit('Private signing configuration is incomplete')
keystore = private / 'insfav-plus-release.jks'
if not keystore.is_file() or stat.S_IMODE(keystore.stat().st_mode) & 0o077:
    raise SystemExit('Existing Plus keystore must be present with owner-only permissions')
env = {**os.environ, **{name: data[name] for name in required}, 'INSFAV_KEYSTORE': str(keystore)}
subprocess.run(['./gradlew', ':app:assembleRelease'], cwd=root, env=env, check=True)
subprocess.run(['python3', 'tools/verify-apk.py', 'app/build/outputs/apk/release/app-release.apk',
                '--release', '--output', 'artifacts/apk.json'], cwd=root, env=env, check=True)
