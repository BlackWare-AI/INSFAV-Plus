#!/usr/bin/env python3
"""Pinned google-java-format. Use --check in CI; formatting-only commits are separate."""
import argparse, hashlib, os, subprocess, urllib.request
from pathlib import Path
VERSION='1.25.2';SHA='25157797a0a972c2290b5bc71530c4f7ad646458025e3484412a6e5a9b8c9aa6'
p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');a=p.parse_args()
cache=Path(os.environ.get('XDG_CACHE_HOME','~/.cache')).expanduser()/'insfav-plus-tools';cache.mkdir(parents=True,exist_ok=True);jar=cache/('google-java-format-'+VERSION+'.jar')
if not jar.exists():
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/'+VERSION+'/google-java-format-'+VERSION+'-all-deps.jar') as r:jar.write_bytes(r.read())
assert hashlib.sha256(jar.read_bytes()).hexdigest()==SHA,'Formatter checksum mismatch'
root=Path(__file__).resolve().parents[1];files=sorted([*root.glob('app/src/**/*.java'),*root.glob('tests/java/**/*.java')])
flags=['--dry-run','--set-exit-if-changed'] if a.check else ['--replace']
raise SystemExit(subprocess.run(['java','-Xmx256m','-jar',str(jar),*flags,*map(str,files)]).returncode)
