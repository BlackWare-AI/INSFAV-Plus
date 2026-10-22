#!/usr/bin/env python3
"""Verify one APK, print only public artifact metadata. Never reads signing passwords."""
import argparse, hashlib, json, os, subprocess, xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--release',action='store_true');p.add_argument('--version',default='1.0.0');p.add_argument('--code',type=int,default=1);p.add_argument('--output',type=Path);a=p.parse_args()
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',os.environ.get('ANDROID_HOME','')))
versions=sorted((sdk/'build-tools').glob('*'),key=lambda x:tuple(map(int,x.name.split('.'))));bt=versions[-1]
def run(*cmd):
    result=subprocess.run(list(map(str,cmd)),text=True,capture_output=True)
    if result.returncode:raise SystemExit('APK verification tool failed: '+Path(str(cmd[0])).name)
    return result.stdout
manifest=ET.fromstring(run(sdk/'cmdline-tools/latest/bin/apkanalyzer','manifest','print',a.apk));ns='{http://schemas.android.com/apk/res/android}'
package=manifest.get('package');version=manifest.get(ns+'versionName');code=int(manifest.get(ns+'versionCode'));debug=manifest.find('application').get(ns+'debuggable','false')=='true'
assert package=='com.instagram.insfav.plus',package
assert version==a.version+('' if a.release else '-debug'),version
assert code==a.code,code
assert debug!=a.release,'Unexpected debuggable state'
cert=run(bt/'apksigner','verify','--verbose','--print-certs',a.apk)
result=dict(file=a.apk.name,bytes=a.apk.stat().st_size,sha256=hashlib.sha256(a.apk.read_bytes()).hexdigest(),package=package,version=version,versionCode=code,debuggable=debug,signatureVerified=True,certificateSha256=[x.split(': ',1)[1] for x in cert.splitlines() if 'certificate SHA-256 digest:' in x],officialRelease=a.release)
raw=json.dumps(result,indent=2)+'\n';print(raw,end='')
if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(raw)
