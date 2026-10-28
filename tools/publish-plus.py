#!/usr/bin/env python3
"""Publish a verified Plus APK. No key creation or uploads to the original project."""
import hashlib, io, json, os, subprocess, tempfile, zipfile
from pathlib import Path
REPOSITORY='BlackWare-AI/INSFAV-Plus'
assert os.environ['GITHUB_REPOSITORY']==REPOSITORY
meta=json.loads(Path('artifacts/apk.json').read_text());assert meta['officialRelease'] and not meta['debuggable'] and meta['signatureVerified']
assert meta.get('certificateSha256'),'Release signing certificate is missing'
# The owner approved this identity change starting with session build 10.
# Older installations require reinstalling; subsequent releases retain this key.
APPROVED_CERTIFICATE='cffc756bf3d517b02780093e7d0d80ae737ce9e3b035a86a094f5698ffab35c8'
LEGACY_CERTIFICATE='9090544d4da9428880aa032a35e44de61a83dd46eca88e4cc04cf16a0da22f0f'
current_certificates={v.lower() for v in meta['certificateSha256']}
if meta['versionCode']>=10:
    assert current_certificates=={APPROVED_CERTIFICATE},'APK must use the owner-approved signing certificate'
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
def verify_same_build_icon_refresh(release, assets, prior):
    assert prior.get('version')==meta['version'],'Icon refresh must preserve versionName'
    assert prior.get('versionCode')==meta['versionCode'],'Icon refresh must preserve versionCode'
    sources=[a for a in assets if a['name']==prior.get('file')]
    assert len(sources)==1 and 0<sources[0]['size']<=32*1024*1024,'Missing previous signed APK'
    result=subprocess.run(['gh','api','repos/'+REPOSITORY+'/releases/assets/'+str(sources[0]['id']),'-H','Accept: application/octet-stream'],capture_output=True)
    assert result.returncode==0,'Cannot download previous signed APK'
    assert hashlib.sha256(result.stdout).hexdigest()==prior.get('sha256'),'Previous signed APK SHA-256 mismatch'
    old=apk_payload(result.stdout)
    current=apk_payload(Path('app/build/outputs/apk/release/app-release.apk').read_bytes())
    allowed={'assets/logo.jpg','res/ST.jpg','META-INF/version-control-info.textproto'}
    changed={name for name in old.keys()|current.keys() if old.get(name)!=current.get(name)}
    assert changed==allowed,'Same-build icon refresh may change only launcher artwork and source provenance'
    image=Path('web/static/logo.jpg').read_bytes()
    assert current['assets/logo.jpg']==image and current['res/ST.jpg']==image,'APK icon must match the reviewed source image'
    old_source=release.get('target_commitish','')
    new_source=os.environ.get('PLUS_SOURCE_SHA') or os.environ['GITHUB_SHA']
    assert len(old_source)==40 and len(new_source)==40,'Cannot verify source revision provenance'
    assert old['META-INF/version-control-info.textproto'].replace(old_source.encode(),new_source.encode())==current['META-INF/version-control-info.textproto'],'Only source revision may change in APK provenance'
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
        if prior['versionCode']==meta['versionCode']:
            if os.environ.get('PLUS_ICON_REFRESH')=='true':
                verify_same_build_icon_refresh(release,assets,prior)
                continue
            raise AssertionError('versionCode must increase for every published signed APK')
        assert prior['versionCode']<meta['versionCode'],'versionCode must increase for every published signed APK'
        assert prior.get('certificateSha256'),'Cannot verify the previous Plus signing certificate'
        prior_certificates={v.lower() for v in prior['certificateSha256']}
        if meta['versionCode']>=10 and prior['versionCode']<10 and prior_certificates=={LEGACY_CERTIFICATE}:
            continue  # Explicitly approved migration; no in-place update from this legacy key.
        assert prior_certificates==current_certificates,'Plus signing certificate must match previous releases for in-place updates'
icon_refresh=os.environ.get('PLUS_ICON_REFRESH')=='true'
tag='plus-v'+meta['version']+'-build.'+str(meta['versionCode'])+('-icon-'+hashlib.sha256(Path('web/static/logo.jpg').read_bytes()).hexdigest()[:10] if icon_refresh else '')
notes=Path(os.environ['PLUS_RELEASE_NOTES_FILE']).read_text() if os.environ.get('PLUS_RELEASE_NOTES_FILE') else ('Launcher icon artwork enlarged by 6.7%. Version and build are unchanged.\n' if icon_refresh else Path('INSTAGRAM-SESSION-RELEASE.md').read_text() if Path('INSTAGRAM-SESSION-RELEASE.md').exists() else 'Instagram session management update.\n')
title='INSFAV Plus '+meta['version']+(' — icon size update (build '+str(meta['versionCode'])+')' if icon_refresh else '')
with tempfile.NamedTemporaryFile(mode='w',suffix='.md') as f:
    f.write(notes);f.flush()
    subprocess.run(['gh','release','create',tag,'app/build/outputs/apk/release/app-release.apk','artifacts/apk.json','--repo',REPOSITORY,'--target',(os.environ.get('PLUS_SOURCE_SHA') or os.environ['GITHUB_SHA']),'--title',title,'--notes-file',f.name]+(['--latest=false'] if os.environ.get('PLUS_RELEASE_LATEST')=='false' else []),check=True)
