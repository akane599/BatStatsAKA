# BatStats Audit Report

Baseline: `76bc831328572c81717b97ffb0e280b10b14b8ad`; supplied APK6.2.6/code735 differs from source code734. Execution handoff: [PROGRESS.md](PROGRESS.md). Findings describe confirmed source paths unless execution evidence is stated.

## Coverage
- [x] Build/dependencies/manifest and CI/release source reviewed; workflow fixes and final delivery pending.
- [x] Monitoring lifecycle, access modes, transport/security, measurements and session calculations reviewed; runtime checks pending.
- [x] Room/migrations/import/export/clear/retention source reviewed; Android execution pending.
- [x] Advanced collectors, Android16 producer layout, UID attribution and kernel ABI reviewed; real device outputs pending.
- [x] ViewModels/navigation/screen layouts/theme colors and widget/background paths reviewed; remaining fixes below.
- [ ] Complete localization/resource/store-asset review, actual screens, accessibility and error/recovery interactions.
- [ ] Android16/Shizuku instrumentation, visual validation and final debug/nondebug APK delivery.

## Confirmed findings and status

| ID | Priority | Evidence / affected area | Resolution / remaining verification |
| --- | --- | --- | --- |
| B01 | High | `BatteryRepository`: missing level becomes 0; MIN_VALUE current survives fallback; valid zero replaced by average; power/current widgets inherit invalid readings.  Fixed; JVM regression coverage; device validation pending |
| B02 | High | `AdvancedDrainTracker.isInDeepSleep`: total elapsed−uptime since boot >30s labels every later screen-off snapshot deep sleep; Doze time incorrectly used as CPU sleep.  Fixed; separate observed CPU/Doze accounting tested |
| B03 | High | Drain tracker: end-of-interval screen attribution, racing receiver/poll jobs, wall-clock durations, charge time included in denominator, reset leaves prior snapshot, 4000 mAh fallback creates fictional consumption.  Fixed; monotonic observation/screen/gap/coverage tests pass |
| B04 | High | Repository auto-session logic is a TODO; manual starts allow multiple active sessions; no reboot/gap boundaries; all-null current average converts NaN to 0.  Fixed; shared writer and DB4 active uniqueness; Room device tests pending |
| B05 | High | `TimeEstimator` assumes 4000 mAh and instantaneous current; UI invents health percentage from cycle-count thresholds.  Fixed; stable counter ETA, no fixed capacity or cycle-derived health |
| B06 | High | Android manifest/service uses `dataSync` for indefinite monitoring (Android 15+ six-hour limit); boot coroutine not protected by goAsync; privileged mode only chosen at startup.  Implemented; API36 service/boot runtime tests pending |
| B07 | High | `ShellRunner` silently falls through Root→Shizuku→ADB; helper reports partial timeout/nonzero-exit output as success, truncates output; inline timeout occurs after blocking read.  Implemented; bounded transport/cancellation JVM tests pass; Binder runtime pending |
| B08 | High | `DetailedStatsCollector`: successful power/deviceidle read hides failed batterystats and refreshes timestamp of stale snapshot; failed subreads retain previous data.  Partly fixed; collector generations/stale snapshots handled; notification error propagation pending |
| B09 | High | `BatteryStatsParser`: jobs/sync count/time swapped per Android 16 BatteryStats.java; Doze idling fields mislabeled as maintenance; app detail fields never populated but displayed as zero.  Fixed against Android16 producer; synthetic parser regressions pass |
| B10 | High | `BstatsCollector`: newly seen UID uses zero prior counter (attributes pre-observation usage); missing epoch/backend boundaries; retries every 5s on failure; duplicate dumps across collectors.  Removed unused duplicate writer; detailed UID collector preserved |
| B11 | High | `ForegroundDrainTracker`: arbitrary 80/20 mA subtraction, current charged attributed to last foreground app, ignores pause/stop and observation gaps; setting not consulted.  Removed unsupported heuristic; detailed UID reports preserved; unused setting pending |
| B12 | High | Room destructive fallback loses history; imports nontransactional/unbounded and duplicate sample IDs reset; active imported sessions overwrite local state; exports ignore session range.  Implemented; bounded transactional imports/export, nondestructive migrations; JVM/host SQL pass, Android execution pending |
| B13 | High | Settings clear-all calls Room blocking API on main thread; service may continue writing during deletion.  Implemented; serialized clear/start gate and cancellation tests pass; UI runtime pending |
| B14 | Medium | History `SessionCard` internal empty click handler consumes navigation; detail/export screens lack vertical scrolling; six dashboard actions crowd toolbar; charts replace missing with zero and lack axes/time.  Partly fixed; dashboard/drain/advanced/export/session details revised; remaining history/accessibility/visual review pending |
| B15 | Medium | Monitoring notification rebuild resets timestamp; settings/alarms/retention largely unused; widget IPC on every sample even no installed widgets.  Partly fixed; stable notification/collection, bounded retention; alerts/settings/widgets pending |
| B16 | Medium | Root-only collectors read files as app UID, not su; unsupported devices appear empty/zero; battery capacity advertised as true/exact.  Implemented; actual su reads and validated kernel parsers; vendor/root hardware unverified |
| B17 | Medium | Release workflow has stale application-id `app.batstats`, unused upload inputs, default production publishing; no local test/report pipeline.  Open; local manual build/report workflow and corrected release configuration pending |
| B18 | High | Alpha Compose requires SDK37.1 while project declared37.0. | Fixed with stable Compose BOM; metadata and app compilation pass. |
| B19 | High | ADB dump gate incorrectly modeled DUMP/PACKAGE_USAGE_STATS/AppOps. | Fixed to Android16 producer contract; actual device grants pending. |
| B20 | High | `--checkin` may consume a completed saved report. | Fixed to current non-consuming `-c --charged`; source/window remains explicit. |
| B21 | Medium | Default automatic backup included history databases. | Fixed manifest and both backup-rule generations: settings DataStore only; XML checked, OEM transfer unverified. |
| B22 | Medium | New advanced UI strings lack locale translations. | Open: last lint125 MissingTranslation errors/216 warnings. More new strings now added; real translations required. |
| B23 | Medium | Dark onSecondary#3B1F70 over secondary#8B5CF6 has calculated contrast3.07:1. | Open: fix normal-text contrast and inspect actual screens. |
| B24 | Medium | Local chart query included imported/legacy readings without a matching source label. | Fixed local query to BatteryManager records; host SQL passes, Android regression compiled. Imported legacy detail labels still pending. |
| B25 | Medium | Widgets lack freshness/stopped labels; absent in-memory sample leaves stale/blank display. | Open: update lifecycle/empty/freshness behavior and temperature preference. |

## Actual validation
- Pristine dependency metadata failed in57s: alpha UI requires SDK37.1. Stable BOM metadata passes with compile37/target36/min26.
- Parser baseline reproduced12 new failures; fixed producer-based suite subsequently passed. Stage3d51 JVM tests passed; lint failed125 missing translations/216 warnings. API28/min26 charge-time error was corrected; lint translation errors are not suppressed.
- **Stage4a rerun passed in2m6s:** `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`;63 JVM tests,0fail/error/skip. Includes12 new history identity/units/coverage/CSV/JSON limits and clear-race/cancellation tests. Android migration/import test sources compile, not executed.
- First stage4a run:63 JVM tests passed but Android tests did not compile because RoomDatabase is not Closeable. Fixed test cleanup with explicit try/finally; no assertions removed/weakened.
- `python3 scripts/check_migrations.py`: actual SQL1/2/3→4 matches exported schema/indices, preserves rows and sanitizes sentinels/active state. `python3 scripts/check_history_queries.py`: actual DAO SQL verifies chart provenance, positive local IDs after negative imported IDs, duplicate-point transaction rollback preserving existing rows, overlapping periods and bounded sessions retaining active state. Both pass on host SQLite, not Android.
- All76 Android XML files parse; explicit backup includes only settings DataStore; `git diff --check` passes.
- Latest assembled APK is stage3b, older than current source. Final lint, debug and nondebug builds remain required.

## Device/environment limits
Android16 ATD software emulator has no KVM. Initial framework repeatedly hit watchdog; documented timeout multiplier100 allowed one verified boot completion before user pause. Baseline install was interrupted; installation status unknown. Resumed emulator boot plus build exhausted3.8GiB RAM/4GiBswap; only task emulator stopped. **Run builds and emulator checks sequentially.** No app UI screenshots/instrumentation/Shizuku runtime success claimed. No physical Samsung hardware available or requested. Emulator sensor values are injected; they cannot establish current calibration, capacity accuracy, Samsung AOD/lifecycle or energy savings.

## Next work and unresolved suspicions
- Stage4b: meaningful alerts/settings, collector error propagation, rich stable notification consistency/navigation, local bounded diagnostics, widget freshness and remaining monitoring efficiency.
- Stage4c: complete translations/resource extraction, history pagination/legacy states, color contrast and all-screen responsive/accessibility polish.
- Stage5: API36/Shizuku/Binder/UI tests as environment supports, debug/nondebug APKs/signing guidance, locally prepared manual build workflow/reports/PR description. GitHub writes/Actions require explicit approval; none performed.
- Samsung vendor current direction/units, kernel/sysfs permissions/formats, actual reconnection/cancellation and One UI process management remain unverified. Missing data stays distinguishable from zero.
- Unused helper/constants/system-UI utilities are low-risk technical debt, not grounds for cleanup-only changes. User-requested UI enhancements are authorized independently of audit defects.

## Primary platform references consulted
- [BatteryManager units, sentinel values and charge-time approximation](https://developer.android.com/reference/android/os/BatteryManager)
- [PowerManager interactive state and ambient display](https://developer.android.com/reference/android/os/PowerManager#isInteractive())
- [Foreground service types and specialUse declaration](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Samsung sleeping/deep sleeping app restrictions](https://www.samsung.com/us/support/answer/ANS10003442/)
- [Shizuku API lifecycle and UserService](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)
- [Android 16 checkin producer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/os/BatteryStats.java), local reference `/tmp/batstats-BatteryStats-android16.java`.

Additional sources/contracts and privacy constraints: [docs/PLATFORM_NOTES.md](docs/PLATFORM_NOTES.md). Do not treat source review or emulator success as proof of physical battery accuracy.
