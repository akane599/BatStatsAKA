---
name: screenshot-rebaseline
description: Regenerate BatStats screenshot reference images cleanly after an intended UI change — triages failures first, drops stale PNGs of renamed/removed previews, and reports which references were added, changed or removed vs git HEAD.
disable-model-invocation: true
---

# Re-baseline screenshot references

Overwrites `app/src/screenshotTestDefaultDebug/reference/`. Only for UI changes the user intends. The shell is zsh: redirect Gradle output to a file and check `$?`.

1. **Check what fails now:** `bash .claude/scripts/run_screenshot_tests.sh` (Bash timeout 600000; it runs the suite with `--rerun` and lists failures).
   - Exit 0 and no previews were added, renamed or removed → nothing to re-baseline; stop and say so.
   - Exit 1 → dispatch the `screenshot-diff-triager` agent, telling it the results are fresh. Continue only if it ends with `REBASELINE OK`, or the user explicitly accepts the regressions it lists.
   - Exit 3 → the build failed before tests ran; show the errors and stop.
2. **Regenerate from scratch:** `bash .claude/skills/screenshot-rebaseline/rebaseline.sh` (Bash timeout 600000). It moves the reference folder aside, runs `updateDebugScreenshotTestDefaultTestSuite`, puts the old folder back if that fails or is interrupted, and prints added/changed/removed counts plus the file list. If it refuses because an earlier run left `app/build/screenshot-reference-backup`, show the user its restore instructions and stop.
3. **Sanity-check the output.**
   - `removed` should only list previews you renamed or deleted; anything else means a preview stopped compiling into the suite — investigate.
   - Read one PNG per new test function (prefer `W400H500`) and any changed PNG the triager did not already cover. Look for blank renders, clipping, content below the fold (use `@TallPhonePreview`).
4. **Confirm:** `bash .claude/scripts/run_screenshot_tests.sh` must exit 0.
5. **Report** the counts and remind the user to commit the reference changes in the same commit as the UI change.
