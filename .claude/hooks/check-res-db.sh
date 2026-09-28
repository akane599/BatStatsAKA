#!/usr/bin/env bash
# PostToolUse (Edit|Write): run the repo's fast consistency checks for the file just edited.
# Exit 2 feeds the failure back to Claude; unrelated files exit 0 immediately.
command -v jq >/dev/null || { echo 'check-res-db: jq is missing, resource/migration checks skipped' >&2; exit 2; }
f=$(jq -r '.tool_input.file_path // empty')
case "$f" in
  */src/main/res/*/*.xml)
    check=scripts/check_resources.py ;;
  */battery/data/db/*.kt | */app/schemas/* | */DatabaseMigrationTest.kt)
    check=scripts/check_migrations.py ;;
  *)
    exit 0 ;;
esac
root=${CLAUDE_PROJECT_DIR:-$(git -C "$(dirname "$f")" rev-parse --show-toplevel)}
out=$(python3 "$root/$check" 2>&1) || { printf '%s failed:\n%s\n' "$check" "$out" >&2; exit 2; }
