#!/usr/bin/env python3
"""Publish a verified Plus APK. No key creation or uploads to the original project."""
import json, os, subprocess, tempfile
from pathlib import Path
REPOSITORY='BlackWare-AI/INSFAV-Plus'
assert os.environ['GITHUB_REPOSITORY']==REPOSITORY
meta=json.loads(Path('artifacts/apk.json').read_text());assert meta['officialRelease'] and not meta['debuggable'] and meta['signatureVerified']
assert meta.get('certificateSha256'),'Release signing certificate is missing'
def gh(*args):
    result=subprocess.run(['gh',*args],text=True,capture_output=True)
    if result.returncode:raise SystemExit('GitHub release API failed; no credentials exported')
    return result.stdout
# Older gh versions paginate as adjacent JSON arrays and lack --slurp.
raw=gh('api','--paginate','repos/'+REPOSITORY+'/releases?per_page=100')
pages=[];decoder=json.JSONDecoder()
while raw.strip():
    raw=raw.lstrip();page,end=decoder.raw_decode(raw)
    assert isinstance(page,list),'Unexpected release pagination response'
    pages.append(page);raw=raw[end:]
for release in [r for page in pages for r in page]:
    assets=release.get('assets',[])
    if not any(a['name'].endswith('.apk') for a in assets):continue
    records=[a for a in assets if a['name']=='apk.json']
    if not records:raise SystemExit('Cannot verify a previously published APK versionCode')
    for asset in records:
        assert asset['size']<16384
        prior=json.loads(gh('api','repos/'+REPOSITORY+'/releases/assets/'+str(asset['id']),'-H','Accept: application/octet-stream'))
        assert prior['package']==meta['package'] and prior['versionCode']<meta['versionCode'],'versionCode must increase for every published APK'
        if prior.get('unsigned') is True:
            assert release.get('prerelease') is True and release.get('tag_name','').endswith('-unsigned'),'Unsigned APK must be explicitly marked as a prerelease'
            assert prior.get('officialRelease') is False and prior.get('signatureVerified') is False and prior.get('certificateSha256')==[],'Invalid unsigned APK metadata'
            continue  # An explicitly unsigned artifact has no signing certificate to compare.
        assert prior.get('certificateSha256'),'Cannot verify the previous Plus signing certificate'
        assert {v.lower() for v in prior['certificateSha256']}=={v.lower() for v in meta['certificateSha256']},'Plus signing certificate must match previous releases for in-place updates'
tag='plus-v'+meta['version']+'-build.'+str(meta['versionCode'])
notes='Independent INSFAV Plus package.\n\nPackage: '+meta['package']+'\nVersion: '+meta['version']+'\nBuild: '+str(meta['versionCode'])+'\nSHA-256: '+meta['sha256']+'\n\nSee MIGRATION.md before importing original INSFAV settings.\n'
with tempfile.NamedTemporaryFile(mode='w',suffix='.md') as f:
    f.write(notes);f.flush()
    subprocess.run(['gh','release','create',tag,'app/build/outputs/apk/release/app-release.apk','artifacts/apk.json','--repo',REPOSITORY,'--target',(os.environ.get('PLUS_SOURCE_SHA') or os.environ['GITHUB_SHA']),'--title','INSFAV Plus '+meta['version'],'--notes-file',f.name],check=True)
