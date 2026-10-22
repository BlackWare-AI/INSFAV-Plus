#!/usr/bin/env python3
"""Legacy entrypoint: the integrated WorkManager dependency requires a Gradle build."""
import argparse, pathlib, subprocess
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--release',action='store_true')
args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
print('The SDK-only route is retired; delegating to the pinned dependency-aware Gradle wrapper.',flush=True)
subprocess.run(['sh',str(root/'gradlew'),':app:assembleRelease' if args.release else ':app:assembleDebug'],cwd=root,check=True)
