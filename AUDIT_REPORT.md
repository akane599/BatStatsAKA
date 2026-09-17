# BatStats Audit Report

Baseline: `76bc831328572c81717b97ffb0e280b10b14b8ad`. Durable execution handoff: `PROGRESS.md`.

## Coverage
- [ ] Build, dependencies, manifest, CI/release, documentation/assets
- [ ] Monitoring lifecycle, access modes, Root/Shizuku/shell security
- [ ] Battery readings, calculations, estimates, session/screen accounting
- [ ] Room persistence, retention, resets, import/export
- [ ] Advanced collectors, parsers, per-app attribution and reporting windows
- [ ] ViewModels, dashboard, navigation, charts, history, settings and themes
- [ ] Notifications, widgets, alarms and background overhead
- [ ] Automated tests, Android 16 interactions/screenshots and APK delivery

## Findings
All below confirmed by direct execution path/source inspection; fixes pending unless stated.

| ID | Priority | Evidence and affected area |
| --- | --- | --- |
| B01 | High | `BatteryRepository`: missing level becomes 0; MIN_VALUE current survives fallback; valid zero replaced by average; power/current widgets inherit invalid readings. |
| B02 | High | `AdvancedDrainTracker.isInDeepSleep`: total elapsed−uptime since boot >30s labels every later screen-off snapshot deep sleep; Doze time incorrectly used as CPU sleep. |
| B03 | High | Drain tracker: end-of-interval screen attribution, racing receiver/poll jobs, wall-clock durations, charge time included in denominator, reset leaves prior snapshot, 4000 mAh fallback creates fictional consumption. |
| B04 | High | Repository auto-session logic is a TODO; manual starts allow multiple active sessions; no reboot/gap boundaries; all-null current average converts NaN to 0. |
| B05 | High | `TimeEstimator` assumes 4000 mAh and instantaneous current; UI invents health percentage from cycle-count thresholds. |
| B06 | High | Android manifest/service uses `dataSync` for indefinite monitoring (Android 15+ six-hour limit); boot coroutine not protected by goAsync; privileged mode only chosen at startup. |
| B07 | High | `ShellRunner` silently falls through Root→Shizuku→ADB; helper reports partial timeout/nonzero-exit output as success, truncates output; inline timeout occurs after blocking read. |
| B08 | High | `DetailedStatsCollector`: successful power/deviceidle read hides failed batterystats and refreshes timestamp of stale snapshot; failed subreads retain previous data. |
| B09 | High | `BatteryStatsParser`: jobs/sync count/time swapped per Android 16 BatteryStats.java; Doze idling fields mislabeled as maintenance; app detail fields never populated but displayed as zero. |
| B10 | High | `BstatsCollector`: newly seen UID uses zero prior counter (attributes pre-observation usage); missing epoch/backend boundaries; retries every 5s on failure; duplicate dumps across collectors. |
| B11 | High | `ForegroundDrainTracker`: arbitrary 80/20 mA subtraction, current charged attributed to last foreground app, ignores pause/stop and observation gaps; setting not consulted. |
| B12 | High | Room destructive fallback loses history; imports nontransactional/unbounded and duplicate sample IDs reset; active imported sessions overwrite local state; exports ignore session range. |
| B13 | High | Settings clear-all calls Room blocking API on main thread; service may continue writing during deletion. |
| B14 | Medium | History `SessionCard` internal empty click handler consumes navigation; detail/export screens lack vertical scrolling; six dashboard actions crowd toolbar; charts replace missing with zero and lack axes/time. |
| B15 | Medium | Monitoring notification rebuild resets timestamp; settings/alarms/retention largely unused; widget IPC on every sample even no installed widgets. |
| B16 | Medium | Root-only collectors read files as app UID, not su; unsupported devices appear empty/zero; battery capacity advertised as true/exact. |
| B17 | Medium | Release workflow has stale application-id `app.batstats`, unused upload inputs, default production publishing; no local test/report pipeline. |

Suspicions needing tests: Samsung vendor current sign/units, provider/binder reconnection behavior, platform-specific checkin formats and sysfs accessibility. No real Samsung available.

## Validation
Baseline compile completed but dependency metadata check failed; remaining D8 work stopped and pristine metadata-only reproduction started. Stage 2 code now being implemented, not yet verified. APK signature/manifest inspected; see PROGRESS.md hashes. Default emulator creation hit disk requirement; recovery in progress. No physical Samsung hardware available.

## Work queue
Establish baseline build and trace collection → persistence → calculations → UI/notification. Prioritize confirmed correctness/lifecycle defects; implement explicitly requested UX and delivery improvements.

## Primary platform references consulted
- [BatteryManager units, sentinel values and charge-time approximation](https://developer.android.com/reference/android/os/BatteryManager)
- [PowerManager interactive state and ambient display](https://developer.android.com/reference/android/os/PowerManager#isInteractive())
- [Foreground service types and specialUse declaration](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Samsung sleeping/deep sleeping app restrictions](https://www.samsung.com/us/support/answer/ANS10003442/)
- [Shizuku API lifecycle and UserService](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)
- [Android 16 checkin producer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/os/BatteryStats.java), local reference `/tmp/batstats-BatteryStats-android16.java`.

## Architecture/coverage detail
Read critical source in full: repository, entities/DAOs/database, export/import, service/boot/app/activity, all ViewModels, Shizuku helper/bridge/checkin collector, ShellRunner/RootStatsCollector/PrivilegeChecker/TimeEstimator/DetailedStatsCollector/BatteryStatsParser, drain tracker/state/notification, heuristic tracker, widgets. Reviewed build/manifest/workflows/settings and screen entry/data flows. Remaining detailed UI layouts/theme resources/localizations and assets are explicitly pending, together with runtime validation.

### Stage 2 work in progress
B06/B07/B08: specialUse FGS, goAsync boot, no source fallthrough on read errors, bounded framed helper output and partial-failure reporting implemented but unverified. B18 (High): baseline alpha Compose BOM UI artifacts require compile SDK 37.1, project has 37.0; switching to stable BOM whose UI AAR requires 37.0. Pristine metadata-only reproduction failed in 57s with five SDK 37.1 requirements; updated stable BOM metadata check passed. JVM compilation/tests pending. Stable Material 3 replaces unavailable experimental wavy indicators/theme. Read deadline now covers inherited pipes as well as parent-process timeout; 8 regression tests authored, not yet passed. All other B findings remain open.

Static validation: parsed all 76 Android XML files successfully after manifest changes; `git diff --check` passes. This is not runtime or UI validation.

### Stage 3a measurement model
Added pure validated Android-unit readings, monotonic observation accounting and stable counter-based ETA model. Tests cover zero vs missing, units/sign, screen transitions, no screen-off interval, charging denominators, reset/reboot/clock/gaps, CPU suspend vs Doze, counter resets and unstable ETA. All 28 current pure JVM tests PASS (Kotlin 2.4.20/JUnit4). B01–B05 remain open until these models replace existing app calculations. Earlier Gradle test reproduced old command-pipe hang; current read-deadline implementation passes the same regression test. Android service/binder runtime verification remains pending.

### Stage 3b integration (uncommitted; validation pending)
B01–B04: normalized nullable readings feed one bounded serialized observation/persistence path; new DB4 metadata/unique active session/migrations1–4; UI and notification share screen/counter coverage. B05: removed fixed4000 ETA; cycle-derived health in advanced UI still open. B10/B11: removed unused duplicate hourly writer and unsupported per-app current-minus-baseline tracker; existing detailed UID statistics retained. B14/B15: dashboard/drain/notification and timestamp/gap-aware charts rewritten; no emulator visual validation yet. Migration tests1/2/3→4 authored. Earlier integration build failed on stale KSP adapter after DAO edit during build; rerun currently in progress. No complete APK/lint success yet.

Remaining work: advanced producer-backed parser/UID attribution/source windows; root helper reads; imports/export/reset integrity; alarms/unused settings/retention bounds; local bounded diagnostics; resource extraction and all-screen/accessibility review; integration tests and actual emulator/Shizuku behavior; debug/non-debug APKs/signing/workflow/PR delivery. Emulator ADB online but framework services absent: baseline install failed, no app runtime validation yet.

Stage 3b verification: Gradle JVM tests + instrumentation-source compilation PASS in5m35s:29 JVM tests,0 failures,0 skipped. Host SQLite migrations1/2/3→4 match Room-exported schema/indices/PKs and preserve rows while sanitizing unavailable sentinels; active uniqueness enforced. Android migration execution and UI still unverified.

New confirmed findings (priority high; next fixes):
- B19: Android16 batterystats dump requires DUMP and PACKAGE_USAGE_STATS permission plus allowed/default usage app-op. Original OR guard and current DUMP/BATTERY_STATS AND guard do not model this contract correctly. Correct the introduced restrictive gate before delivery.
- B20: `dumpsys batterystats --checkin` prefers a saved completed snapshot and deletes that saved file after reading, so repeated reads can cover different windows and consume Android's checkin. Use current non-consuming `-c` output with bounded handling of included history, or a supported replacement; do not silently mix epochs.
Sources: [BatteryStatsService Android16](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/BatteryStatsService.java) (`dumpUnmonitored`, help and checkin file paths), [DumpUtils permissions](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/com/android/internal/util/DumpUtils.java).
