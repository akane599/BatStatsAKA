#!/usr/bin/env bash
# Run the screenshot suite fresh and list failures, rejecting results that this run did not produce.
# Exit: 0 all passed · 1 tests ran and some failed (listed) · 3 build failed before tests ran.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1
log=app/build/ss-test.log
mkdir -p app/build || exit 1
touch app/build/ss-run.stamp
# --rerun: an UP-TO-DATE skip would leave old results that look like a fresh run.
./gradlew :app:testDebugScreenshotTestDefaultTestSuite --rerun --console=plain -q >"$log" 2>&1
gradle_status=$?
python3 .claude/scripts/screenshot_failures.py
parse_status=$?
echo "gradle exit=$gradle_status, parser exit=$parse_status, log: $log"
if [ "$parse_status" -ne 0 ]; then
  echo "BUILD FAILED before tests ran:" >&2
  grep -E "^e: |error:|What went wrong" -A3 "$log" | head -30 >&2
  exit 3
fi
[ "$gradle_status" -eq 0 ] && exit 0
exit 1
