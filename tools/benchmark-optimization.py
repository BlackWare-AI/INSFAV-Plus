#!/usr/bin/env python3
"""Compare this application to its own pinned baseline, sequentially on one host.
No APK signing, version changes, release or network profile requests are performed.
Requires JDK 17, SDK 35 and the repository's existing Gradle dependencies.
"""
import argparse,json,os,pathlib,shlex,subprocess,tempfile,xml.etree.ElementTree as ET,hashlib,re,shutil
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--inventory-only',action='store_true');p.add_argument('--scale-scans-only',action='store_true');p.add_argument('--baseline-ref',required=True);p.add_argument('--baseline-path',type=pathlib.Path);p.add_argument('--rounds',type=int,default=3);p.add_argument('--output',type=pathlib.Path,default=ROOT/'artifacts/optimization-native');a=p.parse_args()
if a.rounds<1:p.error('--rounds must be positive')
out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
run=lambda cmd,cwd:subprocess.check_output(cmd,cwd=cwd,text=True).strip()
ref=run(['git','rev-parse',a.baseline_ref+'^{commit}'],ROOT)
temporary=None
if a.baseline_path:
 baseline=a.baseline_path.resolve()
 if run(['git','rev-parse','HEAD'],baseline)!=ref:raise SystemExit('Baseline checkout does not match the pinned commit')
 if run(['git','status','--porcelain','--untracked-files=no'],baseline):raise SystemExit('Baseline tracked files are modified')
else:
 temporary=tempfile.TemporaryDirectory(prefix='insfav-optimization-');baseline=pathlib.Path(temporary.name)/'baseline'
 subprocess.run(['git','worktree','add','--detach',str(baseline),ref],cwd=ROOT,check=True)

def fingerprint(root):
 digest=hashlib.sha256()
 for directory in ['app/src/main/java','web']:
  for file in sorted((root/directory).rglob('*')):
   if file.is_file():digest.update(str(file.relative_to(root)).encode());digest.update(file.read_bytes())
 return digest.hexdigest()

names=['IndexWriteBenchmarkTest','StatusSnapshotBenchmarkTest','NativeStatusBenchmarkTest','fetcher/QueueReadBenchmarkTest','fetcher/BrokerIdleBenchmarkTest','RescanBenchmarkTest']
if a.scale_scans_only:names=['RescanScaleBenchmarkTest']
if a.inventory_only:names=['InventoryPageBenchmarkTest']
extra=out/'external-test-source/com/instagram/insfav';extra.mkdir(parents=True,exist_ok=True)
for name in names:
 rel=pathlib.Path('app/src/test/java/com/instagram/insfav')/(name+'.java')
 if not (baseline/rel).exists():
  dest=extra/(name+'.java');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes((ROOT/rel).read_bytes())
quoted=str(out/'external-test-source').replace('\\','\\\\').replace("'","\\'")
init=out/'baseline.init.gradle';init.write_text("gradle.projectsEvaluated { gradle.rootProject.project(':app').android.sourceSets.test.java.srcDir('"+quoted+"') }\n")
# Retire only fixture directories created by this completed test JVM. Measurements
# finish before cleanup; unrelated temp paths and all pre-existing paths are untouched.
fixture_prefixes = {'robolectric-'}
for source_root in [ROOT, baseline]:
 for source in (source_root/'app/src/test').rglob('*.java'):
  fixture_prefixes.update(value for value in re.findall(r'createTempDirectory\(\s*"([^"]+)"', source.read_text()) if len(value) >= 3)
def temp_paths():
 return set(pathlib.Path(tempfile.gettempdir()).iterdir())
def cleanup_fixtures(before):
 for path in temp_paths() - before:
  if path.is_dir() and any(path.name.startswith(prefix) for prefix in fixture_prefixes):
   shutil.rmtree(path)

results={'baselineCommit':ref,'candidateSourceSha256':fingerprint(ROOT),'baselineSourceSha256':fingerprint(baseline),'physicalDevice':False,'records':[]}
try:
 for round in range(a.rounds):
  order=[('before',baseline),('after',ROOT)]
  if round%2:order.reverse()
  for variant,cwd in order:
   command=shlex.split(os.environ.get('GRADLE_COMMAND','./gradlew'))+(['-I',str(init)] if variant=='before' else [])+['-Pbenchmark',':app:testDebugUnitTest','--rerun-tasks']
   for name in names:command+=['--tests','*'+name.split('/')[-1]]
   existing_temp = temp_paths()
   with (out/f'{variant}-{round}.log').open('w') as log:completed=subprocess.run(command,cwd=cwd,stdout=log,stderr=subprocess.STDOUT)
   if completed.returncode:raise SystemExit(f'Benchmark failed: {variant}-{round}.log')
   row={'variant':variant,'round':round,'measurements':[]}
   for file in sorted((cwd/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    suite=ET.parse(file).getroot()
    if int(suite.get('failures','0')) or int(suite.get('errors','0')):raise SystemExit('Failed benchmark suite')
    for line in (suite.findtext('system-out') or '').splitlines():
     label,_,value=line.partition(' ')
     if label in ['INDEX_WRITE','STATUS_SNAPSHOT','SMALL_STATUS','QUEUE_READ','CATALOG_SEED','BROKER_IDLE','RESCAN_15000','RESCAN_SCALE','INVENTORY_PAGES']:row['measurements'].append({'kind':label,**json.loads(value)})
   cleanup_fixtures(existing_temp)
   results['records'].append(row);(out/'results.json').write_text(json.dumps(results,indent=2)+'\n');print(f'{variant} round {round+1} passed',flush=True)
finally:
 if temporary:
  subprocess.run(['git','worktree','remove',str(baseline)],cwd=ROOT,check=True);temporary.cleanup()
