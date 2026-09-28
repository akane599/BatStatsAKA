# PROGRESS.md — BatStats

_Last updated: 2026-09-28 by claude_
_Claude: read this first every session. Update it before ending. Keep each section under ~15 lines; archive old entries at the bottom._

## Now (this session / this week)
Overhaul on `feat/overhaul` — plan + approved design brief: `~/.claude/plans/adaptive-wibbling-twilight.md` (one commit per verified phase, no push; before-refs in `design/before/`)
- [ ] P0a bug fixes · P0b dead code/resources
- [ ] Contracts (shared types/interfaces stubbed)
- [ ] Wave A: A1 measurement · A2 per-app parsing · A3 DB v5 · A4 theme
- [ ] Wave B: B1a sampler/repository · B1b settings v3 + gating · B2 per-app repositories · emulator cadence check
- [ ] P3a shell/navigation · P3b components/charts
- [ ] P4a Now → **visual checkpoint with user**
- [ ] P4b screens (6 agents) ∥ P5 notification/tile/widgets
- [ ] P4c removal of old screens/shims
- [ ] P6 tests, docs, reviews, fresh verification; user runs `/screenshot-rebaseline` + `/device-check`

## Next (ordered)

## Blockers / open questions

## Recently done (newest first, keep ~10)
- 2026-09-28 — /review-pr fixes: stale screenshot docs (compose-design, ui-overhaul), rebaseline restores refs on failure, stale-results guard in `screenshot_failures.py`, CI uploads screenshot results, hook fails loudly without jq, `@Immutable` on 4 screen state classes, `DestructiveConfirmDeviceTest` (7 device tests: stats reset + Clear All Data confirm/cancel/disabled/failure)
- 2026-09-28 — Claude Code automations: re-wired `check-res-db.sh` PostToolUse hook (verified exit 0 clean / exit 2 on duplicate key), removed dormant `ktlint -F` hook, skills `/new-screen` `/screenshot-rebaseline` `/device-check`, agent `screenshot-diff-triager` (+ `.claude/scripts/screenshot_failures.py`, tested on a forced failure), CI runs the screenshot suite
- 2026-09-28 — AGP 9.5.0-alpha07; screenshots on AGP test suite; UTC/en-US pinned; 8 screens split into wrapper + `XxxContent` with screen-level screenshot tests (125 refs); LSP → official kotlin-lsp. Verified by: independent agent — unit 107/107, screenshot suite 125/125, emulator smoke of all 8 screens (no crash/exception); compose-reviewer no regressions; module-graph-auditor pass
- 2026-09-28 — Added Compose Preview Screenshot Testing (`com.android.compose.screenshot` 0.0.1-alpha16) + smoke test `TelemetryChartScreenshotTest` (light/dark/1.5× font). Verified by: independent agent — `assembleDebug` exit 0, `testDebugUnitTest` 107/107, `validateDebugScreenshotTest` 3/3, refs unchanged

## Decision log (never delete, one line each)
- 2026-09-28 — Overhaul plan audited by a fresh-context `/plan-audit`: APPROVE WITH FIXES (4 blockers + 23 fixes, all folded into the plan before approval). User approved dark-only + OLED, 4 tabs, on-demand per-app stats, QUERY_ALL_PACKAGES, auto calibration.
- 2026-09-28 — Removed the `ktlint -F` on-edit hook: ktlint absent (no-op) and, if installed, it would reformat whole files (codebase uses `;` chains/wildcard imports). Revisit if ktlint is adopted with a baseline.
- 2026-09-28 — CI runs `:app:testDebugScreenshotTestDefaultTestSuite` in the existing verify step (same JDK 21/Linux as local). Revisit if CI renders differ from local refs (font/JDK drift).
- 2026-09-28 — Screen refactor: wrappers keep exact signatures (NavGraph untouched); DetailedStats kernel data passed as a `kernelContent` slot so root reads + recomposition stay inside the kernel-tab list item (compose-reviewer flagged whole-screen recomposition otherwise).
- 2026-09-28 — Screen screenshot matrix: 9 sizes (400/610/900 × 400/500/1000 dp) + dark + 1.5× font per screen, OLED + secondary states at 400×500 (`@PhonePreview`) or 400×1000 (`@TallPhonePreview` when the state sits below the fold). ~14 MB of refs committed.
- 2026-09-28 — Moved to AGP 9.5.0-alpha07 (alpha) at user request so screenshots can use AGP test suites (standalone plugin deprecated). Revisit: move to 9.5.0 stable when released.
- 2026-09-28 — Pinned timezone/locale in the screenshot Test JVMs instead of injecting a clock/formatter into app code, because it keeps production code unchanged. Revisit if a component needs per-test locales.
- 2026-09-28 — Replaced kotlinsense (fwcd kotlin-language-server, embedded Kotlin 2.1 compiler) with the official `kotlin-lsp` plugin (JetBrains) because fwcd could not read Kotlin 2.5 metadata and flagged the screenshotTest source set as unresolved.
- 2026-09-28 — Chose standalone `com.android.compose.screenshot` plugin over AGP test suites because AGP is 9.4.0 (suites need ≥ 9.5.0-alpha03) and AGP bumps need approval. Revisit when AGP ≥ 9.5.0-alpha03 (standalone setup is deprecated there). (Superseded 2026-09-28: moved to AGP 9.5.0-alpha07 + test suites, see above.)

## Known debt / follow-ups
- Settings "Clear All Data": `clearingHistory = false` runs after the suspending `showSnackbar`, so for ~4 s after a failed clear the dialog buttons stay disabled, and after a successful clear reopening the dialog shows disabled buttons. Pre-existing; fix by resetting the flag before showing the snackbar (then drop the dismiss step in `DestructiveConfirmDeviceTest`) — low
- Review suggestions not yet done: `DetailedStatsTab` enum instead of `Int` tab; one shared Shizuku/access UI class for Dashboard + DetailedStats; `KernelDetailsState` single `sources` list; `DiagnosticsUiState` builder shared by wrapper/tests; previews for DetailedStats tabs 1–5/error, Settings dialogs, SessionDetails failed/recording/interrupted/imported — low
- Transient UI (snackbars) can't be screenshot-tested: layoutlib captures one frame before `showSnackbar` renders — low
- DetailedStats/History/Settings `rememberSaveable` keys moved with the split; a state bundle saved by the previous APK won't restore once after update — low
- `app/build.gradle.kts` `unitTests.isIncludeAndroidResources = true` now required by the screenshot suite (adds resource processing to plain JUnit tests) — low

## Audit status
| Area | Last run | Result | Command |
|---|---|---|---|
| Security (claude-security) | — | — | `/claude-security` |
| Compose review | 2026-09-28 | /review-pr (5 agents): 0 critical, 5 important fixed (stale docs, rebaseline data loss, CI artifacts, stale results, confirm tests), @Immutable added; type redesigns deferred | pr-review-toolkit + compose-reviewer |
| Module graph | 2026-09-28 | Pass (2nd run, AGP 9.5.0-alpha07 + test suite) — deps test-scoped, lazy Test config, release unaffected; med: alpha AGP (user-approved) | module-graph-auditor agent |
| Lint (Android lint) | — | — | `./gradlew :app:lintDebug -q` |
| Emulator QA (API 36) | 2026-09-28 | Pass — all 8 screens reached/interacted, no crash; left one short charging session in the emulator DB | android-emulator-qa skill |

## Environment notes (quirks discovered)
- AGP 9.5.0-alpha07 registers `generate<Variant>ComposePreviewRunfiles` for every Compose variant; realizing all tasks (`./gradlew tasks --all`) fails on `release` (unit tests disabled). Normal builds are fine. List tasks via `tasks.names` in an init script instead.
- Suite task names are variant-first (`testDebugScreenshotTestDefaultTestSuite`), not the doc's `testScreenshotTestDefaultDebugTestSuite`.
- Screenshot plugin truncates `@Preview(name=…)` at "." (`"Font 1.5"` → `5_….png`); keep preview names dot-free.
- Shell is zsh: `${PIPESTATUS[0]}` is empty — use `$pipestatus[1]` or redirect Gradle output to a file and check `$?`.

---
## Archive
