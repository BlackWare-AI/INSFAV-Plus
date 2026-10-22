#!/usr/bin/env python3
"""Manual workflow only. No key creation, no uploads to the original project."""
import json, os, subprocess, tempfile
from pathlib import Path
REPOSITORY='BlackWare-AI/INSFAV-Plus'
assert os.environ['GITHUB_REPOSITORY']==REPOSITORY
meta=json.loads(Path('artifacts/apk.json').read_text());assert meta['officialRelease'] and not meta['debuggable'] and meta['signatureVerified']
def gh(*args):return subprocess.check_output(['gh',*args],text=True)
pages=json.loads(gh('api','--paginate','--slurp','repos/'+REPOSITORY+'/releases?per_page=100'))
for release in [r for page in pages for r in page]:
    assets=release.get('assets',[])
    if not any(a['name'].endswith('.apk') for a in assets):continue
    records=[a for a in assets if a['name']=='apk.json']
    if not records:raise SystemExit('Cannot verify a previously published APK versionCode')
    for asset in records:
        assert asset['size']<16384
        prior=json.loads(gh('api','repos/'+REPOSITORY+'/releases/assets/'+str(asset['id']),'-H','Accept: application/octet-stream'))
        assert prior['package']==meta['package'] and prior['versionCode']<meta['versionCode'],'versionCode must increase for every published APK'
tag='plus-v'+meta['version']+'-build.'+str(meta['versionCode'])
notes='Independent INSFAV Plus package.\n\nPackage: '+meta['package']+'\nVersion: '+meta['version']+'\nBuild: '+str(meta['versionCode'])+'\nSHA-256: '+meta['sha256']+'\n\nSee MIGRATION.md before importing original INSFAV settings.\n'
with tempfile.NamedTemporaryFile(mode='w',suffix='.md') as f:
    f.write(notes);f.flush()
    subprocess.run(['gh','release','create',tag,'app/build/outputs/apk/release/app-release.apk','artifacts/apk.json','--repo',REPOSITORY,'--target',os.environ['GITHUB_SHA'],'--title','INSFAV Plus '+meta['version'],'--notes-file',f.name],check=True)
