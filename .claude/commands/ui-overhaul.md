---
description: Orchestrated visual overhaul of a Compose app — brief, tokens, per-screen agents, screenshots, critique
argument-hint: [scope: "all" | screen/package names] [optional design direction]
allowed-tools: Bash(./gradlew:*), Bash(adb:*), Bash(emulator:*), Bash(rg:*), Bash(git:*), Read, Edit, Write, Glob, Grep, Agent, TaskCreate, TaskUpdate
---

Run a graphic overhaul of the Compose UI. Scope and direction: $ARGUMENTS
Load the `compose-design` skill and follow its process. Keep the main context for decisions; delegate reading, per-screen work, and review to agents.

## Phase 0 — Inventory (one Explore agent, read-only)
Ask it to return, not dump: list of screen-level composables with file paths; the current theme files and whether tokens are real or defaults; every literal `Color(0x…)`, `.dp`, `.sp` count outside `ui/theme`; existing `@Preview`s and `@PreviewTest`s in `app/src/screenshotTest/`; navigation graph entry points. Write the inventory to `PROGRESS.md` under "Now".

## Phase 1 — Baseline screenshots
Screenshot testing is set up (AGP test suite): copy `app/src/screenshotTestDefaultDebug/reference/` to `design/before/` (run `./gradlew :app:testDebugScreenshotTestDefaultTestSuite --console=plain -q` first; it must pass). For flows the previews can't show, boot the emulator, install, and screenshot each screen in scope via adb into `design/before/`. Read 3–4 of them and write a 5-line honest critique (what dominates, what's generic, what's inconsistent).

## Phase 2 — Brief and tokens (main context, then STOP for approval)
Produce the brief and token plan from the skill (§4 steps 1–3). Present it compactly — palette hexes with roles, type pairing, shapes, spacing, motion, the one memorable element — and the list of screens in scope with a one-line intent per screen. **Wait for the user's approval before editing any file.** If the user isn't available, proceed only with the theme files (Phase 3) and stop before screens.

## Phase 3 — Theme first
Implement `ui/theme/{Color,Type,Shape,Spacing,Theme}.kt` (or the project's equivalents). Build once. Do not touch screens yet.

## Phase 4 — Screens in parallel
One agent per screen (or per feature package), dispatched in a single batch, each with: the token plan, its exact file list (strict scope — it may not edit theme files or other screens), the screen's intent line, the tells to avoid, and the requirement to keep the screen's `XxxContent` stateless and its `@PreviewTest @ScreenPreviews` preview in `app/src/screenshotTest/kotlin/com/akane/voltwise/ui/screens/` rendering. Each returns: files changed, decisions, anything it needed from the theme that didn't exist (collect these; add to theme in main context, don't let agents fork the theme).

## Phase 5 — See it
Run `bash .claude/scripts/run_screenshot_tests.sh` (failures are expected: they are the restyle) and copy `app/build/intermediates/debug/testDebugScreenshotTestDefaultTestSuite/results/rendered/` into `design/after/`. Read before/after pairs. For each screen note: improved / regressed / unchanged, and one concrete fix if needed. Apply fixes (small ones directly; large ones back to the screen's agent). Re-screenshot only the changed screens.

## Phase 6 — Independent review
Dispatch, in one batch: `compose-reviewer` (recomposition/stability/Modifier order/a11y) and a general-purpose agent with the `compose-design` skill that has *not* seen this session's work, given only the after-screenshots and the brief, asked for a designer's critique against §3 tells and the calibration list. Fix what's real; log what's rejected and why.

## Phase 7 — Prove and record
- `rg -n "Color\(0x|[0-9]+\.(dp|sp)\b" <ui dir> --glob '!**/theme/**'` → should be near-empty; list survivors with justification.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain -q` then ask the user to run `/screenshot-rebaseline` (user-invoked; it triages diffs first and rewrites the references).
- Update `PROGRESS.md`: move to "Recently done", decision log entries (tokens chosen, ideas rejected), audit-table "Compose review" row.
- Summarize in ≤10 lines: what changed visually, screenshot paths, open items. Do not paste code.
