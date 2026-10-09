# Combined review against main — 2026-10-06

## Scope and outcome

This report consolidates the earlier seven-agent `pr-review-toolkit:review-pr` review and the available results of `code-audit -all fable --base origin/main`. Duplicate observations are merged by root cause. Suggestions and unresolved hypotheses are not counted as confirmed defects.

- Reviewed HEAD: `38205e19ebe1c109e2e8fd52c1df8788d9ed090e` (`feat/overhaul`).
- Base ref: `origin/main`, tip `ff17d6bd2eb5d579cc9c925f4ebfb76f083d7b7b`. There is no local `main` branch.
- Merge base: `76bc831328572c81717b97ffb0e280b10b14b8ad`; review range `76bc831..38205e1`, **not** the base tip's tree versus HEAD.
- Size: 655 files, approximately +41,710/−12,561 lines; 201 reference PNGs excluded from static inspection.
- Includes the Android 16 reliability import, the overhaul, and the post-merge AppDetails fix. PR #1 was merged into the now-deleted intermediate `codex/android16-reliability` branch, not main.
- Out of scope: local `PROGRESS.md` edits and untracked `code-audit-claude/`; no application source changed during this audit.
- **20 consolidated findings:** 17 P2, 3 P3. No demonstrated P0/P1 defect or P0–P2 security exploit. Static inspection is not proof that no other bugs exist.
- **The requested all-fable audit is incomplete:** five specialist passes completed under requested fable configuration. Quality and simplify were interrupted, then returned supplemental results on resume without verified fable profiles. Comments produced no completed second-pass report. A fresh fable validation call failed with HTTP 429 (session limit). No substitute-model validation is represented as fable validation.

### Evidence labels

- **PR**: original seven-agent PR review (data pipeline, privileged/per-app, UI/viewmodels, errors, tests, types, comments).
- **FA**: completed code-audit pass configured for fable: bugs, security, errors, types or tests.
- **SUP**: resumed quality/simplify output; runtime fable identity not verified. Supplemental, not an additional completed fable pass.
- **CO**: coordinator re-read the decisive implementation/callers or ran the listed check. Agreement alone is not proof.

P2 means a concrete normal-priority defect; P3 means a concrete low-priority defect. High confidence below refers to the static mechanism, not a claim that a device reproduction was executed.

## Confirmed findings

Paths beginning `battery/`, `viewmodel/` or `ui/` below are relative to `app/src/main/java/app/batstats/`.

### C01 · P2 — Shizuku destroy transaction is off by one

- **Location:** `battery/shizuku/ShellUserService.kt:16,62–67`; `ShizukuBridge.kt:364–371`.
- **Trigger:** the new 60-second idle unbind invokes Shizuku's user-service destroy transaction.
- **Evidence:** the helper matches `16777114`; `javap -constants` on the installed Shizuku 13.1.5 `shared` library reports `USER_SERVICE_TRANSACTION_destroy = 16777115`. The unmatched code falls through to `super.onTransact`, never reaching `halt(0)`.
- **Impact:** BatStats does not implement Shizuku's requested helper shutdown. A lingering helper after idle is plausible; growing process counts have **not** been measured, and server-side reaping has not been verified.
- **Fix:** use the library constant and bump `SERVICE_VERSION` from 5 to 6. Add an on-device helper-stop regression.
- **Origin/confidence:** pre-existing constant, newly exercised by idle unbind; high. **Sources:** PR privileged, FA bugs, CO bytecode/code.

### C02 · P2 — Daily totals lose charge coverage and bias the ETA seed

- **Location:** `battery/measurement/DailySummaryAggregator.kt:47–57,97–100`; `ObservationEngine.kt:128–147`; `battery/data/db/DailySummary.kt`.
- **Trigger:** some discharge intervals have valid counters and others do not.
- **Evidence:** the engine separates `durationMs` and `chargeCoveredMs`, adding zero charge for an unavailable interval. The daily model retains duration and charge but discards coverage; `typicalDischargeUa` divides charge by *all* discharge time.
- **Impact:** e.g. 100 mAh over one covered hour plus one uncovered hour yields a 50 mA historical seed, not the observed 100 mA rate. Days/Today cannot distinguish partial or unavailable charge from a full total. An entirely counter-less history returns no seed because `uah > 0` is required; it does not produce a zero-rate ETA.
- **Fix:** preserve counter-covered durations independently of real screen time; compute rates over covered time and label partial/unavailable charge. Apply equivalent rules to replay/backfill. A schema change needs its normal migration review; blindly discarding all screen time is not the fix.
- **Origin/confidence:** new; high. **Sources:** PR errors/data, FA bugs, CO.

### C03 · P2 — Capacity estimates combine partial counter charge with the full level span

- **Location:** `battery/data/sampling/SessionReport.kt:73–78`; `battery/measurement/CapacityEstimator.kt:31–53`.
- **Trigger:** a charging/discharging session has enough counter coverage to pass the 75% threshold, but not complete coverage.
- **Evidence:** `deltaUah` is the sum of covered intervals only; the estimator computes `deltaUah * 100 / abs(endLevel - startLevel)` across the whole session. There is no normalization at the call site.
- **Impact:** at constant drain, a 4,000 mAh battery spanning 40 percentage points with 75% coverage yields 3,000 mAh. At 90% coverage the 3,600 mAh result can be marked HIGH. Missing charge is interpreted as reduced battery capacity.
- **Fix:** estimate from counter-covered level spans or reject incomplete spans. Scaling by time coverage assumes uniform drain and must not be presented as an exact correction.
- **Origin/confidence:** new; high for the arithmetic. **Sources:** PR data, CO.

### C04 · P2 — Unauthorized Shizuku prevents root fallback

- **Location:** `battery/util/ShellRunner.kt:98–105`; `battery/apps/AppStatsRepository.kt:110–119`.
- **Trigger:** Shizuku is running, BatStats lacks its permission, and root is available to BatStats.
- **Evidence:** `if (shizuku.ping()) return ... SHIZUKU else NONE` exits before root/ADB probes. `NONE` becomes `NoAccess`.
- **Impact:** Apps and session snapshots fail despite an authorized backend. Main tried root first.
- **Fix:** prefer Shizuku only when available **and authorized**; otherwise try other backends. Keep backend selection separate from any deliberate no-fallback-after-command-failure policy.
- **Origin/confidence:** new in the reliability import; high. **Sources:** PR privileged, FA bugs, SUP quality, CO.

### C05 · P2 — Explicit navigation links can land on a tab's stale detail stack

- **Location:** `ui/NavGraph.kt:58`; `ui/navigation/TopLevelBackStack.kt:42–55,103–120`.
- **Trigger:** Apps retains AppDetails, user switches to Now and taps See all; or Now retains Health and a monitoring notification/tile sends NOW from another tab.
- **Evidence:** `select()` preserves the target stack when switching tabs; these links call it rather than `openRoot()`. HEALTH/STATUS links also push onto preserved detail stacks.
- **Impact:** links do not open their named list/root, and detail deep links can accumulate entries.
- **Fix:** explicit root links should call `openRoot`; detail deep links should establish the intended root before navigating. Keep normal tab-selection stack retention.
- **Origin/confidence:** new; high. **Sources:** PR UI, CO.

### C06 · P2 — Export options reset if the process dies while the picker is open

- **Location:** `viewmodel/DataViewModel.kt:87–96,163–187`; `ui/screens/DataScreen.kt:79–87`.
- **Trigger:** choose a limited range/exclude samples, open the SAF picker, and lose the app process before the picker returns.
- **Evidence:** the launcher can deliver its restored result to a new ViewModel; range and include switches exist only in its in-memory state. `onFileChosen` reads those new default values.
- **Impact:** an explicitly narrowed export can silently include all history/samples.
- **Fix:** persist the export request/options through SavedStateHandle and use the request that launched the picker. Test process recreation, not just rotation or "Don't keep activities" (which does not guarantee process death).
- **Origin/confidence:** new; high static confidence, device reproduction not run. **Sources:** PR UI, CO.

### C07 · P2 — Export metadata mislabels raw current as normalized microamps

- **Location:** `battery/data/ExportImport.kt:64`; `battery/data/sampling/SamplingController.kt:302–328`.
- **Trigger:** export from a device reporting raw mA or inverted current sign.
- **Evidence:** stored `currentNowUa` is raw, but metadata says `µA, positive into battery`. The export has no per-row calibration establishing that claim.
- **Impact:** downstream analysis interprets the wrong magnitude or charge/discharge direction.
- **Fix:** accurately describe raw values, or export separately identified calibrated values with the required calibration provenance; consider format compatibility.
- **Origin/confidence:** new export contract; high. **Sources:** PR tests/types, CO. FA types confirms production consumers calibrate correctly.

### C08 · P2 — All malformed app-power records can be saved as successful empty data

- **Location:** `battery/util/BatteryStatsParser.kt:56–57,299–305,399`; `battery/apps/AppStatsRepository.kt:127–132`.
- **Trigger:** `bt` is valid but all UID `pwi` power values are malformed/rejected.
- **Evidence:** `hasValidWindow` checks only window fields; rejection metadata is not consumed. The repository returns Ready and session collection can persist an empty READY breakdown.
- **Impact:** a format failure is indistinguishable from genuinely empty usage and becomes permanent session data.
- **Fix:** track acceptance/rejection of the relevant UID power records and return a format/partial-data outcome for malformed app data. **Do not reject every zero-app dump:** a fresh/reset window can legitimately be empty. A global `rejectedRecords > 0` alone is insufficient because unrelated records can be rejected.
- **Origin/confidence:** new readiness path; high. **Sources:** PR errors, CO.

### C09 · P2 — Live AppDetails still attributes a reused UID to the old package

- **Location:** `viewmodel/AppDetailsViewModel.kt:208,252–254`.
- **Trigger:** open AppDetails from an older session after the original app was uninstalled and its UID reassigned.
- **Evidence:** the route preserves the old package, but live `details(snapshot, uid)` selects only by UID. The history fix's package rule does not apply here.
- **Impact:** a new app's current drain/times appear under the old app's name.
- **Fix:** validate package membership for application UIDs before attaching live usage. Define the shared/unknown-package policy explicitly.
- **Origin/confidence:** new detail flow; high. **Sources:** PR UI, CO.

### C10 · P2 — Secondary-user system UIDs are classified as application UIDs in history matching

- **Location:** `viewmodel/AppDetailsViewModel.kt:53–58`; `battery/util/BatteryStatsParser.kt:11`; `battery/apps/AppUsageSnapshot.kt:24`.
- **Trigger:** a system appId such as 1000 belongs to a secondary user (`uid=1001000`), and the stored/current first package differs for that shared UID.
- **Evidence:** `isSameApp` compares raw `uid < 10000`; the parser correctly compares `appId(uid)`. Snapshot/Apps identities use the first package when mappings exist, so this is not limited to fallback display labels.
- **Impact:** valid history for a shared system UID disappears based on package ordering.
- **Fix:** reuse `isSystemUid(uid)` and add a secondary-user/shared-UID test. The blank-package clause mostly covers imports, not local display-name fallback rows.
- **Origin/confidence:** post-merge fix 43035c7; high static confidence. **Sources:** PR UI/tests, FA tests question, CO resolution.

### C11 · P2 — AppDetails history read failure looks like successful empty history

- **Location:** `viewmodel/AppDetailsViewModel.kt:235–242`.
- **Trigger:** the Room-backed history operation throws.
- **Evidence:** the exception becomes `emptyList()` with neither logging nor a failed UI state.
- **Impact:** users are told implicitly that there are no sessions, rather than that the read failed.
- **Fix:** expose/log the failure with retry; retain cancellation propagation.
- **Origin/confidence:** new; high. **Sources:** PR errors, CO.

### C12 · P2 — Leaving Data cancels an accepted operation without an outcome

- **Location:** `viewmodel/DataViewModel.kt:211–222`; `ui/screens/DataScreen.kt:133–137`.
- **Trigger:** start a sufficiently long import/export/settings task and pop the Data nav entry.
- **Evidence:** the task runs in `viewModelScope`; Back remains enabled and clearing the entry's ViewModel cancels the scope. Cancellation is rethrown before publishing an outcome.
- **Impact:** imports can roll back without a completion/cancellation message. File writes and settings tasks have their own side-effect boundaries; they should not be claimed universally atomic or universally cancellable.
- **Fix:** establish an explicit lifecycle/cancellation policy: prevent accidental navigation while committing, or use an owned long-lived operation with observable status. Do not indiscriminately wrap everything in NonCancellable.
- **Origin/confidence:** new; high static confidence. **Sources:** PR UI, CO.

### C13 · P2 — An unobserved one-shot failure contaminates the observation timeline

- **Location:** `battery/data/sampling/SamplingController.kt:250–266`.
- **Trigger:** a realtime-only/readOnce capture (`observe=false`) fails while monitoring's observed timeline has not missed a capture.
- **Evidence:** both catch and missing-intent paths set `overflow=true` without checking `observe`; the next observed point becomes GAP.
- **Impact:** a one-off widget/tile read can manufacture an observation gap and segment the active session.
- **Fix:** mark observed continuity loss only for observed captures; retain the one-shot error diagnostic.
- **Origin/confidence:** new; high. **Sources:** PR data, CO.

### C14 · P2 — Whole shared-UID power is labeled as one member app

- **Location:** `battery/apps/AppUsageSnapshot.kt:21–24`; `viewmodel/AppsViewModel.kt:383–388`.
- **Trigger:** the parser maps a UID to multiple packages.
- **Evidence:** the row uses `packages.firstOrNull()` and resolves that package's display label; the parser's shared-UID label is not retained in the UI.
- **Impact:** all UID power appears attributed to one arbitrary member, with no visible shared-attribution limitation. Ordering changes can change the apparent app.
- **Fix:** display an explicit shared-UID identity/member count; keep UID-level accounting rather than fabricating per-package power.
- **Origin/confidence:** new UI/snapshot attribution; high. **Sources:** PR privileged/types, CO.

### C15 · P2 — Widgets bypass localized percentage and duration formatting

- **Location:** `battery/widget/WidgetUpdater.kt:129–134`; `battery/util/TimeEstimator.kt:18–21`.
- **Trigger:** Turkish/Spanish locale and widget display.
- **Evidence:** percentage is `"$it%"`; duration is hard-coded `h`/`m`. The notification uses localized templates.
- **Impact:** e.g. Turkish receives `42%`/`2h 5m` rather than the app's `%42`/localized units.
- **Fix:** reuse percentage, number and duration resources/formatters. Verify all three widget providers in supported locales.
- **Origin/confidence:** introduced/retained in the new widget path; high. **Sources:** PR privileged, CO.

### C16 · P3 — Output failures are reported as rejected input files

- **Location:** `viewmodel/DataViewModel.kt:278–287,323–325`; `battery/data/ExportImport.kt:86,93,99,102`.
- **Trigger:** output document/folder creation fails, or settings export returns Error.
- **Evidence:** these paths throw IllegalStateException; `failure()` maps it to REJECTED even for export/save tasks.
- **Impact:** users see a file-validation explanation for a destination/write failure.
- **Fix:** use appropriate I/O exceptions or map write-task failures to UNWRITABLE while preserving genuine validation errors.
- **Origin/confidence:** new; high. **Sources:** FA errors, CO.

### C17 · P3 — Cached Shizuku access failures temporarily get the wrong recovery advice

- **Location:** `battery/util/ShellRunner.kt:56–58`; `battery/apps/AppStatsRepository.kt:113–122`; `viewmodel/AppsViewModel.kt:91–96`.
- **Trigger:** Shizuku stops or permission is revoked during the 10-second cached mode.
- **Evidence:** NOT_RUNNING/NO_PERMISSION become Failure(SHIZUKU), then a generic helper-failed banner instead of NoAccess.
- **Impact:** temporarily misleading recovery advice; bridge state and a forced refresh generally correct it. This is not a persistent no-access bug.
- **Fix:** preserve typed failure reasons, invalidate stale mode and classify authorization/availability failure at production time.
- **Origin/confidence:** new; high. **Sources:** PR privileged/types, FA errors/types, CO.

### C18 · P2 — Current measurement/access claims contradict the implementation

- **Locations:** `docs/MEASUREMENTS.md:15–16,24,46`; `docs/PLATFORM_NOTES.md:19`; `README.md:11,21–29`; `CHANGELOG.md:5`; `fastlane/metadata/android/en-US/full_description.txt:1`.
- **Evidence/impact:**
  1. ETA's 45 minutes is an exponential **time constant**, not a half-life (half-life about 31 minutes); seed/live crossover and gates need accurate wording.
  2. Learned root sysfs `charge_full` capacity is advertised, but `CapacityEstimator.fromSysfs` has no production caller. Root reads design capacity, which is not learned full capacity.
  3. Only detected calibration is excluded from backup/export; user overrides are DataStore settings and are included.
  4. ADB DUMP grants cannot obtain per-app batterystats on API 36; Shizuku/root are required there.
- **Fix:** correct current-facing documentation and store descriptions; do not silently add new behavior merely to match stale prose.
- **Origin/confidence:** new documentation/retained import claims; high. **Sources:** PR comments; implementation corroboration in FA types/bugs and CO.

### C19 · P2 — New-screen scaffolding targets deleted navigation APIs

- **Location:** `.claude/skills/new-screen/SKILL.md:19,25`; `template.md:4,106–116`.
- **Evidence:** instructions use `Screen`, `backStack.add` and `HistoryViewModel.Ui`; current navigation uses `Routes : NavKey`, TopLevelBackStack and `HistoryUiState`. The template also contains raw screen dp literals, and the skill instructs a bare screenshot update contrary to the rebaseline rule.
- **Impact:** copying the supplied route scaffold does not compile and violates current theme/verification conventions.
- **Fix:** update the scaffold around current route/state/token APIs and the approved rebaseline workflow.
- **Origin/confidence:** new skill, still wrong after post-merge edits; high. **Sources:** PR comments, CO.

### C20 · P3 — Historical records are presented as current guidance

- **Locations:** `AUDIT_REPORT.md:3,80,150`; `docs/BASELINE_TO_CURRENT.md:30–50`; `docs/VALIDATION.md:7,41`; `docs/PR_DESCRIPTION.md`; `README.md:7,29,56`; `CHANGELOG.md:31`; `CLAUDE.md:55–61`; selected Kotlin phase comments.
- **Evidence/impact:** old DB4, deleted Advanced/Kernel screens, old cadence, draft-PR/unverified caveats and historical host facts conflict with this branch. CLAUDE.md also points to a missing global file and tools unavailable in this environment. These were current review leads, not proof that historical runs never occurred.
- **Fix:** label/archive historical checkpoints, add an accurate current checkpoint, remove obsolete PR-body guidance, and distinguish required project rules from machine-specific optional tooling. Update StateEventSequencer's owner comment (sampler thread), stale P5 comments and capacity KDoc. Keep historical decision provenance rather than erasing it.
- **Origin/confidence:** imported/additional docs; high. **Sources:** PR comments, CO environment checks. Second fable comments pass incomplete.

## Optional hardening and simplification (not additional confirmed defects)

1. **Signing-secret scope (FA security):** `.github/workflows/build-apk.yml:37–40` exposes PREVIEW signing env to all job steps. Scope it to configuration/build steps. No leak or exploitable action is demonstrated; actions are pinned, checkout credentials are disabled and PR runs receive no secrets.
2. **Typed failure reasons (PR types, FA types):** replace equality checks against English sentences with structured mode/kind/reason captured alongside the result. Current cross-user refusal handling is correct; C17 covers the observed stale-mode case.
3. **Raw/calibrated current types (PR types, FA types):** separate raw values from calibrated values or pass explicit calibrated alert input. Production display/alert/calibration wiring is currently correct; C07 is the distinct export-contract defect. Rename positive drain magnitudes to `drainMa` if useful; do not claim a current sign-conversion bug.
4. **Session-close invariant:** `ChargeSession.activeKey` is a constructor default, not recomputed by copy. Current close sites explicitly null it. A shared close helper prevents a future omission.
5. **Tab-route invariants:** `select/openRoot` accept any Routes, although all current callers use tabs. Restrict their types if worthwhile; no current caller crash was found.
6. **ETA/UI-state invariants:** unify exclusive ETA shown/pending states and remove near-identical drain-state copies only if it improves clarity; no invalid current producer was found.
7. **Diagnostics compatibility (FA types):** `DiagnosticLog.kt:26` uses valueOf; an unknown future code makes DiagnosticStore discard the saved log and call it a storage failure. Tolerantly skip unknown codes if downgrade compatibility is wanted.
8. **App identity derivation:** derive a primary/shared/unknown identity once rather than duplicating first-package selection. Avoid changing exported package sentinels without compatibility review.
9. **Graceful Android read failures:** log or distinguish failed launchable-package lookup (`AppsViewModel.kt:381`) and failed cycle-count read (`HealthViewModel.kt:82`) from normal unavailable data.
10. **Delivery/status diagnostics:** a disabled alert channel is intentionally honored, but showing an alerts-blocked warning would prevent confusion. Keep detailed timeout/overflow/root/helper failure information in local diagnostics rather than only generic codes. Failure logging improvements should avoid leaking sensitive data.
11. **Migration outcome:** `BatteryApp.kt:52` ignores the returned migration result. Retention safely refuses purge when migration fails, but an explicit migration diagnostic/recovery path is preferable to repeated generic maintenance logs. A library-thrown crash loop is not verified (see hypotheses).
12. **Display resource cost:** build notification/widget strings after eligibility/gating where possible. The current pre-gate formatting is a small cost; no benchmark establishes a material battery regression.
13. **Helper hardening:** bind run/destroy authorization to expected callers and avoid thread-per-busy-request churn. No reachable foreign-binder attack was established; exact command allowlisting already prevents arbitrary command execution.
14. **Dead resources:** lint identifies three unused widget layouts, filepaths.xml, locales_config.xml and several icons/art assets. The three widget layouts are confirmed unused in runtime/provider references; providers use widget_common. Inspect every other resource's references before deletion. locale_config can be wired rather than deleted if per-app language selection is wanted.
15. **Unused parser APIs/fields:** isUserApp, parseDeviceIdle, parsePowerManager, runOrNull and legacy parsed sections have no production consumers identified. Public/parser API removal is a separate compatibility decision, not automatically behavior-preserving.
16. **CSV formula neutralization:** import rejects dangerous text; current export text is app-generated and no formula-injection vector was found. Symmetric export hardening is optional before adding arbitrary free text.
17. **Delegation seams/DI:** prefer one Koin ownership path over BatteryGraph, but thin repository adapters are legitimate test seams—not inherently needless indirection. Share the repeated recording-generation predicate.
18. **Localization lint:** add Spanish `many` categories where needed; make session-history and matching-count strings plurals. Android's locale-config resource is unwired; that fact predates this branch and does not by itself prove how every platform version discovers available languages.
19. **Supplemental simplify output (runtime not fable-verified):** scan DumpOutput.failure once while preserving error precedence; reuse EtaHold.remainingMs countdown; share the two cancellation-safe AppInfo lookups; reuse dayAwareTime in Status; derive each Apps metric once while preserving total inclusion/order, null semantics and tie-breaking.
20. **Partial JSON destination:** exportJson cleans its temporary cache file, not a partially written SAF destination. Decide safe cleanup/reporting semantics; deleting an existing user-selected document after overwrite failure is not automatically safe.

## Test follow-ups, merged by behavior

| Priority | Regression to pin | Proposed coverage | Sources |
|---|---|---|---|
| Highest | Correct helper stop transaction and idle process exit | RequiresShizuku device test across repeated idle binds; verify server reaping as well | PR privileged, FA bugs/security |
| Highest | Covered/uncovered daily intervals; incomplete level spans | DailySummaryAggregatorTest, replay and SessionReport/CapacityEstimator integration cases | PR errors/data, FA bugs |
| High | Repository unplug/reset/gap/retry boundaries | Extend RepositoryRecoveryTest beyond plug-in; assert window start and PowerTransition/baseline | PR tests |
| High | Capability probing and actual outcome translation | Running-but-denied Shizuku with authorized root; extract/test shell result mapping; API36 ADB refusal integration | PR tests/privileged, FA quality/errors |
| High | Real parser field layouts | Captured, sanitized API34/35/36 golden dumps, multi-user/shared UID; malformed-pwi versus valid-empty cases | PR tests, FA tests/security |
| High | AppDetails repository composition and live UID reuse | In-memory Room repository test, assert passed package in VM fake, secondary-user UID case | PR tests/UI, FA tests |
| Medium | Export request survives process recreation and Back policy | DataViewModel state restoration and device picker/navigation tests | PR UI |
| Medium | Sample boundStorage SQL, threshold/ ties | Extend check_history_queries.py; explicitly decide retention of active-session samples | PR tests, FA bugs |
| Medium | Alert wiring | Pure alertInputs mapping, calibrated sign, all threshold fields, latch persistence | PR tests |
| Medium | One-shot failure/queue overflow/stale screen event | Observation continuity tests with observe=false failure and dropped observed capture | PR tests/data |
| Medium | Failure-state presentation | Export UNWRITABLE, history-query failure, settings migration non-success, boot failure recovery | PR errors, FA errors |
| Low | Defensive import/allowlist edge cases | Unsupported helper command, malformed frame magic/length, unknown settings keys | FA security |

Weak-test notes: the 6.5-second stop assertion in MonitoringLifecycleDeviceTest can miss leaked polling because persistence is spaced at 30 seconds; assert sampling state/poll generation directly. RepositoryRecoveryTest uses wall-time delays and count ranges; fixed-count hooks would reduce timing dependence. HistoryDetailsDeviceTest can cross local midnight unless its clock is pinned. Remove the vacuous non-null hero assertion and correct its misleading Reset test name. CommandOutputTest's 1.5-second wall-clock upper bound can flake under runner contention. Existing green tests do not establish these gaps are fixed.

## Unresolved hypotheses and policy decisions

These are preserved from both reviews, **not counted as confirmed defects**:

- **Simultaneous state changes:** StateEventSequencer cannot confirm two changed dimensions even when both matching events arrive within 2 seconds. ObservationEngine's one-boundary `missingTransition` check also rejects them, so changing only the sequencer is insufficient. This is an explicit conservative/tested policy, not automatically corrupt accounting. Decide whether to support multi-event confirmation; otherwise communicate the resulting excluded coverage. Device reproduction on an OEM that wakes on plug-in remains unrun.
- **"Since unplug" segmentation:** BatteryRepository closes/reopens on any observation gap, including clock changes or write recovery, without a physical unplug. Keeping sessions across uncertain coverage versus relabeling the window is a product/measurement-policy decision, not permission to bridge unobserved intervals.
- **Backfill retry overwrites live rows:** an unset flag after a failed backfill permits a later replay to upsert live day rows. Demonstrate a numeric mismatch between replay and live aggregation before treating it as lost data; test retry idempotence and equivalent coverage.
- **Retention and active sessions:** samples can be purged before cutoff while the active session row is retained. This truncates charts in long sessions but may be intentional retention policy; establish requirements before exempting active-session samples from limits.
- **Unbounded settings migration wait:** retention suspends on awaitMigrated. A hung library call could stall the writer and Clear, but no stalled implementation was demonstrated. Investigate timeout/failure policy without risking purge of unmigrated settings.
- **Migration/DataStore exception crash loops:** the app/service scopes lack a user-visible recovery path for some library failures. The library's actual exception/corruption-handler behavior must be established before calling this a crash loop.
- **Permanent Shizuku denial:** isPermanentlyDenied is unused and the earlier agent questioned rationale polarity. The precise library contract and reachable "don't ask again" button behavior need validation; the inverse-condition allegation is not promoted without that evidence.
- **Boot/update lifecycle:** unexpected boot failures only log; missing MY_PACKAGE_REPLACED may leave monitoring stopped after an update. Verify package-replacement/service restart behavior on-device before treating the absent receiver as proof of a regression.
- **Intent replay after process death:** BatteryMainActivity reads destination on every onCreate and removes it from the local Intent. Verify actual system restoration/replay after a notification-launched task is killed before changing deep-link semantics.
- **Background root prompts:** forced snapshot probes run su id and may trigger root-manager prompts; severity depends on root-manager policy and whether root probing should be opt-in.
- **Settings import unknown keys and Shizuku binder scoping:** no exploit was found; test library filtering and document whether the helper binder can ever leave the requesting app.

## Checks actually run

| Check | Result on reviewed application source |
|---|---|
| `:app:testDebugUnitTest` | PASS: 542 tests, 0 failures/errors/skipped |
| `:app:lintDebug` | PASS task: 0 errors, 46 warnings (not a clean-warning claim) |
| `scripts/check_migrations.py` | PASS all 1/2/3→5 chains and v4→5 DDL/data preservation |
| `scripts/check_history_queries.py` | PASS actual DAO/chart/history/snapshot/retention SQL checks |
| `scripts/check_resources.py` | PASS XML and 628 matching keys in es/tr |
| Script unittest discovery | PASS 14 tests |
| Shizuku 13.1.5 constant inspection | `javap`: destroy transaction is 16777115 |
| Dry-run merge into origin/main | One content conflict: AGP version line in gradle/libs.versions.toml |
| New device reproductions / connected tests | NOT RUN |
| Screenshot suite / preview build / release checks | NOT RUN in this audit; prior historical passes are not a fresh result |
| Fresh fable validation | BLOCKED: HTTP429, model sent claude-fable-5-1; session limit |

The local app source matched HEAD for tests; dirty PROGRESS.md and the untracked installer are not application changes. No tests were edited to manufacture a reproduction, no snapshots were updated, no dependencies were installed for verification, and no fixes were applied.

## Specialist coverage and model limits

All code-audit roles were configured for **fable / inherited effort**. Configured settings are not proof of runtime settings. The first four used existing static specialist types with a per-call fable override after per-run definitions initially failed to load; the tests pass used the loaded per-run type. This differed from the skill's preferred dynamic-profile dispatch. Later `/effort` changes do not retroactively prove the initial reviewers' effective effort.

| Role | Requested model/effort | Status | Areas actually reported | Runtime/coverage limitation |
|---|---|---|---|---|
| bugs | fable / inherit | Completed original pass | Measurement, sampling, repository, DB, services, privileged access, apps, widgets, viewmodels, navigation | Runtime model metadata not exposed; no device tests; most UI/resources skipped |
| security | fable / inherit | Completed original pass | Helper trust boundary, import limits, manifest, backup, intents, build/CI | No exploit demonstrated; library/server scoping not verified |
| errors | fable / inherit | Completed original pass | Shell fallbacks, writer, migration/retention, import/export, boot/service, viewmodels | Several library-dependent claims remain hypotheses |
| types | fable / inherit | Completed original pass | Entities/schema, calibration/current, state/outcome types, import/settings, nav keys | No confirmed type-layer defect; Data VM/replay/widgets mostly skipped |
| tests | fable / inherit | Completed original pass | Pure core tests, repository adapters, migrations, parser, device-test inspection | Did not run tests itself; many UI/screenshot tests only searched |
| quality | fable / inherit | Fable pass interrupted; supplemental resumed report completed | DI, lifecycle, resource bounds, conventions, build/widget diffs | Resume listed as general-purpose; fable runtime not verified, not counted as completed fable |
| comments | fable / inherit | Interrupted, no final second-pass report | Partial docs inventory only | Earlier PR comments analysis supplies this report's doc findings; not a completed fable pass |
| simplify | fable / inherit | Fable pass interrupted; supplemental resumed report completed | Viewmodels, formatting, parser/helpers, selected shared components | Resume listed as general-purpose; fable runtime not verified, not counted as completed fable |
| independent validation | fable / inherit | Blocked | No completed review | API explicitly identified claude-fable-5-1 and HTTP429; no substitute used |

Earlier PR review: all seven selected agents returned reports. The separate plugin compose-reviewer and module-graph-auditor were unavailable; neither ran. This report is a consolidated static audit, not an exhaustive full-file/full-platform certification.

## Recommended order

1. Correct the destroy transaction (and service version), coverage-sensitive daily/capacity math, backend probing, and raw-current export contract.
2. Address navigation and data-task lifecycle/restoration; test live/history UID attribution and malformed-vs-empty dumps.
3. Update scaffolding and current-facing docs; preserve historical audit provenance under clear labels.
4. Add the focused regressions above, then rerun unit/lint, screenshots for actual UI changes, and the project device-check workflow. Resolve only established product-policy questions; do not silently change app behavior to satisfy an audit suggestion.
5. Finish the missing fable passes/independent validation when that model is available. Keep the audit's incomplete-model coverage visible until then.

Merge note: main changed AGP 9.4.0→9.4.1 while this branch uses approved 9.5.0-alpha07 for screenshot suites. The dry-run conflict is confined to the version catalog line; do not downgrade the screenshot-compatible branch as an automatic conflict fix. No merge, commit, push or PR comment was performed.
