#!/usr/bin/env python3
"""Emulator-only coexistence check. Never replaces/uninstalls the original INSFAV package."""
import argparse,json,re,subprocess,os,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--original',type=Path,required=True);p.add_argument('--plus',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
assert a.serial.startswith('emulator-'),'This test is restricted to an emulator'
def adb(*args):return subprocess.check_output(['adb','-s',a.serial,*map(str,args)],text=True)
def packages():
 out={}
 for line in adb('shell','cmd','package','list','packages','-U','com.instagram.insfav').splitlines():
  m=re.fullmatch(r'package:(\S+) uid:(\d+)',line.strip())
  if m:out[m[1]]=int(m[2])
 return out
def version(package):
 raw=adb('shell','dumpsys','package',package)
 return {key:re.search(r'\b'+key+r'=([^\s]+)',raw)[1] for key in ['versionCode','versionName']}
old='com.instagram.insfav';plus=old+'.plus'
# Verify both APK identities before any install command; -r must never target the original package.
preflight=subprocess.run([sys.executable,str(Path(__file__).with_name('verify-apk.py')),str(a.plus)],text=True,capture_output=True)
if preflight.returncode:raise SystemExit('Plus APK preflight failed; nothing installed')
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',os.environ.get('ANDROID_HOME','')))
original_badging=subprocess.run([str(sdk/'build-tools/35.0.0/aapt2'),'dump','badging',str(a.original)],text=True,capture_output=True)
if original_badging.returncode or not re.search(r"^package: name='com\.instagram\.insfav' ",original_badging.stdout):raise SystemExit('Original APK identity check failed; nothing installed')

if old not in packages():adb('install',a.original)
before=version(old);old_uid=packages()[old]
adb('install','-r',a.plus);uids=packages();assert old in uids and plus in uids and uids[old]!=uids[plus]
props=dict(line.split('=',1) for line in (Path(__file__).resolve().parents[1]/'version.properties').read_text().splitlines() if '=' in line)
assert version(plus)=={'versionCode':props['VERSION_CODE'],'versionName':props['VERSION_NAME']+'-debug'}
for package in [old,plus]:
 launch=adb('shell','am','start','-W','-n',package+'/com.instagram.insfav.MainActivity');assert 'Status: ok' in launch,package
 adb('shell','input','keyevent','KEYCODE_HOME')
assert version(old)==before and packages()[old]==old_uid
result=dict(emulator=a.serial,originalPackage=old,originalVersion=before,originalUidUnchanged=True,plusPackage=plus,plusVersion=version(plus),distinctUids=True,bothLaunch=True,originalUpdatedOrUninstalled=False,physicalDeviceTested=False,liveInstagramTested=False)
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
