#!/usr/bin/env bash
# Regenerate every screenshot reference from scratch, then summarise the result against git HEAD.
# Deleting first drops PNGs of renamed/removed previews, which the update task would leave behind.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1
refs=app/src/screenshotTestDefaultDebug/reference
log=app/build/screenshot-rebaseline.log
mkdir -p app/build

[ -d "$refs" ] && rm -r "$refs"
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --console=plain -q >"$log" 2>&1
status=$?
if [ "$status" -ne 0 ]; then
  grep -E "^e: |error:|FAILED|What went wrong" -A3 "$log" | head -30
  echo "update failed (exit $status); full log: $log"
  exit "$status"
fi

# The renderer is deterministic, so an M line means the pixels changed.
changes=$(git status --porcelain --untracked-files=all -- "$refs")
count() { grep -c "$1" <<<"$changes" || true; }
echo "references: $(find "$refs" -name '*.png' | wc -l) PNGs"
echo "added:   $(count '^??')"
echo "changed: $(count '^ M')"
echo "removed: $(count '^ D')"
[ -n "$changes" ] && sed "s|$refs/app/batstats/ui/||" <<<"$changes" | head -60
exit 0
