#!/usr/bin/env python3
"""List failed screenshot-suite tests with their reference, rendered and diff image paths.

Reads the JUnit XML written by `:app:testDebugScreenshotTestDefaultTestSuite`; run it after that task.
Output: one tab-separated line per failure (class, preview, diff %, ref, new, diff), then a summary.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "app/build/intermediates/debug/testDebugScreenshotTestDefaultTestSuite/results"


def main() -> int:
    reports = sorted(RESULTS.glob("TEST-*.xml"))
    if not reports:
        print(f"No results in {RESULTS.relative_to(ROOT)}; run the screenshot test task first.", file=sys.stderr)
        return 1
    total = failed = 0
    for report in reports:
        for case in ET.parse(report).getroot().iter("testcase"):
            total += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            failed += 1
            props = {p.get("name", "").removeprefix("PreviewScreenshot."): p.get("value", "") for p in case.iter("property")}
            diff = props.get("diffPercent", "")
            percent = f"{float(diff) * 100:.2f}%" if diff else "n/a"
            print("\t".join([
                case.get("classname", "").rsplit(".", 1)[-1],
                case.get("name", ""),
                percent,
                props.get("refImagePath", ""),
                props.get("newImagePath", ""),
                props.get("diffImagePath", ""),
            ]))
            if not diff:
                message = (problem.get("message") or problem.text or "").strip().splitlines()[:1]
                print(f"\t  {message[0] if message else 'no message'}")
    print(f"{failed} failed of {total} screenshot tests")
    return 0


if __name__ == "__main__":
    sys.exit(main())
