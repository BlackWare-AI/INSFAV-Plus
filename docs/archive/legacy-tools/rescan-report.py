#!/usr/bin/env python3
"""Collect measured JUnit output; never substitute fixture estimates for device timings."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

BASELINE = "ba7812fc38e7e35834b909d72740c2873b7e05e7"


def measurement(path, marker):
    root = ET.parse(path).getroot()
    if int(root.get("tests", "0")) < 1 or any(int(root.get(k, "0")) for k in ("failures", "errors", "skipped")):
        raise ValueError("Missing, failed, or skipped measurement test")
    lines = [line[len(marker) + 1:] for out in root.iter("system-out") for line in (out.text or "").splitlines() if line.startswith(marker + " ")]
    if len(lines) != 1:
        raise ValueError("Expected exactly one measured output")
    return json.loads(lines[0])


def collect(before_path, after_path, concurrency_path):
    before = measurement(before_path, "RESCAN_15000")
    after = measurement(after_path, "RESCAN_15000")
    pools = measurement(concurrency_path, "RESCAN_CONCURRENCY")
    for row, expected in ((before, 30000), (after, 15000)):
        if row.get("files") != 15000 or row.get("coldOpens") != expected or row.get("warmOpens") != 0 or row.get("sourceWrites") != 0 or row.get("sourceBytesAndDatesUnchanged") is not True:
            raise ValueError("File count, read cost, or source integrity did not match the benchmark")
        if row.get("coldMs", -1) <= 0 or row.get("warmMs", -1) < 0:
            raise ValueError("Invalid measured durations")
    if sorted(row["workers"] for row in pools["results"]) != [1, 2, 4]:
        raise ValueError("Incomplete concurrency comparison")
    for row in pools["results"]:
        if row["opens"] != 1024 or row["metadataQueries"] != 1 or not 1 <= row["peakValidations"] <= row["workers"]:
            raise ValueError("Invalid concurrency evidence")
    return {"baselineCommit": BASELINE, "evidence": "UNIT_TEST / synthetic SAF latency, not device timing", "before": before, "after": after,
            "coldSpeedup": before["coldMs"] / after["coldMs"], "warmSpeedup": before["warmMs"] / after["warmMs"] if after["warmMs"] else None,
            "concurrency": pools, "physicalDeviceTested": False, "liveInstagramTested": False}


def main():
    parser = argparse.ArgumentParser()
    for name in ("before", "after", "concurrency", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    result = collect(args.before, args.after, args.concurrency)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
