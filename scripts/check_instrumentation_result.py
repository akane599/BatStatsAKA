#!/usr/bin/env python3
"""Require actual successful AndroidJUnitRunner results, not merely adb exit zero."""
import re
import sys
from pathlib import Path


def require_success(output: str) -> int:
    output = output.replace("\r", "")
    summary = re.search(r"^OK \(([1-9][0-9]*) tests?\)$", output, re.MULTILINE)
    completed = re.search(r"^INSTRUMENTATION_CODE: -1$", output, re.MULTILINE)
    failed = re.search(
        r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|^INSTRUMENTATION_STATUS_CODE: -[1-4]$",
        output, re.MULTILINE,
    )
    if summary is None or completed is None or failed:
        raise ValueError("No complete, successful test run, or a test failed/was skipped")
    return int(summary.group(1))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check_instrumentation_result.py REPORT")
    try:
        count = require_success(Path(sys.argv[1]).read_text())
    except ValueError as error:
        raise SystemExit(f"{error}; inspect {sys.argv[1]}") from error
    print(f"Confirmed {count} successful device tests in {sys.argv[1]}")
