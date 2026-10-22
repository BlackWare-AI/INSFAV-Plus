"""Package actual unit-test evidence; offline diagnostics are never a device claim."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def collect(results, output, source):
    suites = [ET.parse(path).getroot() for path in sorted(results.glob("TEST-*.xml"))]
    totals = {key: sum(int(suite.get(key, 0)) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    if totals["tests"] == 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Passing, nonempty test evidence is required.")
    text = "\n".join(suite.findtext("system-out", "") for suite in suites)
    start, end = "FAST_SKIP_FIXTURE_DIAGNOSTICS_BEGIN\n", "FAST_SKIP_FIXTURE_DIAGNOSTICS_END"
    if start not in text or end not in text:
        raise ValueError("Fast-skip diagnostic export evidence is required.")
    sample = text.split(start, 1)[1].split(end, 1)[0]
    output.mkdir(parents=True, exist_ok=True)
    (output / "diagnostics-offline-fixture.txt").write_text(
        "OFFLINE UNIT TEST FIXTURE — NOT A DEVICE OR LIVE INSTAGRAM DIAGNOSTIC\n" + sample,
        encoding="utf-8")
    record = {"version": "5.0.0", "build": 9, "sourceCommit": source,
              "nativeTests": totals, "diagnostics": "offline unit-test export",
              "physicalDeviceTested": False, "liveInstagramTested": False}
    (output / "test-evidence.json").write_text(json.dumps(record, indent=2) + "\n")
    return record


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source", required=True)
    args = parser.parse_args()
    collect(args.results, args.output, args.source)
