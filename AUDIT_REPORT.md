# BatStats Audit Report

Baseline: `76bc831328572c81717b97ffb0e280b10b14b8ad`; branch `codex/android16-reliability`. Supplied APK6.2.6/code735 matches APK-dist's universal +1 offset for source734; that difference does not imply a different source revision. [PROGRESS.md](PROGRESS.md) is the execution handoff; [VALIDATION.md](docs/VALIDATION.md) retains actual checks, failures and commands.

## Coverage
- [x] Build/dependencies/manifest, permissions, backup, CI/release source and store claims reviewed; local workflow/build fixes prepared.
- [x] Monitoring lifecycle/access/transport/security, measurements and session calculations reviewed and revised.
- [x] Room/migrations/import/export/clear/retention reviewed; host SQL and synthetic regressions run.
- [x] Advanced collectors, Android16 producer layout, UID attribution and kernel ABI reviewed; vendor outputs unverified.
- [x] ViewModels/navigation/layout/theme/widget/alert/settings/resource source reviewed; locale inventory corrected by comparing actual baseline values.
- [ ] Actual Android16 screens, accessibility, loading/empty/partial/error/recovery and notification/widget interactions.
- [ ] Real Shizuku Binder integration, final lint and final debug/nondebug APK delivery.

## Confirmed findings

Source review confirms these defects unless runtime reproduction is stated. Implemented fixes are not a claim of complete hardware validation.

| ID | Priority | Baseline evidence / affected area | Fix and verification status |
| --- | --- | --- | --- |
| B01 | High | `BatteryRepository`: missing level becomes 0; MIN_VALUE current survives fallback; valid zero replaced by average; power/current widgets inherit invalid readings. | Fixed; JVM regression coverage; device validation pending |
| B02 | High | `AdvancedDrainTracker.isInDeepSleep`: total elapsed−uptime since boot >30s labels every later screen-off snapshot deep sleep; Doze time incorrectly used as CPU sleep. | Fixed; separate observed CPU/Doze accounting tested |
| B03 | High | Drain tracker: end-of-interval screen attribution, racing receiver/poll jobs, wall-clock durations, charge time included in denominator, reset leaves prior snapshot, 4000 mAh fallback creates fictional consumption. | Fixed; monotonic observation/screen/gap/coverage tests pass |
| B04 | High | Repository auto-session logic is a TODO; manual starts allow multiple active sessions; no reboot/gap boundaries; all-null current average converts NaN to 0. | Fixed; shared writer and DB4 active uniqueness; Room device tests pending |
| B05 | High | `TimeEstimator` assumes 4000 mAh and instantaneous current; UI invents health percentage from cycle-count thresholds. | Fixed; stable counter ETA, no fixed capacity or cycle-derived health |
| B06 | High | Android manifest/service uses `dataSync` for indefinite monitoring (Android 15+ six-hour limit); boot coroutine not protected by goAsync; privileged mode only chosen at startup. | Implemented; API36 service/boot runtime tests pending |
| B07 | High | `ShellRunner` silently falls through Root→Shizuku→ADB; helper reports partial timeout/nonzero-exit output as success, truncates output; inline timeout occurs after blocking read. | Implemented; bounded transport/cancellation JVM tests pass; Binder runtime pending |
| B08 | High | `DetailedStatsCollector`: successful power/deviceidle read hides failed batterystats and refreshes timestamp of stale snapshot; failed subreads retain previous data. | Implemented; collector errors reach notification; stale snapshots/generations handled; device recovery pending |
| B09 | High | `BatteryStatsParser`: jobs/sync count/time swapped per Android 16 BatteryStats.java; Doze idling fields mislabeled as maintenance; app detail fields never populated but displayed as zero. | Fixed against Android16 producer; synthetic parser regressions pass |
| B10 | High | `BstatsCollector`: newly seen UID uses zero prior counter (attributes pre-observation usage); missing epoch/backend boundaries; retries every 5s on failure; duplicate dumps across collectors. | Removed unused duplicate writer; detailed UID collector preserved |
| B11 | High | `ForegroundDrainTracker`: arbitrary 80/20 mA subtraction, current charged attributed to last foreground app, ignores pause/stop and observation gaps; setting not consulted. | Removed unsupported heuristic; detailed UID reports preserved; ineffective setting retired in4b1 |
| B12 | High | Room destructive fallback loses history; imports nontransactional/unbounded and duplicate sample IDs reset; active imported sessions overwrite local state; exports ignore session range. | Implemented; bounded transactional imports/export, nondestructive migrations; JVM/host SQL pass, Android execution pending |
| B13 | High | Settings clear-all calls Room blocking API on main thread; service may continue writing during deletion. | Implemented; serialized clear/start gate and cancellation tests pass; UI runtime pending |
| B14 | Medium | History `SessionCard` internal empty click handler consumes navigation; detail/export screens lack vertical scrolling; six dashboard actions crowd toolbar; charts replace missing with zero and lack axes/time. | Implemented navigation, responsive/scrollable screens, bounded charts and missing-data states; actual visual review pending. |
| B15 | Medium | Monitoring notification rebuild resets timestamp; settings/alarms/retention largely unused; widget IPC on every sample even no installed widgets. | Implemented stable rich notification, alert/settings behavior, bounded retention and no widget work when absent; delivery/runtime pending. |
| B16 | Medium | Root-only collectors read files as app UID, not su; unsupported devices appear empty/zero; battery capacity advertised as true/exact. | Implemented; actual su reads and validated kernel parsers; vendor/root hardware unverified |
| B17 | Medium | Release workflow had stale application-id, ignored publication inputs and production defaults. | Implemented locally in5a: separate read-only manual APK workflow, reports/signatures, release gate/correct ID/defaults. actionlint/build checks pass; hosted execution requires approval |
| B18 | High | Alpha Compose requires SDK37.1 while project declared37.0. | Fixed with stable Compose BOM; metadata and app compilation pass. |
| B19 | High | ADB dump gate incorrectly modeled DUMP/PACKAGE_USAGE_STATS/AppOps. | Fixed to Android16 producer contract; actual device grants pending. |
| B20 | High | `--checkin` may consume a completed saved report. | Fixed to current non-consuming `-c --charged`; source/window remains explicit. |
| B21 | Medium | Default automatic backup included history databases. | Fixed manifest and both backup-rule generations: settings DataStore only; XML checked, OEM transfer unverified. |
| B22 | Medium | New advanced UI strings lack locale translations. | Baseline locale files proved entirely English duplicates. Consolidated fallback and complete Spanish/Turkish translations in208d9b8. XML/AAPT2 checks pass; full lint and visual review pending. |
| B23 | Medium | Dark onSecondary#3B1F70 over secondary#8B5CF6 had contrast3.07:1. | Fixed palettes; normal/fixed text and outline contrast regressions pass. Actual screens pending. |
| B24 | Medium | Local chart query included imported/legacy readings without source labels. | Fixed local query and legacy/import detail evidence labels; host SQL passes, Android regressions compile. Device rendering pending. |
| B25 | Medium | Widgets lack freshness/stopped labels; absent in-memory sample leaves stale/blank display. | Implemented in4b2; snapshot/empty/freshness/stopped/temperature handling; actual widget rendering pending. |
| B26 | Medium | Settings renderer used generated English metadata/resource IDs0; labels bypassed string resources. | Fixed resource-backed titles, descriptions and options. Full Spanish/Turkish resources added; device localization test pending. |
| B27 | High | Plugged/non-charging normalization ignored explicit Android DISCHARGING status, excluding connected discharge. | Corrected status handling; synthetic contradictory/connected cases pass. |
| B28 | Medium | History exposed only100 rows; details loaded unbounded raw samples and reused live/old data after deletion. | Paged queries, bounded linked charts and explicit missing/loading/legacy states; hostSQL/JVM pass, device pending. |
| B29 | Medium | Settings0.8.3 import bypassed dropdown/slider ranges and accepted unbounded payloads; UI hid errors. | Bounded prevalidation and inline feedback; JVM passes, actual import dialog pending. |
| B30 | Medium | Queued SQL publication could overwrite newer live captures, including distinct captures within one millisecond. | Full captured-input matching before applying persisted metadata; JVM regression passes. |
| B31 | Medium | Shared error field retained recovered battery failures but erased event-subscription failure after SQL success; polling exceptions escaped and failed startup SQL prevented observation reset. | Source-specific errors, safe capture, paused observations without state events, bounded retry and startup recovery in454a156. Four Android regressions compile; runtime pending. |

## Additional verified corrections
- Fractional Android capacity estimates no longer truncate to whole mAh; invalid/nonintegral discharge fields and nonfinite values are rejected.
- Monitoring notification/widget IPC failures no longer terminate their update coroutine. Alert episode latches recover after delivery failure; channel setup happens only on delivery attempts.
- Default backup contains settings only. Diagnostics use fixed codes, bounded local AtomicFile storage, coalesced writes and deliberate sharing; no raw dumps or identifiers are exported in a diagnostic report.
- Preview is optimized/nondebuggable with a separate package and explicit signing configuration. Removed unused direct WorkManager/navigation/constraint/browser dependencies and unused permissions after checking callers; Shizuku provider protection remains.
- Existing locale folders were162 English duplicates each, not translated support. Consolidation preserves English fallback.493 Spanish and493 Turkish resources are complete; format/XML/AAPT2 checks pass. No native-speaker review or translated-screen success is claimed.

## Actual validation
- Baseline dependency metadata failed: alpha Compose required SDK37.1. Stable Compose fixes compilation against37 with target36/min26.
- Producer-based parser tests reproduced12 baseline failures before fixes. Latest JVM suites each pass94 tests in Debug and Preview,0fail/error/skip. Host migration/history SQL checks pass. These inputs are synthetic, not physical measurements.
- Stage5a built Debug, optimized nondebug Preview and test APKs; signatures/manifest verified. Stage5b rebuilt Debug/test APKs including device suites. These APKs predate stage4e locale changes and the pending dashboard recovery test.
- Android tests compile through stage5b but none have executed. Additional dashboard error/recovery test is authored and awaits compilation. Device suites cover repository recovery, actual screen/FGS/notification transitions, UI navigation/font200 and real Shizuku authorization/helper/server recovery.
- Last full lint failed125 MissingTranslation errors/216 warnings before resource corrections. Full rerun required; errors are not suppressed. Fatal release lint, actionlint and direct resource compilation have passed, but are not substitutes for full lint.

## Environment and unresolved verification
The host has no KVM and3.8GiB RAM plus4GiB swap. Builds and emulators must run sequentially. The initial ATD booted once with watchdog multiplier100; installation was interrupted. ATD omits SystemUI, so it cannot validate the notification shade. Its disposable AVD was removed to reclaim disk, keeping logs. Full API36 google_apis is booting on5580 at384×800/dpi160 with enforced2560MiB RAM, sparse6GiB userdata and software rendering. Watchdog100 and shell UID2000 verified; Package Manager and completed boot are not yet available.

No current app screenshot, Android test execution, Binder success or widget delivery is claimed. No physical Samsung device is available or requested. Current polarity/scaling, fuel-gauge calibration, real AOD transitions, OEM background restrictions, root/vendor kernel paths and physical energy overhead remain unverified. Neither source changes nor emulator results establish battery-saving percentages.

## Remaining work
1. Finish emulator initialization and baseline APK inspection; run meaningful source/device checks without Gradle competing for memory. Capture actual screens and investigate failures.
2. Compile the pending dashboard recovery test and locale resources; full Debug/Preview lint and final assemblies/JVM/device checks. Preserve failures and limitations honestly.
3. Complete visual/accessibility review and actual screenshot artifacts; finalize PR description, signing/artifact metadata and delivery records.
4. Only then request explicit approval for exact GitHub actions and automatic triggers. No GitHub write/Actions run is approved or performed.

Unused helpers and general cleanup are independent low-priority opportunities, not a reason to expand the change. Additional per-UID component transport would need its own validated schema/window; current activity counters cannot support arbitrary energy attribution.

## Primary platform references consulted
- [BatteryManager units, sentinel values and charge-time approximation](https://developer.android.com/reference/android/os/BatteryManager)
- [PowerManager interactive state and ambient display](https://developer.android.com/reference/android/os/PowerManager#isInteractive())
- [Foreground service types and specialUse declaration](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Samsung sleeping/deep sleeping app restrictions](https://www.samsung.com/us/support/answer/ANS10003442/)
- [Shizuku API lifecycle and UserService](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)
- [Android 16 checkin producer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/os/BatteryStats.java), local reference `/tmp/batstats-BatteryStats-android16.java`.

Additional sources/contracts and privacy constraints: [docs/PLATFORM_NOTES.md](docs/PLATFORM_NOTES.md). Do not treat source review or emulator success as proof of physical battery accuracy.
