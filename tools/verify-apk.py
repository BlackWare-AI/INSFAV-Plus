#!/usr/bin/env python3
"""Verify one APK, print only public artifact metadata. Never reads signing passwords."""
import argparse, hashlib, json, os, re, subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--release',action='store_true');p.add_argument('--version');p.add_argument('--code',type=int);p.add_argument('--output',type=Path);a=p.parse_args()
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',os.environ.get('ANDROID_HOME','')))
bt=sdk/'build-tools/35.0.0'
props=dict(line.split('=',1) for line in (Path(__file__).resolve().parents[1]/'version.properties').read_text().splitlines() if '=' in line)
a.version=a.version or props['VERSION_NAME'];a.code=int(props['VERSION_CODE']) if a.code is None else a.code
def run(*cmd):
    result=subprocess.run(list(map(str,cmd)),text=True,capture_output=True)
    if result.returncode:raise SystemExit('APK verification tool failed: '+Path(str(cmd[0])).name)
    return result.stdout
badging=run(bt/'aapt2','dump','badging',a.apk)
package_line=badging.splitlines()[0]
def field(name):return re.search(r"\b"+name+r"='([^']*)'",package_line)[1]
package=field('name');version=field('versionName');code=int(field('versionCode'));debug='application-debuggable' in badging.splitlines()
assert package=='com.instagram.insfav.plus',package
assert version==a.version+('' if a.release else '-debug'),version
assert code==a.code,code
assert debug!=a.release,'Unexpected debuggable state'
cert=run(bt/'apksigner','verify','--verbose','--print-certs',a.apk)
if a.release:assert not re.search(r'CN\s*=\s*Android Debug',cert,re.I),'Debug certificate cannot sign an official release'
result=dict(file=a.apk.name,bytes=a.apk.stat().st_size,sha256=hashlib.sha256(a.apk.read_bytes()).hexdigest(),package=package,version=version,versionCode=code,debuggable=debug,signatureVerified=True,certificateSha256=[x.split(': ',1)[1] for x in cert.splitlines() if 'certificate SHA-256 digest:' in x],officialRelease=a.release)
raw=json.dumps(result,indent=2)+'\n';print(raw,end='')
if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(raw)
