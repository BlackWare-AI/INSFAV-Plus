#!/usr/bin/env python3
"""Sign a pinned, tested public unsigned APK using the existing Actions signing material."""
import base64, hashlib, io, json, os, re, shutil, subprocess, tempfile, zipfile
from pathlib import Path

def run(*args):
    result=subprocess.run(list(map(str,args)),capture_output=True)
    if result.returncode: raise SystemExit('Tool failed: '+Path(str(args[0])).name)
    return result.stdout

def api(path): return json.loads(run('gh','api',path))

def payload(raw):
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        assert z.testzip() is None and len(z.namelist())==len(set(z.namelist()))
        return {n:z.read(n) for n in z.namelist() if not n.endswith('/') and not
            (n=='META-INF/MANIFEST.MF' or n.startswith('META-INF/') and n.endswith(('.SF','.RSA','.DSA','.EC')))}

def certificate(bt,apk):
    text=run(bt/'apksigner','verify','--verbose','--print-certs',apk).decode()
    return re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)',text)

def main():
    env=os.environ
    assert env['GITHUB_REPOSITORY']==env['GH_REPO']
    for name in ['INSFAV_KEYSTORE_BASE64','INSFAV_STORE_PASSWORD','INSFAV_KEY_ALIAS']:
        if not env.get(name): raise SystemExit('Required signing secret is missing: '+name)
    if env['REQUIRE_KEY_PASSWORD']=='true' and not env.get('INSFAV_KEY_PASSWORD'):
        raise SystemExit('Required signing secret is missing: INSFAV_KEY_PASSWORD (Plus key password)')
    bt=Path(env['ANDROID_HOME'])/'build-tools/35.0.0'
    output=Path('artifacts');output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='official-hd-signing-',dir=env['RUNNER_TEMP']) as temp:
        root=Path(temp);root.chmod(0o700)
        unsigned=root/'unsigned';unsigned.mkdir()
        run('gh','release','download',env['UNSIGNED_TAG'],'--dir',unsigned)
        meta=json.loads((unsigned/'apk.json').read_text());source=unsigned/meta['file']
        assert meta['sourceCommit']==env['UNSIGNED_SOURCE']
        assert meta['unsigned'] is True and meta['officialRelease'] is False and meta['signatureVerified'] is False and meta['certificateSha256']==[]
        assert meta['package']==env['EXPECTED_PACKAGE'] and meta['version']==env['EXPECTED_VERSION'] and meta['versionCode']==int(env['EXPECTED_CODE'])
        digest=hashlib.sha256(source.read_bytes()).hexdigest()
        assert digest==meta['sha256']==env['UNSIGNED_SHA256'],'Unsigned APK digest mismatch'
        assert subprocess.run([str(bt/'apksigner'),'verify',str(source)],capture_output=True).returncode!=0
        check=api('repos/'+env['GH_REPO']+'/actions/runs/'+str(meta['validationRun']))
        assert check['conclusion']=='success' and check['head_sha']==meta['validatedSource']
        jobs=api('repos/'+env['GH_REPO']+'/actions/runs/'+str(meta['validationRun'])+'/jobs')['jobs']
        for name in ['verify','emulator']: assert any(j['name']==name and j['conclusion']=='success' for j in jobs)
        run('git','diff','--exit-code',meta['validatedSource'],meta['sourceCommit'],'--','app','web','version.properties','build.gradle','settings.gradle','gradle.properties','gradle','gradlew','gradlew.bat')
        key=root/'official.jks';key.write_bytes(base64.b64decode(env['INSFAV_KEYSTORE_BASE64'],validate=True));key.chmod(0o600)
        signing_env=dict(env,INSFAV_KEY_PASSWORD=env.get('INSFAV_KEY_PASSWORD') or env['INSFAV_STORE_PASSWORD'])
        apk=output/env['APK_NAME']
        signed=subprocess.run([str(bt/'apksigner'),'sign','--ks',str(key),'--ks-key-alias',env['INSFAV_KEY_ALIAS'],
            '--ks-pass','env:INSFAV_STORE_PASSWORD','--key-pass','env:INSFAV_KEY_PASSWORD','--out',str(apk),str(source)],env=signing_env,capture_output=True)
        if signed.returncode: raise SystemExit('Official APK signing failed; the configured keystore credentials could not sign this APK')
        cert=certificate(bt,apk)
        if cert!=[env['EXPECTED_CERT_SHA256']]: raise SystemExit('Official signing certificate mismatch; expected SHA-256='+env['EXPECTED_CERT_SHA256']+'; received SHA-256='+','.join(cert))
        previous=root/'previous';previous.mkdir()
        run('gh','release','download',env['PREVIOUS_TAG'],'--pattern',env['PREVIOUS_APK'],'--dir',previous)
        assert certificate(bt,previous/env['PREVIOUS_APK'])==cert,'Previous official APK certificate mismatch'
        assert payload(source.read_bytes())==payload(apk.read_bytes()),'Signing must not change any application payload'
        badging=run(bt/'aapt2','dump','badging',apk).decode()
        fields={k:re.search(r"\b"+k+r"='([^']*)'",badging.splitlines()[0])[1] for k in ['name','versionName','versionCode']}
        assert fields==dict(name=env['EXPECTED_PACKAGE'],versionName=env['EXPECTED_VERSION'],versionCode=env['EXPECTED_CODE'])
        assert 'application-debuggable' not in badging.splitlines()
        run(bt/'zipalign','-c','-P','16','4',apk)
        result=dict(file=apk.name,bytes=apk.stat().st_size,sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),package=fields['name'],version=fields['versionName'],versionCode=int(fields['versionCode']),debuggable=False,signatureVerified=True,certificateSha256=cert,officialRelease=True,unsigned=False,sourceCommit=meta['sourceCommit'],signingWorkflowCommit=env['GITHUB_SHA'],unsignedSourceTag=env['UNSIGNED_TAG'],unsignedSha256=digest,payloadMatchesUnsigned=True,validationRun=meta['validationRun'])
        (output/'apk.json').write_text(json.dumps(result,indent=2)+'\n')
        (output/'SHA256SUMS').write_text(result['sha256']+'  '+apk.name+'\n')
        (output/'notes.md').write_text('Resmi imzalı HD thumbnail sürümü.\n\nDaha önce yayımlanan imzasız APK ile uygulama içeriği birebir aynıdır. Mevcut resmi sertifikayla imzalanmıştır.\n\nPaket: '+result['package']+'\n\nSürüm: '+result['version']+'; build: '+str(result['versionCode'])+'\n\nKaynak commit: '+result['sourceCommit']+'\n\nTestler: https://github.com/'+env['GH_REPO']+'/actions/runs/'+str(result['validationRun'])+'\n\nSertifika SHA-256: '+cert[0]+'\n\nAPK SHA-256: '+result['sha256']+'\n')
        common=Path('app/build/outputs/apk/release/app-release.apk');common.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(apk,common)
        with open(env['GITHUB_ENV'],'a') as stream:stream.write('PLUS_SOURCE_SHA='+result['sourceCommit']+'\n')
        print(json.dumps(result,indent=2))

if __name__=='__main__': main()
