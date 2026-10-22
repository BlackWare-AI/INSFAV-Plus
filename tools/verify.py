#!/usr/bin/env python3
"""Run phase gates and retain real exit codes and machine-readable evidence."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--phase', required=True)
parser.add_argument('--browser', action='store_true')
args = parser.parse_args()
out = root / 'artifacts' / 'verification' / args.phase
out.mkdir(parents=True, exist_ok=True)
commands = [
    ['npm', 'ci'], ['npm', 'run', 'web'], ['npm', 'test'],
    ['./gradlew', ':app:testDebugUnitTest'],
    ['./gradlew', ':app:lintDebug'], ['./gradlew', ':app:assembleDebug'],
]
if args.browser:
    commands.append(['npm', 'run', 'test:chromium'])
report = {'phase': args.phase, 'commands': [], 'physicalDeviceTested': False,
          'liveInstagramTested': False}
for index, command in enumerate(commands):
    started = time.monotonic()
    with (out / f'{index:02d}.log').open('w') as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
    row = {'command': command, 'exitCode': result.returncode,
           'seconds': round(time.monotonic() - started, 3)}
    report['commands'].append(row)
    print(json.dumps(row), flush=True)
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, '0'))
report['unitTests'] = totals
report['unitTestCommandSucceeded'] = report['commands'][3]['exitCode'] == 0
report['unitCountsMayBeStale'] = not report['unitTestCommandSucceeded']
lint = root / 'app/build/reports/lint-results-debug.xml'
if lint.exists():
    issues = ET.parse(lint).getroot().findall('issue')
    report['lint'] = {severity: sum(i.get('severity') == severity for i in issues)
                      for severity in ['Error', 'Warning', 'Information']}
    report['lint']['categories'] = {key: sum(i.get('id') == key for i in issues)
                                   for key in sorted({i.get('id') for i in issues})}
(out / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
raise SystemExit(any(row['exitCode'] for row in report['commands']))
