# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working agreements

`AGENTS.md` holds the binding contributor/agent workflow. The parts that are easy to miss:

- **Keep `PROGRESS.md` current.** It is the handoff record (branch, completed stages/commits, decisions, actual test results, unresolved issues, exact next action). Re-read it — plus `AUDIT_REPORT.md` (findings/coverage table) and `docs/VALIDATION.md` (real run evidence) — after any compaction or new session, and reconcile against `git status` and uncommitted diffs before resuming.
- **Commit per focused stage** and update `PROGRESS.md` in the same stage.
- **Local work is pre-authorized** (edits, branches, commits, dependency installs, builds, tests, disposable emulators). **Any GitHub remote mutation needs explicit approval first** — pushes, PRs, comments, merges, releases, settings, triggering/rerunning/cancelling Actions. Note that push and PR events auto-start CI, which counts as a remote action.
- **No physical Samsung device exists here.** Never block on phone testing. Never claim measured energy savings, physical accuracy, or hardware-validated vendor behavior from emulator runs. Distinguish simulated inputs from measurements, estimates from measured values, and unavailable readings from zero.

## Commands

JDK 21, Android SDK platform 37 (`sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0'`). `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`. SDK path goes in untracked `local.properties`.

```sh
./gradlew :app:assembleDebug          # APKs -> app/build/outputs/apk/debug/
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest      # JVM regression tests (JUnit 4)
./gradlew :app:lintDebug              # Android Lint; config in app/lint.xml
```

Single JVM test class or method:

```sh
./gradlew :app:testDebugUnitTest --tests "app.batstats.battery.measurement.ObservationEngineTest"
./gradlew :app:testDebugUnitTest --tests "*ObservationEngineTest.someMethodName"
```

Host-only validation scripts (no device; CI runs all four before Gradle):

```sh
python3 scripts/check_migrations.py         # replays Room migrations on host SQLite vs app/schemas/.../4.json
python3 scripts/check_history_queries.py    # executes the real DAO SQL on host SQLite
python3 scripts/check_resources.py          # translation completeness + format-argument parity (es, tr)
python3 -B -m unittest discover -s scripts -p 'test_*.py'
```

Full pre-push gate, mirroring `.github/workflows/build-apk.yml`:

```sh
./gradlew :app:testDebugUnitTest :app:testPreviewUnitTest :app:lintDebug :app:lintPreview \
          :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest
```

Device tests need an API 36 emulator. The full two-phase device suite (ordinary phase excluding `@RequiresShizuku`, then a real-Shizuku phase after `scripts/prepare_shizuku.py` installs checksum-pinned Shizuku) is:

```sh
ANDROID_SERIAL=emulator-5554 bash scripts/check_android_device.sh              # builds via Gradle
BATSTATS_REPORT_GROUP=16k BATSTATS_EXPECTED_PAGE_SIZE=16384 \
  bash scripts/check_android_device.sh --prebuilt                              # installs already-built APKs
```

The script refuses anything but an `emulator-*` serial on `ranchu`/`goldfish` with `ro.build.version.sdk == 36`, and pipes runner output through `scripts/check_instrumentation_result.py` because `adb` exits 0 even when the runner crashes. Per-phase reports land in `app/build/reports/device-validation/<group>/`. One class directly:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.batstats.ui.NavigationDeviceTest
```

## Architecture

Single Gradle module (`:app`), Kotlin + Compose, Koin DI (`di/AppModules.kt`, started in `BatteryApp`), Room, Navigation 3, DataStore-backed generated settings. `BatteryGraph` is a legacy Koin service locator kept for broadcast receivers; prefer constructor/`by inject()` injection in new code.

### Monitoring pipeline — the core of the app

`BatteryMonitorService` (foreground, `specialUse`, started by the user or `BootReceiver`) drives `BatteryRepository`, which is the **single owner of observation state and history writes**. Everything else reads its `StateFlow`s.

```
BatteryMonitorService ──▶ BatteryRepository ──▶ Channel<Event>(64) single-consumer loop
   ▲   (FGS, alerts,        │  capture() from                 │
   │    notification,       │  poll + SCREEN/POWER/DOZE       ├─▶ ObservationEngine (interval accounting)
   │    widgets)            │  broadcasts                     ├─▶ Room (samples + sessions, one transaction)
   │                        │         │                       └─▶ _observation / _realtime StateFlows
   │                        │         ▼                                      │
   │                        │  StateEventSequencer                           ▼
   └── notification/widgets ┴─ (holds one capture ≤2s awaiting        ViewModels ──▶ Compose screens
                               its confirming system event)
```

Invariants encoded in this pipeline — preserve them when editing:

- **Generation IDs.** Each `startSampling()` mints a UUID `activeGeneration`. Queued samples from a previous generation are dropped, so stop/clear takes effect immediately.
- **Boundaries and gaps.** Every `Observation` carries a `Boundary` (`SAMPLE`/`SCREEN`/`POWER`/`DOZE`/`GAP`). `ObservationEngine.accept` refuses to accumulate an interval and records a gap on clock discontinuity, wall-clock shift, a state change with no matching boundary event, or an over-long *awake* gap. Channel overflow flips the next capture to `Boundary.GAP` rather than inventing continuity.
- **Polling alone never establishes continuity.** If the broadcast receiver is not registered, `captureBattery` updates the live reading and returns without feeding the engine.
- **Elapsed/uptime pairs are read adjacently** so Binder latency does not masquerade as CPU suspend.
- **Sessions are automatic**, derived from observed power-state transitions; `charge_sessions.activeKey` has a unique index so at most one session is active. Interrupted sessions are closed on the next start (`recoverInterruptedSession`).
- **Failures are per-source and visible.** `FailureSource` (BATTERY/STATE_EVENTS/HISTORY/RETENTION/SAMPLE_COUNT) entries merge into `repository.error`; a failed read never silently degrades to another value.

Screen-off includes noninteractive AOD. Screen-off, CPU suspend (`elapsed − uptime`) and Doze are accounted separately and are not interchangeable.

### Privileged access (Shizuku / Root / ADB)

`ShellRunner.Mode` is `SHIZUKU | ROOT | ADB | NONE`, probed in that order with a 10 s cache. **One backend is selected per read and a failure never falls through to another source** — that fallthrough was a fixed defect (AUDIT_REPORT B07), so do not reintroduce it.

- `ShizukuBridge` binds a `ShellUserService` user service; commands are framed by `CommandProtocol` (magic + length + trailing magic) so helper death, truncation and timeout are distinguishable from valid output. Bump `SERVICE_VERSION` when the helper's contract changes.
- ADB mode requires `DUMP` **and** `PACKAGE_USAGE_STATS` **and** an allowed/default `GET_USAGE_STATS` app-op — this mirrors Android 16 `DumpUtils.checkDumpAndUsageStatsPermission` (`PrivilegeChecker`). `BATTERY_STATS` is not a substitute.
- `DetailedStatsCollector` owns advanced snapshots; an access change bumps a generation and clears stale snapshots rather than refreshing their timestamp. `BatteryStatsParser` parses Android **checkin v9** with field offsets from `android16-release` `BatteryStats.java`. `KernelStats`/`RootStatsCollector` read sysfs in documented Linux ABI units only.

`docs/PLATFORM_NOTES.md` records the upstream contracts and AOSP source links behind these rules; check it before "fixing" a parser offset or unit.

### Settings

`settings/SettingsDefinition.kt` declares one `@Serializable data class AppSettings`. The `kmp-settings` KSP processor generates `AppSettingsSchema` and setting metadata from `@Setting` (user-visible, needs `category`/`type`) and `@Persisted` (stored state, no UI) annotations; `@NoReset` survives a reset. Raw index properties are converted by extension vals in the same file (`monitoringIntervalMs`, `chartTimeRangeMs`, `detailedStatsIntervalMs`, `useFahrenheit`) — read those, not the indices. `SettingsText` maps property names to `R.string` ids so stored keys stay stable while displayed text is localized. DataStore schema migrations live in `AppModules.kt` alongside `SCHEMA_VERSION`; automatic backup covers preferences only, never battery history.

### History, import/export, retention

Room DB `battery.db` is at **version 4** with `exportSchema = true` (`app/schemas/`). Migrations are non-destructive and are verified three ways: `DatabaseMigrationTest` (device), `scripts/check_migrations.py` (host SQLite), and the exported schema JSON. Any entity change needs a new `Migration`, a regenerated schema JSON, a fixture update, and the scripts still passing.

`HistoryPolicy` validates and canonicalizes every imported record (unit ranges, coverage consistency, CSV-injection-safe text, `import:` id prefixes, SHA-256-derived negative ids for dedup) — **the whole file is validated before any write**, imports never resume as live observations, and `HistoryMaintenance` gates clears against the sample writer. Limits: 64 MiB files, 100,000 samples, 10,000 sessions.

### UI

Navigation 3: `Screen` is a `@Serializable sealed interface : NavKey`; `MainScreen` owns the `rememberNavBackStack` and `NavGraph` maps keys to screens via `entryProvider`. `NavDisplay` owns system/predictive back. ViewModels are Koin-provided (`koinViewModel()`), expose `StateFlow`, and delegate all mutation to the repository.

Presentation text that must be unit-testable lives in plain classes taking a `(Int, Array<out Any>) -> String` resolver — `MonitoringText` (shared by the dashboard and the monitoring notification) is the model. JVM tests feed it `support/EnglishStrings`, which parses the real `values/strings.xml`, so string changes are covered without an Android runtime.

`DiagnosticLog`/`DiagnosticStore` accept only fixed `DiagnosticCode` enum values — commands, package names and exception messages must never enter the log.

## Domain rules that reviews enforce

- **Missing ≠ zero.** Readings are nullable; `Long.MIN_VALUE` from `BatteryManager` means unsupported, and unsupported must stay `null` through the DB, UI and exports.
- **Units live in the names**: `...Ua` (µA, positive = charging), `...Uah` (µAh), `...Nwh` (nWh), `voltageMv`, `temperatureDeciC`, `...Ms`. `BatteryReading` validates ranges; never infer a vendor scale from a value's magnitude.
- **No invented constants.** No fixed battery capacity, no cycle-count-derived "health" percentage, no per-app mA heuristics. Estimates must state their basis (`etaBasis`) and coverage (`chargeCoveredMs` vs `durationMs`).
- Comments in this codebase explain *why* a constraint exists (a platform contract, a race, an audit finding). Preserve them when refactoring; they are the rationale record.

## Tests

`app/src/test/` is JVM/JUnit 4 and covers the measurement, parsing, policy and text classes (deliberately free of Android dependencies). `app/src/androidTest/` needs a device; every device test calls `DeviceEnvironment.requireDisposableEmulator()`, which asserts package `org.mlm.batstats.debug`, emulator hardware and API 36. `@RequiresShizuku` marks tests that need real Shizuku installed and separates the second device phase. Screenshots are captured to app-private storage and republished through the test shell by `DeviceEnvironment.screenshot`.

Mirror source packages, name files `*Test.kt`. For a fix, reproduce the behavior first and record device/API, privilege mode (standard, ADB, Root, Shizuku) and the result in `docs/VALIDATION.md`.

## Conventions

- Official Kotlin style, four-space indent. PascalCase composables/classes (`DashboardScreen`), `*Screen.kt` / `*ViewModel.kt` / `*Widget.kt`, snake_case resources. Dependency versions only in `gradle/libs.versions.toml`.
- Commit subjects are imperative with a conventional prefix (`fix:`, `test:`, `ci:`, `docs:`, `chore(deps):`).
- New user-facing strings go in `res/values/strings.xml` **and** in `values-es` + `values-tr` (both must stay 1:1; `check_resources.py` fails otherwise). A new locale also needs `androidResources.localeFilters` and the script's locale list. See `docs/LOCALIZATION.md`.
- Build types: `debug` (`org.mlm.batstats.debug`), `preview` (release-like, `org.mlm.batstats.preview`, optional `PREVIEW_*` env signing), `release` (`org.mlm.batstats`, `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`). The three packages never share data or grants; the original release key is not available here, so no build in this branch can update a store installation in place. Never commit keystores. When adapting README/ADB permission commands, use the debug package id.
