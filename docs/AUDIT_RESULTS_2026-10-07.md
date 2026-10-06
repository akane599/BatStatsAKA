# Combined audit and review results — 2026-10-07

## Scope, sources and outcome

This file combines the latest completed reviews of `feat/overhaul` against `origin/main`:

1. The seven-agent PR review and available Fable audit results consolidated in [REVIEW_MAIN_2026-10-06.md](REVIEW_MAIN_2026-10-06.md).
2. The 2026-10-07 `/code-audit review --base origin/main`: all eight inherited-setting specialists and two fresh cross-specialist validation passes completed.

The earlier report remains unchanged as provenance. This consolidation merges duplicates by root cause, preserves disagreements and does not turn suggestions or policy questions into defects.

| Scope | Value |
|---|---|
| Reviewed HEAD | `38205e19ebe1c109e2e8fd52c1df8788d9ed090e` |
| Merge base | `76bc831328572c81717b97ffb0e280b10b14b8ad` |
| Resolved origin/main tip at setup | `ff17d6bd2eb5d579cc9c925f4ebfb76f083d7b7b` |
| Actual comparison | Merge-base..HEAD, `76bc831..38205e1`, not base-tip tree versus HEAD |
| Change size | 655 files, approximately +41,710/−12,561 lines |
| Latest source strategy | Clean detached checkout of exact HEAD; source stability verified before cleanup |
| Excluded working-tree changes | `PROGRESS.md`, untracked `code-audit-claude/` and the earlier report |

**Combined disposition: 25 retained confirmed findings — 20 P2 and 5 P3 — plus one disputed backend-policy item.** “Confirmed” describes the static mechanism reported by the source reviews, not a claim of executable reproduction. Earlier-only findings were not all independently revalidated by the latest selective run. There is no demonstrated P0/P1 defect or introduced exploitable security vulnerability; neither statement proves their absence.

### Deduplication and assessment changes

- Latest daily-summary ETA finding merges into **C02**.
- Latest Android 16 ADB documentation finding merges into **C18**; that subclaim is P3 in the latest audit. C18 retains the earlier umbrella P2 assessment for the broader current-facing measurement/access claims.
- Four new P2 findings are **C21–C24**; two new P3 findings are **C25–C26**.
- **C04** was a confirmed P2 in the earlier report. The latest audit treated fallback behavior as an unresolved product-policy question. Its static early-return mechanism remains established, but it is excluded from this report's confirmed count pending a fallback-contract decision.
- C26 concerns a specific false outlier guarantee, distinct from C20's general stale-checkpoint guidance. It is a documentation defect, not a demonstrated defect in the intended weighted-median algorithm.

### Evidence conventions

- **Earlier:** PR/Fable/supplemental/coordinator evidence described in the 2026-10-06 report. Consult that report for individual source labels and original confidence statements.
- **Latest:** 2026-10-07 specialist inspection plus coordinator verification of producers/callers.
- **Fresh validation:** a new different-specialist pass checked the specific candidate's mechanism and counterarguments. Agreement alone is not proof; no targeted device/Room reproduction was executed.
- P2 = normal priority; P3 = low priority. Paths beginning `battery/`, `viewmodel/` or `ui/` below are relative to `app/src/main/java/app/batstats/`. Line references describe the pinned reviewed revision, not future edits.

## Confirmed P2 findings

### C01 — Shizuku destroy transaction is off by one

- **Location:** `battery/shizuku/ShellUserService.kt:16,62–67`; `battery/shizuku/ShizukuBridge.kt:364–371`.
- **Trigger/evidence:** idle unbind exercises the helper destroy transaction. The implementation matches `16777114`; bytecode inspection of Shizuku 13.1.5 reports `USER_SERVICE_TRANSACTION_destroy = 16777115`. The request falls through instead of invoking `halt(0)`.
- **Impact:** requested helper shutdown is not implemented. A lingering helper is plausible; process growth/server-side reaping was not measured.
- **Fix:** use the library constant, bump `SERVICE_VERSION` and add an on-device stop regression.
- **Provenance:** Earlier; pre-existing constant newly exercised by idle unbind.

### C02 — Daily totals discard counter coverage and bias the ETA seed

- **Location:** `battery/measurement/DailySummaryAggregator.kt:47–57,97–100`; `battery/data/db/DailySummary.kt`.
- **Trigger/evidence:** valid-counter and unavailable/reset-counter discharge intervals coexist. Aggregation retains all observed screen time but only valid-counter charge; `typicalDischargeUa` divides that charge by all discharge time.
- **Impact:** one covered hour consuming 100,000 µAh plus one uncovered hour produces a 50,000 µA seed rather than 100,000 µA. A fresh 2,000,000 µAh observation consequently starts at 40 hours rather than 20. Daily charge totals also lack partial/unavailable coverage evidence. Entirely counter-less history does not produce a zero-rate ETA because the seed requires positive charge.
- **Fix:** preserve covered discharge durations independently of observed screen time; use them for rate and one-hour eligibility, label partial totals and apply equivalent replay/backfill rules. Handle legacy rows conservatively; include migration compatibility.
- **Provenance:** Earlier + Latest + fresh tests-specialist validation.

### C03 — Capacity estimation combines partial charge with the full level span

- **Location:** `battery/data/sampling/SessionReport.kt:73–78`; `battery/measurement/CapacityEstimator.kt:31–53`.
- **Trigger/evidence:** a session passes the 75% counter-coverage threshold, but its charge sum covers less than the full level change. The estimator uses `deltaUah * 100 / abs(endLevel - startLevel)` across the whole session.
- **Impact:** at constant drain, 75% coverage of a 4,000 mAh battery can produce 3,000 mAh; 90% coverage can produce 3,600 mAh marked HIGH.
- **Fix:** use matching covered level spans or reject incomplete spans. Time-based scaling assumes uniform drain and is not an exact correction.
- **Provenance:** Earlier; high static confidence in the arithmetic.

### C05 — Explicit links open a tab's stale detail stack

- **Location:** `ui/NavGraph.kt:58`; `ui/navigation/TopLevelBackStack.kt:42–55,103–120`.
- **Trigger/evidence:** Apps retains AppDetails, then See all selects Apps; or Now retains Health when a notification/tile requests NOW. `select()` preserves the target stack; these links do not establish the requested root.
- **Impact:** named list/root links land on stale details; detail links can accumulate entries.
- **Fix:** use `openRoot` for explicit root links and establish the intended root before detail navigation. Preserve ordinary tab-selection stack retention.
- **Provenance:** Earlier.

### C06 — Export options reset across process death during the picker

- **Location:** `viewmodel/DataViewModel.kt:87–96,163–187`; `ui/screens/DataScreen.kt:79–87`.
- **Trigger/evidence:** the app process dies after a narrowed export launches SAF. A restored launcher result reaches a new ViewModel whose in-memory options have defaulted.
- **Impact:** an explicitly limited export can silently include all history/samples.
- **Fix:** save the launched export request through SavedStateHandle and consume that request on picker completion. Test actual process recreation, not rotation alone.
- **Provenance:** Earlier; device reproduction not run.

### C07 — Export metadata labels raw current as normalized microamps

- **Location:** `battery/data/ExportImport.kt:64`; `battery/data/sampling/SamplingController.kt:302–328`.
- **Trigger/evidence:** a device reports raw mA or inverted sign. Stored `currentNowUa` is raw, while export metadata promises µA, positive into the battery, without per-row calibration provenance.
- **Impact:** external analysis uses the wrong magnitude/direction. Production display/alert calibration was not found defective.
- **Fix:** describe raw values accurately or export separately identified calibrated values with provenance; consider format compatibility.
- **Provenance:** Earlier.

### C08 — Malformed app-power rows become successful empty data

- **Location:** `battery/util/BatteryStatsParser.kt:56–57,299–305,399`; `battery/apps/AppStatsRepository.kt:127–132`.
- **Trigger/evidence:** window fields are valid but every relevant UID `pwi` value is rejected. Readiness checks window validity without consuming relevant rejection evidence.
- **Impact:** a parse failure can become a permanent empty READY session breakdown.
- **Fix:** distinguish malformed/partial relevant app-power data from valid empty dumps. Do not reject every zero-app window or every unrelated rejected record.
- **Provenance:** Earlier.

### C09 — Live AppDetails attributes a reused UID to the old package

- **Location:** `viewmodel/AppDetailsViewModel.kt:208,252–254`.
- **Trigger/evidence:** an old session route names an uninstalled package whose UID now belongs to another app. Live selection matches UID only, unlike the history package rule.
- **Impact:** the replacement app's live drain/times appear under the old app's identity.
- **Fix:** validate package membership for application UIDs; explicitly define shared/unknown-package handling.
- **Provenance:** Earlier.

### C10 — Secondary-user system UIDs fail history identity matching

- **Location:** `viewmodel/AppDetailsViewModel.kt:53–58`; `battery/util/BatteryStatsParser.kt:11`.
- **Trigger/evidence:** system appId 1000 belongs to another user, e.g. UID 1001000, and first-package ordering changes. `isSameApp` checks raw `uid < 10000` instead of appId.
- **Impact:** valid shared-system-UID history disappears based on package ordering.
- **Fix:** reuse `isSystemUid(uid)` and test secondary-user/shared-UID cases.
- **Provenance:** Earlier; introduced by post-merge history fix 43035c7.

### C11 — AppDetails history failure looks like empty history

- **Location:** `viewmodel/AppDetailsViewModel.kt:235–242`.
- **Trigger/evidence:** the Room-backed history operation throws; the exception becomes an empty list without logging or failed UI state.
- **Impact:** users cannot distinguish no history from a failed read.
- **Fix:** expose/log failure with retry, retaining cancellation propagation.
- **Provenance:** Earlier.

### C12 — Leaving Data cancels an accepted task without an outcome

- **Location:** `viewmodel/DataViewModel.kt:211–222`; `ui/screens/DataScreen.kt:133–137`.
- **Trigger/evidence:** pop Data during a long task. Enabled Back clears the entry's ViewModel and cancels its scope before publishing an outcome.
- **Impact:** an import can roll back without completion/cancellation feedback. File/settings operations have distinct side-effect boundaries; they are not universally atomic.
- **Fix:** define lifecycle/cancellation ownership, prevent accidental navigation during commit or use an observable longer-lived task. Do not wrap all work indiscriminately in NonCancellable.
- **Provenance:** Earlier; device reproduction not run.

### C13 — Unobserved one-shot failure contaminates observed continuity

- **Location:** `battery/data/sampling/SamplingController.kt:250–266`.
- **Trigger/evidence:** an `observe=false` read fails while the monitored timeline remains intact. Failure paths set `overflow=true` without checking `observe`.
- **Impact:** the next observed point becomes GAP, potentially segmenting the session because of a widget/tile read.
- **Fix:** mark continuity loss only for observed captures; retain one-shot error diagnostics.
- **Provenance:** Earlier.

### C14 — Whole shared-UID power is labeled as one member app

- **Location:** `battery/apps/AppUsageSnapshot.kt:21–24`; `viewmodel/AppsViewModel.kt:383–388`.
- **Trigger/evidence:** multiple packages map to a UID, but the row resolves `packages.firstOrNull()` as its identity instead of showing shared attribution.
- **Impact:** all UID power appears to belong to an arbitrary member; ordering changes can change the apparent app.
- **Fix:** show a shared-UID identity/member count while retaining UID-level accounting.
- **Provenance:** Earlier.

### C15 — Widgets bypass localized percentage/duration formatting

- **Location:** `battery/widget/WidgetUpdater.kt:129–134`; `battery/util/TimeEstimator.kt:18–21`.
- **Trigger/evidence:** supported non-English locale; percentage uses `"$it%"` and duration hard-codes h/m rather than localized templates.
- **Impact:** Turkish widgets can show `42%`/`2h 5m` instead of the app's localized presentation.
- **Fix:** reuse localized number/percentage/duration formatters and verify all widget providers.
- **Provenance:** Earlier.

### C18 — Current-facing measurement/access claims contradict implementation

- **Location:** `docs/MEASUREMENTS.md:15–16,24,46`; `docs/PLATFORM_NOTES.md:19`; `README.md:11,21–29`; `CHANGELOG.md:5`; `fastlane/metadata/android/en-US/full_description.txt:1`.
- **Evidence/impact:** ETA's 45 minutes is a time constant, not a half-life; learned root sysfs full capacity is advertised despite no production caller of `CapacityEstimator.fromSysfs`; user calibration overrides are exported settings, unlike detected calibration; persistent ADB grants cannot restore per-app batterystats access on API 36 due to cross-user refusal.
- **Fix:** correct current-facing prose/store descriptions; distinguish grant persistence from capability and require Shizuku/root for that API 36 path. Do not silently add behavior merely to match prose.
- **Provenance:** Earlier umbrella P2; Latest independently confirmed the ADB guidance mismatch as P3. Specific outlier-guarantee wording is C26, not an extra copy of this finding.

### C19 — New-screen scaffold targets deleted navigation APIs

- **Location:** `.claude/skills/new-screen/SKILL.md:19,25`; `.claude/skills/new-screen/template.md:4,106–116`.
- **Trigger/evidence:** follow the scaffold's `Screen`, `backStack.add` and `HistoryViewModel.Ui` references; current APIs are Routes/NavKey, TopLevelBackStack and HistoryUiState. Raw screen dp and a bare screenshot-update instruction also conflict with project rules.
- **Impact:** generated navigation does not compile and violates token/rebaseline conventions.
- **Fix:** update scaffold APIs, theme-token usage and approved screenshot workflow.
- **Provenance:** Earlier; no fresh scaffolding execution.

### C21 — Session deletion races with the pending stop writer

- **Location:** `viewmodel/SessionDetailsViewModel.kt:262–265`; supporting `battery/data/BatteryRepository.kt:179–185,393–400` and `battery/data/db/Dao.kt:65–67,123–128`.
- **Trigger/evidence:** Stop clears public monitoring state before the writer drains Event.Stop. The delete guard consequently receives no recording generation; deletion can succeed before the writer upserts its cached session. The maintenance mutex does not serialize writer Stop/sample operations.
- **Impact:** a deleted session reappears without its deleted sample/app children. Database transaction atomicity does not prevent this later transaction.
- **Fix:** serialize single-session deletion through the writer queue with acknowledgment and writer-owned recording checks. Do not prohibit all activeKey rows: abandoned generations must remain deletable.
- **Provenance:** Latest + fresh errors-specialist validation; legal interleaving confirmed statically, incidence/window not measured.

### C22 — Skipped stale import overwrites the retained newer breakdown

- **Location:** `battery/data/ExportImport.kt:242–249`; parent skip at 207.
- **Trigger/evidence:** import a valid same-origin READY session ending 2000 with breakdown A, then an older version ending 1500 with B. The session loop skips the stale parent, but the separate usage loop replaces its rows.
- **Impact:** newer metadata is paired with older app usage; result can report zero changes and one unchanged session despite mutation.
- **Fix:** propagate stale-parent disposition to usage merge while preserving same-window enrichment and standalone app-usage CSV attachment.
- **Provenance:** Latest + fresh tests-specialist validation. Valid external imports are supported; no claim that the native collector routinely generates this pair. No executable Room reproduction.

### C23 — Global coverage fabricates per-screen drain denominators

- **Location:** `battery/data/SessionDrain.kt:24–28`; producers in `battery/measurement/ObservationEngine.kt:24–38,119–147` and `battery/data/sampling/SessionReport.kt:82–87`.
- **Trigger/evidence:** coverage differs between screen buckets, but persisted session mapping applies one combined fraction to each duration. The engine independently tracks covered duration; persistence discards that distinction.
- **Impact:** 20,000 µAh over 120 covered screen-on seconds followed by 120 uncovered screen-off seconds reports 1,200 mA instead of 600 mA; percent/hour is doubled too. Now, details and notification mappings consume this result.
- **Fix:** store matching per-bucket covered durations, withhold ambiguous partial rates for legacy rows and maintain schema/import/export compatibility. Existing uniform-partial-coverage tests do not prove producer compatibility.
- **Provenance:** Latest + fresh tests-specialist validation.

### C24 — Power-boundary closure omits closing screen-off suspend

- **Location:** `battery/data/BatteryRepository.kt:292–299`, especially 293; `battery/data/sampling/SessionReport.kt:40–43,88–92`.
- **Trigger/evidence:** valid screen-off discharge→charge boundary after one hour and only one additional second of uptime. The closing engine interval is accepted, but old extremes are reported before adding that interval's screen-off suspend; reset follows.
- **Impact:** the closed row can contain 3,599,000 ms CPU suspend but non-null zero screen-off suspend. Details prefers that zero and shows 0% rather than about 99.97%.
- **Fix:** accumulate closing suspend using the pre-accept interactive state before report/reset. Do not assign the new charging endpoint's peak power to the old discharge row. No change to simultaneous-event GAP policy is implied.
- **Provenance:** Latest + fresh errors-specialist validation.

## Confirmed P3 findings

### C16 — Output failure is reported as rejected input

- **Location:** `viewmodel/DataViewModel.kt:278–287,323–325`; `battery/data/ExportImport.kt:86,93,99,102`.
- **Trigger/evidence:** destination creation/settings export fails with IllegalStateException; failure mapping selects REJECTED even for write tasks.
- **Impact/fix:** misleading validation advice instead of a write failure. Use suitable I/O exceptions or task-aware UNWRITABLE mapping, preserving real validation errors.
- **Provenance:** Earlier.

### C17 — Cached Shizuku failure temporarily gives wrong recovery advice

- **Location:** `battery/util/ShellRunner.kt:56–58`; `battery/apps/AppStatsRepository.kt:113–122`; `viewmodel/AppsViewModel.kt:91–96`.
- **Trigger/evidence:** Shizuku stops/permission is revoked within the cached 10-second mode. Typed availability/access failures become generic helper Failure rather than NoAccess.
- **Impact/fix:** temporary misleading advice, usually corrected by refresh/bridge state. Preserve reasons, invalidate stale mode and classify access failures at production time.
- **Provenance:** Earlier; not a persistent denial claim.

### C20 — Historical checkpoints are presented as current guidance

- **Location:** `AUDIT_REPORT.md:3,80,150`; `docs/BASELINE_TO_CURRENT.md:30–50`; `docs/VALIDATION.md:7,41`; `docs/PR_DESCRIPTION.md`; `README.md:7,29,56`; `CHANGELOG.md:31`; `CLAUDE.md:55–61`; selected phase comments.
- **Evidence/impact:** DB4, deleted screens, old cadence, draft/unverified caveats and host/tool assumptions conflict with this revision. Missing tooling in this environment does not invalidate historical runs.
- **Fix:** label/archive old checkpoints, provide current guidance and distinguish project requirements from optional machine tooling. Preserve decision provenance. The specific false capacity-outlier guarantee is handled separately in C26.
- **Provenance:** Earlier; latest SDK-path correction reinforces the need to distinguish host facts.

### C25 — In-flight lookup repopulates an invalidated app cache

- **Location:** `battery/apps/AppInfoCache.kt:36–43,50–59`; invalidation callers in `battery/apps/AppInfoRepository.kt:30–34,50–55`.
- **Trigger/evidence:** lookup captures old metadata/icon, package or density invalidation occurs, then the old lookup publishes. It can also overwrite a newer completed load.
- **Impact:** subsequent reads return stale label/install flags or an old-density icon. Container thread safety is not freshness; memory-trim repopulation alone is not the confirmed defect.
- **Fix:** package/global generations with validity checking and publication atomic relative to invalidate/clear. A standalone pre-put check is insufficient.
- **Provenance:** Latest + fresh tests-specialist validation.

### C26 — Weighted-median outlier guarantee is overstated

- **Location:** `docs/MEASUREMENTS.md:46`; `battery/measurement/CapacityEstimator.kt:68–72,74–95`.
- **Trigger/evidence:** LOW/MEDIUM/HIGH weights are 1/2/3. Two 4 Ah LOW estimates plus one 6 Ah HIGH estimate produce 6 Ah/HIGH, or 150% health against a 4 Ah design value.
- **Impact:** prose claiming one outlier cannot move the median/raise confidence promises stronger robustness than the algorithm supplies.
- **Fix:** say weighting reduces outlier influence but high-confidence estimates can dominate sparse lower-confidence history. Do not classify intended confidence weighting itself as a proven algorithm defect.
- **Provenance:** Latest comments specialist + coordinator inspection.

## Disputed prior finding — not in the confirmed count

### C04 — Running but unauthorized Shizuku blocks backend fallback

- **Location:** `battery/util/ShellRunner.kt:98–105`; `battery/apps/AppStatsRepository.kt:110–119`.
- **Established mechanism:** when Shizuku is running but unauthorized, mode selection returns NONE before probing authorized root/ADB. Main previously tried root first.
- **Earlier assessment:** confirmed P2 because per-app operations fail despite an authorized root backend.
- **Latest assessment:** product policy unresolved; no new security exploit demonstrated. Capability availability does not itself establish a requirement to fall back after Shizuku denial.
- **Next step:** decide/document the intended fallback contract. If fallback is required, probe other authorized backends and test running-but-denied Shizuku with authorized root. Keep this separate from no-fallback-after-command-failure policy.

## Optional hardening and simplification

These are not additional confirmed defects. Earlier full rationale is retained in the prior report.

- Scope preview signing environment to required build steps; no secret leak was established. Third-party broker binder ownership and settings-library internals were not audited deeply enough to claim an exploit.
- Preserve structured shell failure reasons; separate raw/calibrated current types and explicit shared/unknown app identity. Current production calibration wiring is correct.
- Strengthen close/tab-route/ETA invariants, tolerantly skip unknown diagnostic codes if downgrade compatibility is wanted, and share recording-generation predicates.
- Distinguish unavailable readings from failed package/cycle-count reads; improve local task/migration/timeout/blocked-alert diagnostics without exposing sensitive data.
- Consider expected-caller helper authorization and busy-request resource hardening. Exact command allowlisting already prevents arbitrary command execution; foreign binder access was not established.
- Inspect unused resources/parser APIs before removal; wire locale config if wanted. Add missing plural categories and locale-aware counts. These are not blanket deletion instructions.
- Prefer one DI ownership path over BatteryGraph, but thin repository adapters remain valid test seams. Build display strings after eligibility where useful; no measured material battery regression exists.
- Scan DumpOutput.failure once, reuse countdown/day-aware formatting, share the two cancellation-safe sequential app-info fallbacks, derive Apps metrics once while preserving ordering/null/tie rules, and replace side-effect-only `zipWithNext` in SessionDetails with ordered iteration.
- Decide partial SAF output cleanup/reporting policy; deleting an existing user-selected document after overwrite failure is not automatically safe. Symmetric CSV formula hardening is optional before accepting arbitrary free text; no current export injection vector was found.

## Regression tests to add

| Priority | Behavior | Coverage direction |
|---|---|---|
| Highest | C21 delete/Stop interleaving | Deterministically pause writer before closure; delete through intended serialization; assert no resurrection or child loss |
| Highest | C22 stale import and enrichment | Real Room imports: newer A then older B; verify metadata, usage and result counts; preserve same-window/standalone CSV cases |
| Highest | C02/C23 counter coverage | Producer-to-persistence tests with asymmetric bucket coverage and missing/reset counters; daily replay and ETA eligibility |
| Highest | C24 closing suspend | Coherent elapsed/uptime power boundary with screen off; verify suspend split and details mapping |
| Highest | C01 helper lifecycle | RequiresShizuku device stop test and repeated idle binds; verify server behavior |
| High | C03 partial counter/level span | SessionReport→CapacityEstimator tests for incomplete coverage and confidence |
| High | C25 invalidation race | Suspend lookup, invalidate/clear, publish old/new loads in both orders; assert freshness |
| High | AppDetails identity and history | Real Room repository history composition, live UID reuse, secondary-user/shared UIDs |
| High | Parser/outcome boundaries | Sanitized API34/35/36 golden dumps; malformed app-power vs valid-empty; actual access-result translation |
| High | Repository close/recovery | Extend unplug/reset/gap/retry coverage; inject saveEnd failure and prove transaction rollback |
| Medium | Data task lifecycle/restoration | Process death during SAF, Back/cancellation policy, destination/write outcome mapping |
| Medium | Monitoring ownership | Collector service-lifetime cancellation; one-shot failure, observed queue overflow/stale screen event; direct polling-generation assertions |
| Medium | Alerts, retention, migration | Pure alert-input mapping, latch persistence, retention thresholds/ties, settings migration failure outcomes |
| Low | Defensive parsing/allowlist | Unsupported helper commands, malformed frame magic/length, unknown settings keys |

Existing test limitations: a 6.5-second persisted-row stop assertion can miss leaked polling because saves are spaced at 30 seconds; wall-time/count-range recovery checks can be timing-sensitive; device history tests can cross midnight; vacuous hero assertions do not pin behavior; a 1.5-second command timing bound can flake under contention. `check_history_queries.py` validates bounded chart SQL, but the actual details path reads samplesForSession before mapping/downsampling; its green result is not proof of bounded screen I/O. The unbounded read predates this change, so no new performance defect or benchmark is claimed.

## Unresolved hypotheses and policy decisions

None of these is counted as a confirmed defect:

- **Simultaneous transitions:** sequencer and engine conservatively reject multiple changed dimensions; changing only the sequencer is insufficient. Decide whether to support multi-event confirmation or communicate lost coverage.
- **Since-unplug segmentation:** gaps/recovery can close/reopen without physical unplug. Decide retention/labeling policy without bridging unobserved intervals.
- **Calibration evidence window:** CurrentCalibrator can retain an open window across mismatched power intervals. Decide continuous-direction versus disjoint covered evidence before changing it.
- **Backfill retries:** establish a numeric replay/live mismatch before claiming overwrite corruption; test idempotence/equivalence.
- **Active-session retention:** purging old samples while preserving active rows truncates long charts but may implement intentional policy.
- **Migration/library failures:** hung migration waits or crash loops require actual library behavior evidence; establish safe recovery without purging unmigrated settings.
- **Shizuku denial/binder scoping:** validate permanent-denial UI/library semantics and whether broker binder possession can leave the requester; no introduced exploit established.
- **Boot/update and intent replay:** verify restart behavior after package replacement and actual destination replay after process death before changing receiver/intent semantics.
- **Background root prompts:** root-manager policy and opt-in requirements determine whether probing should prompt. Backend fallback itself is C04 above.

## Executed checks and limits

| Check | Earlier consolidated run | Latest inherited run |
|---|---|---|
| Unit tests | PASS: 542, zero failures/errors/skips | PASS: 82 suites, 542 tests, zero failures/errors/skips |
| Android debug lint | PASS: zero errors, 46 warnings | PASS: zero errors, 36 warnings; preview not rerun |
| Resources | PASS: XML and 628 matching keys in es/tr | PASS: matching keys, format/newline parity and XML |
| Migrations | PASS: 1/2/3/4→5 chain, DDL/data preservation | PASS: chains, DDL/indices/FKs, retained data/sentinels and active-session uniqueness |
| History-query checks | PASS | PASS: four SQL-check groups; not device/Room integration execution |
| Script unit tests | PASS: 14 | PASS: 14; fake device orchestration/output parsing, not actual device tests |
| Shizuku constant inspection | javap confirmed destroy code 16777115 | Not repeated |
| Merge dry run | AGP catalog-line conflict only | Not repeated |
| Screenshot validation | Not run | BLOCKED before execution: no offline cached screenshot-validation-api:0.0.1-alpha16; Gradle exit 1 |
| Diff whitespace | Not reported | FAIL, exit 2: 93 license line-ending/trailing-whitespace diagnostics plus four source whitespace/EOF diagnostics; nonfunctional |
| Device reproductions/connected tests | Not run | Not run |
| Preview/release checks, performance, CVE lookup | Not freshly established | Not run |
| Candidate-specific Kotlin/Room reproductions | Not established | Not run; validations static |

Latest unit/lint checks initially failed before execution because the assumed SDK path was wrong. They subsequently passed using `ANDROID_HOME=/home/dev/android-sdk`, JDK 21, the pinned checkout, offline mode and serialized Gradle execution. No local.properties was read/edited; no dependencies were installed/downloaded and no screenshot references were changed. Earlier historical screenshot/device successes are not fresh results for either audit.

## Specialist coverage and configuration

| Review | Configuration | Completion and limitations |
|---|---|---|
| Earlier PR review | Seven selected plugin reviewers | All returned reports; separate compose-reviewer/module-graph-auditor unavailable and did not run |
| Earlier Fable audit | fable / inherited effort | Bugs/security/errors/types/tests completed; comments incomplete; quality/simplify supplemental resumed results not Fable-verified; fresh Fable validation blocked HTTP429/session limit |
| Latest inherited audit | inherited model / inherited effort, maximum four concurrent | Eight roles plus two fresh validators completed; effective reviewer runtime model/effort not independently exposed |
| Astra/xhigh attempts | Requested claude-gpt-6-astra / xhigh | No reviewer results/tests: earlier profile discovery failures, later Agent model schema exclusion; startup launcher dry-run does not prove actual model availability |

Latest role coverage:

| Role | Status | Areas inspected / limitations |
|---|---|---|
| Comments | Complete | Measurement/platform docs and implementation; C26 and ADB guidance |
| Tests | Complete | Core/repository tests and gaps; unit execution; no real-device run |
| Errors | Complete | Import, propagation, lifecycle; C22 |
| Types | Complete | Observation/session/daily coverage and ETA; C02/C23 |
| Quality | Complete | Cache freshness, boundaries, host checks; C25 |
| Simplify | Complete | Selected fallback/formatting/iteration duplication; optional changes only |
| Bugs | Complete | Writer lifecycle, deletion and power-boundary accounting; C21/C24 |
| Security | Complete | Privileged commands/IPC, imports, manifest/exported components, diagnostics/storage and CI; no confirmed introduced vulnerability, third-party internals unresolved |
| Fresh tests validation | Complete | C02, C22, C23, C25; static falsification pass |
| Fresh errors validation | Complete | C21, C24; static falsification pass |

This is selective static review of a large change, not exhaustive full-file/full-platform certification. Requested configurations are not proof of runtime identity. Completion of the latest inherited audit does not retroactively complete Fable or Astra requests.

## Recommended order and completion state

1. Address writer/delete ordering, stale-import integrity, helper destroy protocol and coverage-sensitive daily/session/capacity calculations, including closing suspend accounting.
2. Resolve C04's fallback contract; fix raw-current export metadata, explicit navigation, Data lifecycle/restoration, attribution and malformed-versus-empty parsing.
3. Fix cache freshness, localized widgets/failure advice, scaffolding and current-facing documentation; preserve historical provenance.
4. Add focused regressions, then rerun unit/lint and relevant screenshot/device workflows. Resolve policy questions explicitly, not by silently altering behavior to satisfy suggestions.
5. Complete model-specific review gaps if still required. Resolve main's AGP 9.4.1 versus this branch's approved 9.5.0-alpha07 conflict without automatically downgrading screenshot-suite support.

No application fixes, commits, pushes, merges or PR comments were made by these audits. The latest temporary request and detached review checkout were removed; pre-existing dirty files were preserved. This combined file and its PROGRESS.md pointers are documentation only. Creating this consolidation ran no new application checks and did not revalidate every earlier finding.
