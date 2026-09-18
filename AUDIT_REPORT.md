# BatStats Audit Report

Baseline: `76bc831328572c81717b97ffb0e280b10b14b8ad`; branch `codex/android16-reliability`. Supplied APK6.2.6/code735 matches APK-dist's universal +1 offset for source734; that difference does not imply a different source revision. [PROGRESS.md](PROGRESS.md) is the execution handoff; [VALIDATION.md](docs/VALIDATION.md) retains actual checks, failures and commands.

## Coverage
- [x] Build/dependencies/manifest, permissions, backup, CI/release source and store claims reviewed; local workflow/build fixes prepared.
- [x] Monitoring lifecycle/access/transport/security, measurements and session calculations reviewed and revised.
- [x] Room/migrations/import/export/clear/retention reviewed; host SQL and synthetic regressions run.
- [x] Advanced collectors, Android16 producer layout, UID attribution and kernel ABI reviewed; vendor outputs unverified.
- [x] ViewModels/navigation/layout/theme/widget/alert/settings/resource source reviewed; locale inventory corrected by comparing actual baseline values.
- [ ] Actual Android16 screens, accessibility, loading/empty/partial/error/recovery and notification/widget interactions.
- [x] Final host tests/full lint and signed Debug/Preview APK assembly/collection.
- [x] Real Shizuku Binder authorization, command validation, helper/server recovery on API36 (both0e9b301 runs). Visual and16KiB checks remain pending.

## Confirmed findings

Source review confirms these defects unless runtime reproduction is stated. Implemented fixes are not a claim of complete hardware validation.

| ID | Priority | Baseline evidence / affected area | Fix and verification status |
| --- | --- | --- | --- |
| B01 | High | `BatteryRepository`: missing level becomes 0; MIN_VALUE current survives fallback; valid zero replaced by average; power/current widgets inherit invalid readings. | Fixed; JVM coverage and API36 missing/error/recovery UI assertions pass |
| B02 | High | `AdvancedDrainTracker.isInDeepSleep`: total elapsed−uptime since boot >30s labels every later screen-off snapshot deep sleep; Doze time incorrectly used as CPU sleep. | Fixed; separate observed CPU/Doze accounting tested |
| B03 | High | Drain tracker: end-of-interval screen attribution, racing receiver/poll jobs, wall-clock durations, charge time included in denominator, reset leaves prior snapshot, 4000 mAh fallback creates fictional consumption. | Fixed; monotonic observation/screen/gap/coverage tests pass |
| B04 | High | Repository auto-session logic is a TODO; manual starts allow multiple active sessions; no reboot/gap boundaries; all-null current average converts NaN to 0. | Fixed; shared writer and DB4 active uniqueness; API36 Room migration/recovery tests pass |
| B05 | High | `TimeEstimator` assumes 4000 mAh and instantaneous current; UI invents health percentage from cycle-count thresholds. | Fixed; stable counter ETA, no fixed capacity or cycle-derived health |
| B06 | High | Android manifest/service uses `dataSync` for indefinite monitoring (Android 15+ six-hour limit); boot coroutine not protected by goAsync; privileged mode only chosen at startup. | Implemented; API36 service/boot runtime tests pending |
| B07 | High | `ShellRunner` silently falls through Root→Shizuku→ADB; helper reports partial timeout/nonzero-exit output as success, truncates output; inline timeout occurs after blocking read. | Implemented; transport/cancellation JVM tests and actual API36 Shizuku Binder/loss/reconnect pass; Root hardware unverified |
| B08 | High | `DetailedStatsCollector`: successful power/deviceidle read hides failed batterystats and refreshes timestamp of stale snapshot; failed subreads retain previous data. | Implemented; stale snapshots/generations handled; API36 Shizuku loss/reconnect and ordinary-data survival pass |
| B09 | High | `BatteryStatsParser`: jobs/sync count/time swapped per Android 16 BatteryStats.java; Doze idling fields mislabeled as maintenance; app detail fields never populated but displayed as zero. | Fixed against Android16 producer; synthetic parser regressions pass |
| B10 | High | `BstatsCollector`: newly seen UID uses zero prior counter (attributes pre-observation usage); missing epoch/backend boundaries; retries every 5s on failure; duplicate dumps across collectors. | Removed unused duplicate writer; detailed UID collector preserved |
| B11 | High | `ForegroundDrainTracker`: arbitrary 80/20 mA subtraction, current charged attributed to last foreground app, ignores pause/stop and observation gaps; setting not consulted. | Removed unsupported heuristic; detailed UID reports preserved; ineffective setting retired in4b1 |
| B12 | High | Room destructive fallback loses history; imports nontransactional/unbounded and duplicate sample IDs reset; active imported sessions overwrite local state; exports ignore session range. | Implemented; bounded transactional imports/export, nondestructive migrations; JVM/host SQL and API36 import/export/migration tests pass |
| B13 | High | Settings clear-all calls Room blocking API on main thread; service may continue writing during deletion. | Implemented; serialized clear/start gate and cancellation tests pass; API36 deletion and reset-dialog checks pass |
| B14 | Medium | History `SessionCard` internal empty click handler consumes navigation; detail/export screens lack vertical scrolling; six dashboard actions crowd toolbar; charts replace missing with zero and lack axes/time. | Implemented navigation, responsive/scrollable screens, bounded charts and missing-data states; actual visual review pending. |
| B15 | Medium | Monitoring notification rebuild resets timestamp; settings/alarms/retention largely unused; widget IPC on every sample even no installed widgets. | Implemented stable rich notification, alert/settings behavior, bounded retention and no widget work when absent; delivery/runtime pending. |
| B16 | Medium | Root-only collectors read files as app UID, not su; unsupported devices appear empty/zero; battery capacity advertised as true/exact. | Implemented; actual su reads and validated kernel parsers; vendor/root hardware unverified |
| B17 | Medium | Release workflow had stale application-id, ignored publication inputs and production defaults. | Implemented locally in5a: separate read-only manual APK workflow, reports/signatures, release gate/correct ID/defaults. actionlint/build checks pass; hosted execution approved; pending results |
| B18 | High | Alpha Compose requires SDK37.1 while project declared37.0. | Fixed with stable Compose BOM; metadata and app compilation pass. |
| B19 | High | ADB dump gate incorrectly modeled DUMP/PACKAGE_USAGE_STATS/AppOps. | Fixed to Android16 producer contract; actual device grants pending. |
| B20 | High | `--checkin` may consume a completed saved report. | Fixed to current non-consuming `-c --charged`; source/window remains explicit. |
| B21 | Medium | Default automatic backup included history databases. | Fixed manifest and both backup-rule generations: settings DataStore only; XML checked, OEM transfer unverified. |
| B22 | Medium | New advanced UI strings lack locale translations. | Baseline locale files proved entirely English duplicates. Consolidated fallback and complete Spanish/Turkish translations in208d9b8. XML/AAPT2/full lint pass; visual review pending. |
| B23 | Medium | Dark onSecondary#3B1F70 over secondary#8B5CF6 had contrast3.07:1. | Fixed palettes; normal/fixed text and outline contrast regressions pass. Actual screens pending. |
| B24 | Medium | Local chart query included imported/legacy readings without source labels. | Fixed local query and legacy/import detail evidence labels; host SQL passes, Android regressions compile. Device rendering pending. |
| B25 | Medium | Widgets lack freshness/stopped labels; absent in-memory sample leaves stale/blank display. | Implemented; actual widget delivery passes one0e9b301 run, another times out. Phase diagnosis and visual review pending. |
| B26 | Medium | Settings renderer used generated English metadata/resource IDs0; labels bypassed string resources. | Fixed resource-backed titles, descriptions and options; API36 settings resource checks pass. Full Spanish/Turkish resources added; native-speaker review unavailable. |
| B27 | High | Plugged/non-charging normalization ignored explicit Android DISCHARGING status, excluding connected discharge. | Corrected status handling; synthetic contradictory/connected cases pass. |
| B28 | Medium | History exposed only100 rows; details loaded unbounded raw samples and reused live/old data after deletion. | Paged queries, bounded linked charts and explicit missing/loading/legacy states; hostSQL/JVM pass, device pending. |
| B29 | Medium | Settings0.8.3 import bypassed dropdown/slider ranges and accepted unbounded payloads; UI hid errors. | Bounded prevalidation and inline feedback; JVM and API36 invalid-import dialog assertions pass. |
| B30 | Medium | Queued SQL publication could overwrite newer live captures, including distinct captures within one millisecond. | Full captured-input matching before applying persisted metadata; JVM regression passes. |
| B31 | Medium | Shared error field retained recovered battery failures but erased event-subscription failure after SQL success; polling exceptions escaped and failed startup SQL prevented observation reset. | Source-specific errors, safe capture, paused observations without state events, bounded retry and startup recovery. All5 API36 recovery regressions pass after B34 correction. |
| B32 | High | APK-dist wrote into AGP's input directory; connected-test task validation failed before execution. | Separate distribution output; subsequent hosted builds and actual Android execution pass |
| B33 | High | Earlier stable-BOM correction left transitive Material3 alpha ABI incompatible with Foundation; opening TextField crashed on API36. | Enforced compatible stable BOM; API36 history/settings TextField assertions pass in both0e9b301 runs |
| B34 | High | Room query-scope cancellation killed the still-active repository writer, preventing lifecycle recovery. | Respect owner cancellation while handling failed DB operations; all5 API36 recovery tests pass |
| B35 | Medium | Monotonic clock reads straddled Binder IPC, injecting latency into CPU-suspend accounting. | Read clock pair adjacently; hosted values agree, physical suspend accuracy unverified |
| B36 | High | Poll/resume can see wake before its broadcast, causing the prior off interval to be discarded. | Bounded one-capture sequencing implemented;11 JVM regressions pass, device rerun pending |

## Additional verified corrections
- Fractional Android capacity estimates no longer truncate to whole mAh; invalid/nonintegral discharge fields and nonfinite values are rejected.
- Monitoring notification/widget IPC failures no longer terminate their update coroutine. Alert episode latches recover after delivery failure; channel setup happens only on delivery attempts.
- Default backup contains settings only. Diagnostics use fixed codes, bounded local AtomicFile storage, coalesced writes and deliberate sharing; no raw dumps or identifiers are exported in a diagnostic report.
- Preview is optimized/nondebuggable with a separate package and explicit signing configuration. Removed unused direct WorkManager/navigation/constraint/browser dependencies and unused permissions after checking callers; Shizuku provider protection remains.
- Existing locale folders were162 English duplicates each, not translated support. Consolidation preserves English fallback.493 Spanish and493 Turkish resources are complete; format/XML/AAPT2 checks pass. No native-speaker review or translated-screen success is claimed.

## Historical validation (later checkpoints supersede pending status)
- Approved publication created draft PR1 and branch91c6053. First push/PR CI runs35320355939/35320370581 failed before building: wrong SDK package platforms;android-37. Correct path platforms;android-37.0 matches installed metadata. Workflow corrected; new execution pending. This was setup failure, not a device assertion failure.

- Hosted push35320644172 passed both94-case JVM suites/full lint, then packageDebug hit Java heap OOM. Parallel PR35320646877 completed build and booted API36, but device tests were blocked by overlapping APK task outputs. One-worker/in-process/3GiB CI configuration prepared; no assertions changed.

## Actual validation
- Baseline dependency metadata failed: alpha Compose required SDK37.1. Stable Compose fixes compilation against37 with target36/min26.
- Producer-based parser tests reproduced12 baseline failures before fixes. Latest JVM suites each pass94 tests in Debug and Preview,0fail/error/skip. Host migration/history SQL checks pass. These inputs are synthetic, not physical measurements.
- Stage5c built fresh Debug, optimized nondebug Preview and test APKs with locale/configuration fixes and all25 device-test methods. Signatures/artifact metadata verified from clean dd7f2c0; runtime checks remain.
- At stage5c all25 Android test methods compiled but had not executed; stage6e hosted results are recorded below. Device suites cover repository recovery, actual screen/FGS/notification transitions, UI navigation/font200 and real Shizuku authorization/helper/server recovery.
- Stage5c full Debug lint now has zero MissingTranslation errors but failed7 Compose LocalContextGetResourceValueCall errors/202warnings. Configuration-aware resource corrections pass full Debug/Preview lint (0errors;201/200warnings), with94 JVM tests passing per variant. The full command, including all three assemblies, passed16m. No errors suppressed. Fatal release lint, actionlint and direct resource compilation have passed, but are not substitutes for full lint.

## Environment and unresolved verification
The host has no KVM and3.8GiB RAM plus4GiB swap. Builds/emulators ran sequentially. The full API36 google_apis software emulator never reached sys.boot_completed during approximately68minutes, despite launcher/window initialization. Both baseline installs were rejected as still booting. The final window dump timed out; a late screenshot contains only a blank background. Emulator stopped; wrapper returned139 during shutdown. No BatStats or Shizuku guest APK installed. Userdata/logs retained; unrelated AVDs preserved and the previously removed shared ATD SDK restored.

A prebuilt-APK ADB runner avoids a competing Gradle JVM; six synthetic runner-result regressions pass. That local attempt produced no app screenshot or Android execution. Hosted Android results are recorded below; Shizuku Binder passes, widget delivery has an intermittent timeout and visual review is pending. No physical Samsung device is available or requested. Current polarity/scaling, fuel-gauge calibration, real AOD transitions, OEM background restrictions, root/vendor kernel paths,16KiB runtime and physical energy overhead remain unverified. Neither source changes nor emulator results establish battery-saving percentages.

## Unresolved native runtime check
Both APKs pass16KiB ZIP/PT_LOAD alignment. The stricter documented RELRO-end check flags graphics-path (arm64/x86_64); inspected headers contain no trailing writable data in the rounded protection range. This is an unresolved runtime question, not a confirmed crash or a compatibility pass. See VALIDATION for exact evidence; no binary or dependency changed.

## Existing GitHub automation constraint
Read-only inspection of akane599/BatStatsAKA confirms default branch main, workflow-token permissions read, and can_approve_pull_request_reviews=false. The inherited Dependabot reusable workflow requests write permissions and attempts automatic approval/merge only for dependabot[bot]. Actual run35320371541 failed before any job: requested contents:write/pull-requests:write exceed caller contents:read/pull-requests:none. Disabled automatic-review policy is a separate constraint. No settings changed or privileges broadened. The new APK workflow uses contents:read, has no publication steps, and is reused by push/PR CI with Android16 tests mandatory and no signing secrets passed. actionlint passes; hosted execution approved; pending results. This existing automation issue remains separate from app validation; enabling remote approvals would require explicit authorization.

## Unresolved at requested handoff
- Stage6l has not completed compilation or tests. Its reset layout, compact notification and synchronization changes need future validation; no pass is claimed.
- Last hosted checkpoint has one ordinary navigation failure; landscape, settled-frame visual review and16KiB runtime checks remain incomplete. Samsung hardware, real current/capacity accuracy and energy overhead remain unverified.
- Deliver current source through the approved branch/draft PR only. Existing APKs remain e52daf9. Stop after publication; do not continue these tasks without a new request.

Unused helpers and general cleanup are independent low-priority opportunities, not a reason to expand the change. Additional per-UID component transport would need its own validated schema/window; current activity counters cannot support arbitrary energy attribution.

## Primary platform references consulted
- [BatteryManager units, sentinel values and charge-time approximation](https://developer.android.com/reference/android/os/BatteryManager)
- [PowerManager interactive state and ambient display](https://developer.android.com/reference/android/os/PowerManager#isInteractive())
- [Foreground service types and specialUse declaration](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Samsung sleeping/deep sleeping app restrictions](https://www.samsung.com/us/support/answer/ANS10003442/)
- [Shizuku API lifecycle and UserService](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)
- [Android 16 checkin producer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/os/BatteryStats.java), local reference `/tmp/batstats-BatteryStats-android16.java`.

Additional sources/contracts and privacy constraints: [docs/PLATFORM_NOTES.md](docs/PLATFORM_NOTES.md). Do not treat source review or emulator success as proof of physical battery accuracy.

Hosted build finding B32 (High): APK-dist defaults its copy output to the AGP input directory. PR35320646877 failed connectedDebugAndroidTest with an implicit-dependency validation error before any tests ran. Local task-path inspection reproduces the overlap. Configure separate build/outputs/distribution/{variant} copies; standard AGP outputs remain unchanged. Local Gradle verification passes for all three variants; hosted revalidation pending.

Stage6d adds an isolated production history UI regression for card navigation, imported-source provenance, current chart gaps/units, missing temperature and stale-data removal after deletion. All26 Android test methods compile locally in3m36s; the new method has not executed. Both ff792f4 hosted builds passed and entered the Android16 device step; reports pending.

Stage6e hosted runtime: actual Room1/2/3→4 migrations,8 import/export checks,2 history query checks, AtomicFile persistence, notification construction and settings resources passed. PR35323229857 has16pass/5failed entries; push35323226385 has17pass/4failed. Both include one invalid-runner entry (a Kotlin-inferred nonvoid recovery method), preventing4 recovery methods. Real methods executed:20 per run. Dashboard simulated event failure was not injected because ContextCompat calls the5-argument receiver overload. These harness fixes preserve all assertions.
B33 (High, reproduced regression in the earlier stable-BOM correction): Material3 alpha17 pulled by settings UI is ABI-incompatible with resolved Foundation1.12.1. Opening OutlinedTextField throws CustomStyle.applyStyle AbstractMethodError; runDetachLifecycle is a secondary teardown exception. Enforced stable BOM prepared after verifying all45 settings Material3 member references exist in stable1.4.0; full local validation in progress. Notification navigation/screen timing and one system-back failure remain under investigation. Shizuku never ran because ordinary phase failed. Screenshot files were not retained by the post-Gradle shell pull; AGP additional-output collection prepared. No visual success claimed yet.

The revised device script preserves each phase separately and attempts Shizuku even after unrelated ordinary-test failure; either failure keeps CI failed. Eleven host-only parser/orchestration tests pass, including missing setup and incomplete boot guards. Both94-case JVM suites pass with the enforced stable BOM; full lint/assemblies still running.

Stage6e full local validation PASSED23m46s:94 JVM cases per variant,0fail/error/skip; full lint0errors/201Debug+200Preview warnings; Debug/optimized Preview/Android-test APKs assembled. Signatures and16KiB ZIP alignment pass. B33 runtime revalidation and unresolved notification/screen/back behavior remain pending. No assertions suppressed.

Stage6f: PNG plus UI hierarchy captures and phase-labelled lifecycle failure snapshots prepared. The notification test now taps the actual SystemUI row, and Back assertions wait for the destination instead of only Compose idle. Independent Shizuku starts awake and captures failures. No behavior assertions removed. Local Android test compilation and test APK assembly pass; hosted execution pending.

Stage6g: a real AppWidgetHost regression binds all3 development providers, checks delivery, stopped/fresh timestamps, absent values, Celsius/Fahrenheit and stored ETA. Inputs/screenshots are explicitly scripted; test shell binding permission is dropped and the test-owned host deleted. Android compilation/test APK assembly PASSED1m13s (27 methods), not yet executed. Clean0e7455a Debug/Preview assembly and signature collection pass; artifacts/build-info.json records current hashes and signer. Current hosted revalidation: push35327878801 and PR35327883884 at0e7455a.

## Stage6h findings and active validation
Both0e7455a runs built successfully, then ordinary25methods produced19pass/6fail and Shizuku1fail (no skips). Five ordinary failures are capture EACCES from the shell-created external output path; remaining UI assertions did not execute. Three of the four repository recovery methods now pass. Shizuku Binder/shellUID2000 checks passed and permission activity displayed, but the official resource names are obfuscated; the test selector missed the visible dialog. Use package-scoped visible text from the pinned English release, not a guessed resource ID.

- **B34 High, confirmed:** Room's separate query-scope cancellation escapes the active repository actor and silently ends lifecycle processing. Source2.8.5 confirms close→coroutineScope.cancel and DBUtil→withContext(database query context). The storage-restart regression times out in both runs. Prepared fix preserves owner cancellation but reports operation cancellation, processes stop/reset despite SQL failure and clears stale session references; existing regression retained and stop/reset coverage added.
- **B35 Medium, confirmed:** captureBattery samples elapsed then several Binder properties then uptime. A hosted snapshot has uptime42ms later than elapsed, which can distort CPU-suspend differences or create false gaps as IPC latency varies. Prepared fix reads properties before adjacent monotonic clock samples; physical accuracy remains unverified.

Capture fix writes PNG/XML privately then copies through the test shell into a dedicated Download directory collected by AGP. Eleven host runner tests/bash/actionlint pass. Full local compile/JVM/lint/assemblies are running; first attempt intentionally interrupted to include B35. No new runtime pass or visual review claimed.

Stage6h full replacement build PASSED14m29s:94 JVM cases per variant, full lint0errors/201+200warnings, all APK assemblies. Final test-only capture helper compilation/assembly/lint remains running. B34/B35 device proof and screenshot review remain pending.

Final stage6h test-helper compile/assembly and Debug lint PASSED2m13s; all28 device methods compile. Eleven host runner regressions, bash syntax, actionlint and diff checks pass. Ready for approved hosted validation; no device assertions weakened.

Stage6i extends the existing dark/font200 navigation test through landscape dashboard and observed-drain screens, with orientation cleanup. Android test compilation/assembly PASSED1m;28 methods total. Held locally while0e9b301 hosted checks run. Current installable APKs were recollected from clean0e9b301 after successful38s incremental assembly; signatures and16KiB ZIP alignment pass.

Stage6j prepares actual16KiB Android testing: SDK catalog lists API36 google_apis_ps16k x86_64 revision7; pinned emulator action explicitly supports this target. A second sequential device pass reuses built APKs, with per-image reports and required page-size verification. New native test checks the app process page size and explicitly loads graphics-path/DataStore libraries.29 device methods compile; test APK assembly passes1m4s.14 host parser/orchestration checks, bash/actionlint/diff checks pass. No16KiB runtime pass yet. Current0e9b301 APKs rechecked: all64-bit PT_LOAD alignments pass; strict graphics-path RELRO-end warning remains, no trailing writable LOAD overlap. Reports include APK SHA256.

## Stage6k confirmed runtime results and remaining faults
At0e9b301 both hosted builds and Shizuku1/1 pass. PR35331604578 ordinary25pass/2fail; push35331600751 ordinary26pass/1fail, no skips. All5 repository recovery methods pass (B34); adjacent clocks match (B35). Widget delivery passes push, times out PR with no phase yet. Lifecycle passes behavior assertions in PR but ActivityScenario ignores cleanup after onNewIntent changes launch intent. Push exposes B36.
- **B36 High, confirmed:** resume/poll observes new interactive state before SCREEN_ON broadcast; engine treats this ordering as missing transition, drops prior off interval. Prepare bounded one-capture sequencer preserving actual endpoint until matching event; absent/conflicting events must still create a gap, no timer/wakeups.
- Screenshot exports are all zero bytes: SELinux denies run-as writes to FUSE stdout even though command exits0. No visual review has occurred. Copy private captures through shell-owned pipe and verify exact byte counts. This corrects earlier retention assumptions.
- Stage6k next: integrate/test sequencer and stale screen event handling, repair capture/cleanup, add widget failure phase diagnostics; then full local checks, focused commit and approved CI push. Held landscape/16KiB commits bebe479/a59149c compiled but not runtime validated.

Stage6k full local validation PASSED15m10s:105 JVM regressions in each Debug/Preview variant (0fail/error/skip), all29 device methods compile, both full lint variants0errors/201+200warnings, Debug/optimized Preview/test APKs assembled. Fourteen host runner tests and actionlint pass. Sequencer, capture-pipe byte verification and exact ActivityScenario intent cleanup are ready for device execution; widget phase diagnostics retain every assertion. No device/visual pass is inferred from local compilation.

Stage6k device proof: both e52daf9 runs pass27/28 ordinary plus1/1 Shizuku. B36 lifecycle/screen-off/stop/restart and native4KiB loading pass; widgets pass both. Capture byte verification works (62 nonempty PNGs); actual visual review started. Only NavigationDeviceTest reset-Cancel→system-Back fails (new stage6i path), landscape not reached;16KiB phase skipped by failed standard step.
**B37 Medium confirmed:** reset-dialog confirm FlowRow and dismiss button overlap at200% font (PNG and XML bounds). Use a single vertical action column, assert non-overlap. Test sends external Back before explicitly waiting for dialog teardown; synchronize before retaining its exact navigation assertion and add failure capture.
**B38 Medium confirmed:** notification's full permission instructions consume its expanded height and clip period/activity values. Compact failure summary, prioritize observed window and retain rich measurement sections; verify actual screenshots. Widget PNG/XML inconsistency is a frame-capture timing issue (correct fields/assertions/XML), add UI-idle capture barrier.

User requested stopping testing; stage6l validation was interrupted with Ctrl-C (exit130) before tests completed. No new pass is claimed. The user subsequently explicitly requested pushing the current work. Stage6l edits are included in the final publication checkpoint, unverified; collected APKs remain e52daf9 and exclude these edits. Push-triggered CI is automatic and was disclosed; no further tests/builds, CI monitoring or reruns are requested. The existing PR remains a draft.
