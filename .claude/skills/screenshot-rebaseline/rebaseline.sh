#!/usr/bin/env bash
# Regenerate every screenshot reference from scratch, then summarise the result against git HEAD.
# Starting from an empty folder drops PNGs of renamed/removed previews, which the update task would
# leave behind. The old references are moved aside and put back if the update fails or is interrupted.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1
refs=app/src/screenshotTestDefaultDebug/reference
backup=app/build/screenshot-reference-backup
log=app/build/screenshot-rebaseline.log
mkdir -p app/build || exit 1

# A leftover backup means an earlier run was killed before it could restore; never delete it blindly.
if [ -e "$backup" ]; then
  echo "An earlier run left the previous references in $backup. Nothing was changed." >&2
  echo "Restore them:  rm -r $refs; mv -T $backup $refs   (or delete $backup if $refs is correct)" >&2
  exit 1
fi

restore() {
  [ -d "$backup" ] || return 0
  if [ -e "$refs" ] && ! rm -r "$refs"; then
    echo "RESTORE FAILED: could not remove the partial $refs; previous references are in $backup" >&2
    return 1
  fi
  if mv -T "$backup" "$refs"; then
    echo "previous references restored"
  else
    echo "RESTORE FAILED: previous references are in $backup" >&2
    return 1
  fi
}
trap 'restore; exit 130' INT TERM HUP

if [ -d "$refs" ] && ! mv -T "$refs" "$backup"; then
  echo "could not move $refs aside; nothing was changed" >&2
  exit 1
fi
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --console=plain -q >"$log" 2>&1
status=$?
if [ "$status" -ne 0 ]; then
  grep -E "^e: |error:|FAILED|What went wrong" -A3 "$log" | head -30
  restore
  echo "update failed (exit $status); full log: $log"
  exit "$status"
fi
trap - INT TERM HUP
if [ -d "$backup" ] && ! rm -r "$backup"; then
  echo "warning: new references are in place but $backup could not be deleted" >&2
fi

# References regenerate byte-identically for a fixed engine/AGP/JDK, so an M line normally means the
# pixels changed; after bumping any of those, check the diffs before trusting the counts.
changes=$(git status --porcelain --untracked-files=all -- "$refs")
count() { grep -c "$1" <<<"$changes" || true; }
echo "references: $(find "$refs" -name '*.png' | wc -l) PNGs"
echo "added:   $(count '^??')"
echo "changed: $(count '^ M')"
echo "removed: $(count '^ D')"
if [ -n "$changes" ]; then sed "s|$refs/com/akane/voltwise/ui/||" <<<"$changes" | head -60; fi
exit 0
