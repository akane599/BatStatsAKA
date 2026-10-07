# PROGRESS.md — BatStats

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Now
- [x] Bootstrap complete — verify build command
- [x] US-1 audit fixes (docs/AUDIT_RESULTS_2026-10-07.md, SQ-2..SQ-31) integrated into local feat/overhaul @ 6ca5ffd; full gate green; not pushed
- [x] US-2 bug-hunt fixes (SQ-37..47, SQ-54) integrated into local feat/overhaul @ 4672588; full gate green; not pushed. Open: SQ-52, SQ-53, SQ-55

## Baseline
- 2026-10-07 @ dc7a4c5 (working tree): debug build OK, assembleDebug exit 0 with JDK 21 and ANDROID_HOME=/home/dev/android-sdk; log `.claude/kit/logs/gradle.6vEcmiDx.log`. Existing testDebugUnitTest reports: 82 suites, 542 tests, 0 failures/errors/skips; not rerun during bootstrap.

## Decision log (never delete; one line each)
- 2026-10-07 — Bug hunt (US-2): an observation Reset is guarded writer-side (`resetApplies` only for an open DISCHARGE session) and restarts the sampler sequence only when it applies, because a stale notification Reset could otherwise close a charge, plugged or unknown session that the user never asked to reset.
- 2026-10-07 — Bug hunt (US-2): history trimming keeps 200 rows of headroom below MAX_SAMPLES/MAX_SESSIONS, and an import is refused only when it grows the table past the cap, because cleanup runs only every 200 inserts, so the tables legitimately exceed the cap between trims and an ALL export/re-import of that history used to be refused.
- 2026-10-07 — Bug hunt (US-2): import clamps clock-corrected session coverage to the session span (allowance 5 s + span/10, capped at 15 min) instead of rejecting the file, and the monotonic-coverage check compares raw rows, because writer clock steps made valid backups unimportable and normalized rows made re-imports abort.
- 2026-10-07 — Bug hunt (US-2): TopLevelBackStack gets per-entry leave blockers (DataScreen blocks while a task runs), and tab re-selection/popToRoot respect them, instead of moving Data tasks to a longer-lived scope; known gap: an external deep link can still cancel an off-screen task (SQ-52).
- 2026-10-07 — Bug hunt (US-2): batterystats checkin column offsets are chosen by the device SDK (`parseCheckin(sdkInt)`, background/cached at 8/9 before API 28, 7/10 after), because pre-P dumps put the fields in different columns.
- 2026-10-07 — Bug hunt (US-2): a Shizuku permission the user denied permanently is shown as "blocked" with an Open Shizuku action instead of a dead Allow button, because requestPermission is a no-op once Shizuku stops asking.
- 2026-10-07 — US-1 integration: Shizuku's USER_SERVICE_TRANSACTION_destroy is @RestrictTo its library group, so ShellUserService keeps the literal 16777115 and ShellUserServiceTest asserts it equals ShizukuApiConstants, because referencing the constant from app code fails lint (RestrictedApi) while the test still catches a library change.
- 2026-10-07 — US-1 integration: C06 export requests live in SavedStateHandle and DataScreen forwards every event (Pick included) to the VM before launching SAF (SQ-17 + SQ-31 split), because the launch-time selection must survive process death and the screen previously never told the VM about Pick.
- 2026-10-07 — US-1 integration: C14 shared UIDs keep per-UID power and show a sorted representative + "Shared UID · N apps" line (AppRow optional supportingText); stored session_app_usage rows now use that sorted representative, and AppDetails still shows the representative's identity for whole-UID power (accepted limitation).
- 2026-10-07 — Sidequest scoping: this repo splits strings per screen (strings_<screen>.xml in values/values-es/values-tr) and screenshot refs live in app/src/screenshotTestDefaultDebug/reference/<pkg>/<File>Kt; declare those paths on UI tickets, because declaring values/strings.xml caused scope refusals mid-run.
- 2026-10-07 — Audit fixes (US-1, SQ-2..SQ-26): C04 backend selection falls back (Shizuku only when running AND authorized, else root, then ADB), because availability of an authorized backend should not be blocked by a denied Shizuku; no fallback after a command failure.
- 2026-10-07 — Audit fixes C02/C23: no Room migration — per-screen drain rates are withheld unless session counter coverage is complete (overall session rate stays), and the 7-day ETA seed comes from closed local discharge sessions' counter-covered time, because the session row only stores combined coverage and daily rows mix covered/uncovered time.
- 2026-10-07 — Audit fixes: C03 rejects capacity estimates from incompletely covered sessions (no uniform-drain scaling); C07 corrects export metadata only (format unchanged for import compatibility); C12 blocks Back while a Data task commits instead of moving work to a longer-lived scope.
- 2026-10-07 — Commit/push only docs/AUDIT_RESULTS_2026-10-07.md per user scope; keep unrelated dirty files local. Use recent verified author identity through per-command Git options because this host lacks configured identity; do not alter global/repository configuration.
- 2026-10-07 — Consolidate completed reviews in a new docs file and retain the earlier report; merge duplicate daily coverage/ADB claims and exclude disputed C04 from the confirmed count, because conflicting policy assessments and model-specific gaps must remain visible rather than being silently resolved or counted twice.
- 2026-10-07 — Run this explicit review with inherited reviewer model/effort against clean pinned 76bc831..38205e1, not historical Fable/Astra overrides, because those were separate requests. All eight roles + two fresh falsification passes completed; runtime settings unconfirmed and candidate validation static, so this selective result does not supersede earlier coverage limits or imply device/security assurance.
- 2026-10-07 — Follow updated code-audit v3 stable profiles; stop before passing unsupported Astra model to Agent and provide validated startup launcher, because registered startup model definitions avoid unsupported per-call selection without changing permissions or silently substituting a model.
- 2026-10-07 — User explicitly retried the all-Astra/xhigh command after exit; check discovery against immutable objects before creating another checkout. Stop on repeated profile-loading failure without model overrides or settings changes, because a base-profile fallback would not fulfill the requested configuration.
- 2026-10-07 — Interpret `main` in the user audit command as the base branch (`origin/main`, no local main); require pinned clean source and configured Astra/xhigh profiles. Stop after the permitted discovery retry instead of substituting a static agent/model, because model/effort fidelity is part of the request.
- 2026-10-06 — User requested an independent all-fable review against main; consolidate both reviews by root cause and report model limits explicitly because resumed agents lost verified profiles and fresh fable validation hit a session limit. Do not turn conservative gap policies or latent type invariants into demonstrated critical defects.
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

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | — | — | `/plan-audit` |
| Bug hunt | 2026-10-07 | /bug-hunt over data/persistence, drain/notifications/service, shizuku/apps/util, navigation/UI: 21 verified, 10 fixed (US-2, SQ-37..47 + follow-up SQ-54), 3 follow-ups on board (SQ-52/53/55); unit 689/0 fail, screenshots 205/0 fail, lint 0 errors @ 4672588. Earlier: audit C01–C26 fixed (US-1) @ 6ca5ffd | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Build (bootstrap) | 2026-10-07 | PASS, exit 0; JDK 21, actual environment SDK | `bash .claude/kit/gradle-check.sh :app:assembleDebug` |
| Lint | 2026-10-07 | PASS @ 4672588 (0 errors, 47 warnings) | `./gradlew :app:lintDebug --console=plain -q` |
| Device QA (none — create one) | — | Not run; no AVD or attached device | android-emulator-qa skill |

## Environment notes
- 2026-10-07 host: actual `ANDROID_HOME=/home/dev/android-sdk` (`adb` there), not the documented `$HOME/Android/Sdk`. Pinned-checkout checks used this explicit environment plus JDK 21; no `local.properties` access/change. SDK-path failure was environmental, not a source failure.
- CI's device jobs use the `pixel_2` profile (411×731 dp): device tests must scroll to below-the-fold content. The 16 KB-page job needs 4 GB emulator RAM (at 2 GB the low-memory killer took the app under test). `check_android_device.sh` saves `<phase>-crash-logcat.txt` / `<phase>-logcat-tail.txt` for failed phases in the uploaded reports.
- AGP 9.5.0-alpha07 registers `generate<Variant>ComposePreviewRunfiles` for every Compose variant; realizing all tasks (`./gradlew tasks --all`) fails on `release` (unit tests disabled). Normal builds are fine. List tasks via `tasks.names` in an init script instead.
- Suite task names are variant-first (`testDebugScreenshotTestDefaultTestSuite`), not the doc's `testScreenshotTestDefaultDebugTestSuite`.
- Screenshot plugin truncates `@Preview(name=…)` at "." (`"Font 1.5"` → `5_….png`); keep preview names dot-free.
- Shell is zsh: `${PIPESTATUS[0]}` is empty — use `$pipestatus[1]` or redirect Gradle output to a file and check `$?`.
---
