#!/usr/bin/env python3
"""Publish a verified Plus APK. No key creation or uploads to the original project."""
import hashlib, io, json, os, subprocess, tempfile, zipfile
from pathlib import Path
REPOSITORY='BlackWare-AI/INSFAV-Plus'
assert os.environ['GITHUB_REPOSITORY']==REPOSITORY
meta=json.loads(Path('artifacts/apk.json').read_text());assert meta['officialRelease'] and not meta['debuggable'] and meta['signatureVerified']
assert meta.get('certificateSha256'),'Release signing certificate is missing'
def gh(*args):
    result=subprocess.run(['gh',*args],text=True,capture_output=True)
    if result.returncode:raise SystemExit('GitHub release API failed; no credentials exported')
    return result.stdout
def apk_payload(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        assert z.testzip() is None and len(z.namelist())==len(set(z.namelist()))
        return {n:z.read(n) for n in z.namelist() if not n.endswith('/') and not
                (n=='META-INF/MANIFEST.MF' or n.startswith('META-INF/') and n.endswith(('.SF','.RSA','.DSA','.EC')))}
def verify_same_build_unsigned(release, assets, prior):
    assert prior.get('version')==meta['version'],'Same-build signing must preserve versionName'
    sources=[a for a in assets if a['name']==prior.get('file')]
    assert len(sources)==1 and 0<sources[0]['size']<=32*1024*1024,'Missing unsigned source APK'
    result=subprocess.run(['gh','api','repos/'+REPOSITORY+'/releases/assets/'+str(sources[0]['id']),'-H','Accept: application/octet-stream'],capture_output=True)
    assert result.returncode==0,'Cannot download unsigned source APK'
    assert hashlib.sha256(result.stdout).hexdigest()==prior.get('sha256'),'Unsigned source SHA-256 mismatch'
    current=Path('app/build/outputs/apk/release/app-release.apk').read_bytes()
    assert apk_payload(result.stdout)==apk_payload(current),'Same-build signing must preserve the entire APK payload'
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
        assert prior['package']==meta['package'],'Package must remain unchanged'
        if prior.get('unsigned') is True:
            assert release.get('prerelease') is True and release.get('tag_name','').endswith('-unsigned'),'Unsigned APK must be explicitly marked as a prerelease'
            assert prior.get('officialRelease') is False and prior.get('signatureVerified') is False and prior.get('certificateSha256')==[],'Invalid unsigned APK metadata'
            assert prior['versionCode']<=meta['versionCode'],'versionCode cannot decrease'
            if prior['versionCode']==meta['versionCode']: verify_same_build_unsigned(release,assets,prior)
            continue  # Same-build signing is allowed only for identical, explicitly unsigned content.
        assert prior['versionCode']<meta['versionCode'],'versionCode must increase for every published signed APK'
        assert prior.get('certificateSha256'),'Cannot verify the previous Plus signing certificate'
        assert {v.lower() for v in prior['certificateSha256']}=={v.lower() for v in meta['certificateSha256']},'Plus signing certificate must match previous releases for in-place updates'
tag='plus-v'+meta['version']+'-build.'+str(meta['versionCode'])
notes='Independent INSFAV Plus package.\n\nPackage: '+meta['package']+'\nVersion: '+meta['version']+'\nBuild: '+str(meta['versionCode'])+'\nSHA-256: '+meta['sha256']+'\n\nSee MIGRATION.md before importing original INSFAV settings.\n'
with tempfile.NamedTemporaryFile(mode='w',suffix='.md') as f:
    f.write(notes);f.flush()
    subprocess.run(['gh','release','create',tag,'app/build/outputs/apk/release/app-release.apk','artifacts/apk.json','--repo',REPOSITORY,'--target',(os.environ.get('PLUS_SOURCE_SHA') or os.environ['GITHUB_SHA']),'--title','INSFAV Plus '+meta['version'],'--notes-file',f.name],check=True)
