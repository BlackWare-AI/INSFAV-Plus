#!/usr/bin/env python3
"""Offline A/B evidence against the preserved source. Never writes tracked baseline files.
Requires the README toolchain. Run full verification/coverage after filtered benchmark tests.
"""
import argparse,datetime,json,os,subprocess,tarfile,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
BASE='0a4ba915418e0652618879d4af2c0745ef9ca4f0'
p=argparse.ArgumentParser();p.add_argument('--kind',choices=['native','browser','all'],default='all');p.add_argument('--rounds',type=int,default=3);p.add_argument('--output',type=Path,default=ROOT/'artifacts/performance');p.add_argument('--avatar-baseline-ref',default='d976254');a=p.parse_args();assert a.rounds>0
out=a.output.resolve()/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ');out.mkdir(parents=True)
baseline=out/'original';subprocess.run(['git','-c','advice.detachedHead=false','clone','--quiet','--branch','baseline/insfav-0a4ba91',str(ROOT/'docs/archive/source/insfav-baseline.bundle'),str(baseline)],check=True)
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=baseline,text=True).strip()==BASE
records=[]
def call(command,cwd,log,env=None):
 with log.open('w') as stream:r=subprocess.run(command,cwd=cwd,env=env,stdout=stream,stderr=subprocess.STDOUT)
 if r.returncode:raise SystemExit('Benchmark failed; inspect '+str(log))
if a.kind in ['native','all']:
 extra=out/'test-source';extra.mkdir()
 for rel in ['IndexWriteBenchmarkTest.java','RescanBenchmarkTest.java','StatusSnapshotBenchmarkTest.java','fetcher/BrokerIdleBenchmarkTest.java','fetcher/StatusDeliveryBenchmarkTest.java']:
  source=ROOT/'app/src/test/java/com/instagram/insfav'/rel;dest=extra/'com/instagram/insfav'/rel;dest.parent.mkdir(parents=True,exist_ok=True)
  content=source.read_text()
  if source.name=='RescanBenchmarkTest.java':content=content.replace('class RescanBenchmarkTest','class PairedRescanBenchmarkTest');dest=dest.with_name('PairedRescanBenchmarkTest.java')
  dest.write_text(content)
 init=out/'baseline.init.gradle';quoted=str(extra).replace('\\','\\\\').replace("'","\\'");init.write_text("gradle.projectsEvaluated { gradle.rootProject.project(':app').android.sourceSets.test.java.srcDir('"+quoted+"') }\n")
 for repeat in range(a.rounds):
  for variant,cwd in [('original',baseline),('plus',ROOT)]:
   command=['./gradlew']+(['-I',str(init)] if variant=='original' else [])+['-Pbenchmark',':app:testDebugUnitTest']
   for test in ['IndexWriteBenchmarkTest','BrokerIdleBenchmarkTest',('Paired' if variant=='original' else '')+'RescanBenchmarkTest','StatusSnapshotBenchmarkTest','StatusDeliveryBenchmarkTest']:command+=['--tests','*'+test]
   call(command+['--rerun-tasks'],cwd,out/f'{variant}-{repeat}.log')
   row={'variant':variant,'repeat':repeat,'measurements':{}}
   for xml in (cwd/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite=ET.parse(xml).getroot();assert int(suite.get('failures','0'))==0 and int(suite.get('errors','0'))==0
    for line in (suite.findtext('system-out') or '').splitlines():
     name,_,value=line.partition(' ')
     if name in ['INDEX_WRITE','BROKER_IDLE','RESCAN_15000','STATUS_SNAPSHOT','STATUS_DELIVERY']:row['measurements'][name]=json.loads(value)
   records.append(row);(out/'native.json').write_text(json.dumps(records,indent=2)+'\n');print(variant,repeat,'passed',flush=True)
if a.kind in ['browser','all']:
 env={**os.environ,'BASELINE_ASSETS':str(baseline/'app/src/main/assets')}
 if subprocess.run(['git','cat-file','-e',a.avatar_baseline_ref+'^{commit}'],cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
  archive=subprocess.check_output(['git','archive','--format=tar',a.avatar_baseline_ref,'app/src/main/assets'],cwd=ROOT);import io
  avatar=out/'avatar60';avatar.mkdir()
  with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
   for member in tar.getmembers():
    target=(avatar/member.name).resolve()
    if not target.is_relative_to(avatar.resolve()):raise ValueError('Invalid archive path')
   tar.extractall(avatar,filter='data')
  env['BASELINE_60_ASSETS']=str(avatar/'app/src/main/assets')
 for script in ['performance','thumbnail-performance','acceptance-performance']:call(['node','tests/'+script+'.cjs'],ROOT,out/(script+'.json'),env)
subprocess.run(['git','diff','--exit-code'],cwd=baseline,check=True,stdout=subprocess.DEVNULL)
print('Evidence:',out,'\nRun tools/verify.py and jacocoDebugReport for the full test/coverage report after benchmarks.')
