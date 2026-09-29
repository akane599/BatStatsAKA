# PROGRESS.md — BatStats

_Last updated: 2026-09-28 by claude_
_Claude: read this first every session. Update it before ending. Keep each section under ~15 lines; archive old entries at the bottom._

## Now (this session / this week)
Overhaul on `feat/overhaul` — plan + approved design brief: `~/.claude/plans/adaptive-wibbling-twilight.md`. Executed, reviewed and verified; PR #1 open with green checks (the run ledger was deleted after CI passed). Old before/after renders in `design/` (gitignored).
- [x] P0a bug fixes · P0b dead code/resources · Contracts
- [x] Wave A: A1 measurement · A2 per-app parsing · A3 DB v5 · A4 theme (merged 6f9d130 + 2be6ff9)
- [x] Wave B: B1a sampler (833cc5a) · B1b settings v3 + gating (ac8bb6f) · B2 per-app repositories (a7e089f) · cadence check screen on/off ✓ (Now-demand half → P4a)
- [x] P3a shell/navigation (merged 935e985) · [x] P3b components/charts (2 fix rounds, merged 2770ebc)
- [x] P4a Now (c43b796 + a1b36e7; user approved the look, 2×2 phone readouts, true since-unplug window)
- [x] P4b ∥ P5 wave: 9 tasks reviewed + merged (7ea5448); WAVE-INTEGRATION de7eae5 (wiring, shims gone, shared design capacity, DAO queries, device tests green one class at a time) — review running
- [x] P4c removal of old screens/shims (c5bd318)
- [x] P6: tests, docs, final reviews → fix wave (990d8a3), verification PASS, screenshots rebaselined (e667cf9, 201/201), /device-check PASS (ordinary 70/70, Shizuku 1/1)

## Next (ordered)
1. PR #1 (https://github.com/akane599/BatStatsAKA/pull/1, feat/overhaul → codex/android16-reliability): all checks green on ae28239 (both build jobs incl. the 16 KB-page emulator suite). Merge when reviewed; handle any feedback on `feat/overhaul`.

## Blockers / open questions

## Recently done (newest first, keep ~10)
- 2026-09-28 — /review-pr fixes: stale screenshot docs (compose-design, ui-overhaul), rebaseline restores refs on failure, stale-results guard in `screenshot_failures.py`, CI uploads screenshot results, hook fails loudly without jq, `@Immutable` on 4 screen state classes, `DestructiveConfirmDeviceTest` (7 device tests: stats reset + Clear All Data confirm/cancel/disabled/failure)
- 2026-09-28 — Claude Code automations: re-wired `check-res-db.sh` PostToolUse hook (verified exit 0 clean / exit 2 on duplicate key), removed dormant `ktlint -F` hook, skills `/new-screen` `/screenshot-rebaseline` `/device-check`, agent `screenshot-diff-triager` (+ `.claude/scripts/screenshot_failures.py`, tested on a forced failure), CI runs the screenshot suite
- 2026-09-28 — AGP 9.5.0-alpha07; screenshots on AGP test suite; UTC/en-US pinned; 8 screens split into wrapper + `XxxContent` with screen-level screenshot tests (125 refs); LSP → official kotlin-lsp. Verified by: independent agent — unit 107/107, screenshot suite 125/125, emulator smoke of all 8 screens (no crash/exception); compose-reviewer no regressions; module-graph-auditor pass
- 2026-09-28 — Added Compose Preview Screenshot Testing (`com.android.compose.screenshot` 0.0.1-alpha16) + smoke test `TelemetryChartScreenshotTest` (light/dark/1.5× font). Verified by: independent agent — `assembleDebug` exit 0, `testDebugUnitTest` 107/107, `validateDebugScreenshotTest` 3/3, refs unchanged

## Decision log (never delete, one line each)
- 2026-09-29 Overhaul executed via subagent-driven development; ~47 controller rulings (listed in the final session summary; ledger deleted after CI went green). User decisions at the Now checkpoint: look approved, 2×2 phone readouts, since-unplug = open discharge session. Notable: QS tile without ACTIVE_TILE; Reset settings removed; Sparkline removed; package-cycle refactor deferred to debt.
- 2026-09-28 — Overhaul outline token: M3 `outline` = #63758D (approved #3A4452 kept as `outlineVariant`) because #3A4452 was 1.45–1.95:1 on surfaces (invisible Switch track); smallest lightening passing ≥3:1 on every tier.
- 2026-09-28 — Overhaul ETA/calibration: "sleep gaps" skip only observation gaps (engine's awake-time rule), not CPU-suspended intervals — counter Δq across suspend is real idle drain. Calibration fast path requires plugged == 0 and a falling counter.
- 2026-09-28 — Overhaul persistence: screen-off polls saved at most every 30 s (elapsed clock) so a stuck 2 s demand token can't write every 2 s; a session end always forces a save.
- 2026-09-28 — Parallel waves run in isolated git worktrees with Gradle serialised by `flock /tmp/batstats-gradle.lock`; worktrees sometimes start at origin/main — agents verify/reset their base first. `.superpowers/` and `.claude/worktrees/` are gitignored (a worktree once committed a report).
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
- Package cycles from the overhaul audit: replace `BatteryGraph` service locator with Koin inject; move AppUsage enums/row into `data.db`; SessionEvidence → data.db; EtaHold off `BatteryRepository.Realtime`; Notifier out of util; SamplingDemand into data.sampling; ChartMath/TimePoint/TimeWindow into a Compose-free file; Destinations out of ui.
- Review suggestions not yet done: `DetailedStatsTab` enum instead of `Int` tab; one shared Shizuku/access UI class for Dashboard + DetailedStats; `KernelDetailsState` single `sources` list; `DiagnosticsUiState` builder shared by wrapper/tests; previews for DetailedStats tabs 1–5/error, Settings dialogs, SessionDetails failed/recording/interrupted/imported — low
- Transient UI (snackbars) can't be screenshot-tested: layoutlib captures one frame before `showSnackbar` renders — low
- DetailedStats/History/Settings `rememberSaveable` keys moved with the split; a state bundle saved by the previous APK won't restore once after update — low
- `app/build.gradle.kts` `unitTests.isIncludeAndroidResources = true` now required by the screenshot suite (adds resource processing to plain JUnit tests) — low

## Audit status
| Area | Last run | Result | Command |
|---|---|---|---|
| Security (claude-security) | — | — | `/claude-security` |
| Compose review | 2026-09-29 (overhaul) | compose-reviewer + independent design critic: 2 important (tab switch wiped tab state/VMs; Apps search async text) + error-treatment unification, History labels at 1.5× — all fixed in 990d8a3 | android-kmp-playbook:compose-reviewer + design critic on renders |
| Module graph | 2026-09-28 (overhaul) | 1 important (unused libs.material) + dep cleanup + WidgetUpdater uncaught exception + 10 `!!` → final fix wave; package cycles (BatteryGraph locator, db↔apps enums, …) → debt | module-graph-auditor agent |
| Lint (Android lint) | 2026-09-29 | Pass (debug + preview, 0 errors) | `./gradlew :app:lintDebug :app:lintPreview -q` |
| Emulator QA (API 36) | 2026-09-29 (overhaul, /device-check) | Pass — ordinary 70/70 (19 classes), Shizuku 1/1 (official Shizuku 13.6.0 as shell); API 36 ranchu, 4 KB pages; screenshot suite 201/201 after rebaseline (e667cf9) | `bash scripts/check_android_device.sh` |

## Environment notes (quirks discovered)
- CI's device jobs use the `pixel_2` profile (411×731 dp): device tests must scroll to below-the-fold content. The 16 KB-page job needs 4 GB emulator RAM (at 2 GB the low-memory killer took the app under test). `check_android_device.sh` saves `<phase>-crash-logcat.txt` / `<phase>-logcat-tail.txt` for failed phases in the uploaded reports.
- AGP 9.5.0-alpha07 registers `generate<Variant>ComposePreviewRunfiles` for every Compose variant; realizing all tasks (`./gradlew tasks --all`) fails on `release` (unit tests disabled). Normal builds are fine. List tasks via `tasks.names` in an init script instead.
- Suite task names are variant-first (`testDebugScreenshotTestDefaultTestSuite`), not the doc's `testScreenshotTestDefaultDebugTestSuite`.
- Screenshot plugin truncates `@Preview(name=…)` at "." (`"Font 1.5"` → `5_….png`); keep preview names dot-free.
- Shell is zsh: `${PIPESTATUS[0]}` is empty — use `$pipestatus[1]` or redirect Gradle output to a file and check `$?`.

---
## Archive
