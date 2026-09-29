#!/usr/bin/env python3
"""List failed screenshot-suite tests with their reference, rendered and diff image paths.

Normally called by `.claude/scripts/run_screenshot_tests.sh`, which touches `app/build/ss-run.stamp` and
runs `:app:testDebugScreenshotTestDefaultTestSuite --rerun`; calling it again afterwards re-lists that run.
Output: one tab-separated line per failure (test class, test case `<function>_<preview>_{params}`,
diff %, reference PNG, rendered PNG, diff PNG), then a summary.
Exits 1 when there are no results, 3 when the results are older than the stamp (that run failed before
any test ran, e.g. a compile error), and 4 when there is no stamp to judge freshness by.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "app/build/intermediates/debug/testDebugScreenshotTestDefaultTestSuite/results"
STAMP = ROOT / "app/build/ss-run.stamp"


def main() -> int:
    reports = sorted(RESULTS.glob("TEST-*.xml"))
    if not reports:
        print(f"No results in {RESULTS.relative_to(ROOT)}; run the screenshot test task first.", file=sys.stderr)
        return 1
    if not STAMP.exists():
        print("No run stamp; run `bash .claude/scripts/run_screenshot_tests.sh` instead of the task directly.", file=sys.stderr)
        return 4
    if min(report.stat().st_mtime for report in reports) < STAMP.stat().st_mtime:
        print("STALE: these results predate the last run, which failed before any test ran.", file=sys.stderr)
        return 3
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
