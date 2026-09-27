# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Orchestration policy

You are the orchestrator. Do not implement unless I say "implement in this session".

# Durable project progress

- Read the root `PROGRESS.md` at the start of a session and after context compaction, before planning, delegating, or changing code. Treat it as the project's handoff record; verify branch/file state before acting on potentially stale entries.
- Keep `PROGRESS.md` current after meaningful milestones, changed requirements, confirmed findings, build results, blockers, and before handing work back. Update it during the task, not only at the end of a long conversation.
- Record the latest agreed scope, current branch/commit and uncommitted work, active worker ownership, completed versus pending work, important decisions, reproducible error details, exact build commands/results, skipped checks, APK location, and concrete next actions.
- Clearly separate confirmed facts from hypotheses. Never mark a pending worker's work complete, an unrun check passing, or a phone-specific bug fixed based only on build-host success.
- Preserve the user's latest validation preferences; do not restart already completed work or add test/review loops merely because context was compressed.
- Coordinate progress-file edits between parent and workers. Keep it a concise current-state summary with short milestone history, not a raw transcript. Replace stale entries rather than accumulating contradictory status notes.
- Never put credentials, signing keys, tokens, or sensitive device/network identifiers in `PROGRESS.md`. Do not overwrite unrelated user changes, auto-commit, or auto-push merely to update the progress record.

# Build and test

JDK 21, Android SDK platform `android-37.0` + build-tools 36.0.0 (compileSdk 37, targetSdk 36, minSdk 26). SDK path comes from `ANDROID_HOME` or an untracked `local.properties`. Single Gradle module `:app`.

```sh
# Host checks, in the order CI runs them
python3 scripts/check_migrations.py        # Room migrations vs exported schema, on host SQLite
python3 scripts/check_history_queries.py   # executes the actual DAO SQL on host SQLite
python3 scripts/check_resources.py         # translation completeness + format arguments
python3 -B -m unittest discover -s scripts -p 'test_*.py'   # device-runner orchestration tests (no device)
./gradlew :app:testDebugUnitTest :app:testPreviewUnitTest :app:lintDebug :app:lintPreview
./gradlew :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest
python3 scripts/collect_apks.py            # universal APKs + public signing metadata into artifacts/

# Single JVM test class / method
./gradlew :app:testDebugUnitTest --tests 'app.batstats.battery.measurement.ObservationEngineTest'
./gradlew :app:testDebugUnitTest --tests 'app.batstats.battery.measurement.ObservationEngineTest.chargingUsesCounterGainWithoutCreatingDischarge'

# Single instrumentation class (connected device/emulator)
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.batstats.ui.NavigationDeviceTest
```

- CI build flags (`build-apk.yml`): `--no-daemon --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx3g`. `gradle.properties` enables the configuration cache locally.
- Full device validation: `ANDROID_SERIAL=emulator-5554 bash scripts/check_android_device.sh`. It runs the ordinary phase, downloads and installs checksum-pinned Shizuku 13.6 (`scripts/prepare_shizuku.py`, needs network) as shell, then runs the real Shizuku phase; both must pass. `--prebuilt` installs already-built Debug + androidTest APKs over ADB without Gradle. 16 KiB page-size image: `BATSTATS_REPORT_GROUP=16k BATSTATS_EXPECTED_PAGE_SIZE=16384 bash scripts/check_android_device.sh --prebuilt`. Use a disposable API 36 `google_apis` emulator (not ATD; notification checks need SystemUI). The tests change simulated battery and font settings, so never point them at a personal device. Reports go to `app/build/reports/device-validation/{standard,16k}/`.
- Manual ADB-mode testing needs both grants plus the app-op on Android 16 (adjust the package to the installed build):
  `adb shell pm grant org.mlm.batstats.debug android.permission.DUMP && adb shell pm grant org.mlm.batstats.debug android.permission.PACKAGE_USAGE_STATS && adb shell appops set org.mlm.batstats.debug GET_USAGE_STATS allow`
- Build types: `debug` (`org.mlm.batstats.debug`); `preview` (R8-optimized release clone, `org.mlm.batstats.preview`, signed with the four `PREVIEW_*` env vars if set, otherwise the debug key; setting only some of them fails the build); `release` (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Neither dev package can update an upstream `org.mlm.batstats` install. ABI splits are on by default (`-PenableApkSplits=false`, `-PtargetAbi=…`). APK-dist copies go to `app/build/outputs/distribution/`, separate from AGP's `outputs/apk/` because device-test tasks consume the latter.

# CI

`test_if_it_builds.yml` runs on every push and PR and calls `build-apk.yml`: host checks, the full Gradle build and lint, then both emulator phases on API 36 `google_apis` and again on `google_apis_ps16k`. `build-apk.yml` is also a manual `workflow_dispatch` for downloadable APKs. `android.yml` is the production release workflow (external `mlm-games/ci`; can push version commits and publish releases).

# Architecture

Kotlin + Jetpack Compose, Koin DI (`di/AppModules.kt`; `BatteryGraph` in `BatteryApp.kt` is a legacy accessor), Room, Navigation3 (`ui/NavGraph.kt`: `Screen` sealed `NavKey`s, `NavDisplay` owns back handling). Source namespace `app.batstats`; application ID `org.mlm.batstats`.

**Monitoring pipeline.** `BatteryMonitorService` is a `specialUse` foreground service, also started by `BootReceiver`. It calls `BatteryRepository.startSampling()` and combines repository flows into the notification (`DrainNotificationManager`), widgets (`WidgetUpdater`: plain `RemoteViews` pushed by the service, not Glance) and alerts (`BatteryAlerts`, with latched episodes persisted in SharedPreferences). In `BatteryRepository`:
- Each start creates a random *generation* ID. A timed loop (`capture(Boundary.SAMPLE)`) and a receiver for battery/screen/Doze broadcasts produce `Observation`s tagged with it. Events queued under an old generation are dropped after stop.
- `StateEventSequencer` briefly buffers a poll capture until the matching state broadcast arrives. Missing or conflicting events become explicit gaps.
- All persistence runs in **one writer coroutine** that consumes an `Event` channel (Start/Sample/Stop/Reset/Clear). That coroutine owns `ObservationEngine` (observed totals, gaps, screen/Doze buckets), session handling, `RemainingTimeEstimator` and Room writes. History clear and import are serialized through `HistoryMaintenance`'s mutex.
- Failures go into a per-source failure map exposed as `error`, so storage failures are reported while live readings continue.

**Privileged access.** `ShellRunner.exec` selects exactly one mode per command (`SHIZUKU`, `ROOT`, `ADB` via granted DUMP/PACKAGE_USAGE_STATS, or `NONE`), and a failure never falls through to another source. `ShizukuBridge` binds `ShellUserService` as a Shizuku UserService and runs commands over pipe transactions with request IDs so they can be cancelled. `DetailedStatsCollector` refreshes on its own interval and whenever the access mode changes. It parses output with `BatteryStatsParser`, `CheckinParser`, `KernelStats` and `DumpOutput`.

**Settings.** `settings/SettingsDefinition.kt` defines `AppSettings` with `@Setting`/`@Persisted` annotations from the `kmp-settings` library, and KSP generates `AppSettingsSchema`. Settings are stored in DataStore `batstats_settings`, with `MigrationManager` keyed to `SCHEMA_VERSION` in `AppModules.kt`. Settings import (`SettingsImportPolicy`) matches schema fields by persisted key, so renaming a key breaks existing backups.

**Database.** `BatteryDatabase` is version 4 with explicit `MIGRATION_x_y` objects. Schemas are exported to `app/schemas/`. `scripts/check_migrations.py` and `check_history_queries.py` hard-code `4.json`, and the migration check reads fixtures from `DatabaseMigrationTest.kt`. A schema bump needs all three updated.

# Gotchas

- Compose uses `enforcedPlatform(composeBom)` on purpose: kmp-settings would otherwise pull an alpha Material3 with an incompatible ABI. Don't upgrade Material3 separately from the BOM.
- Only `en`, `es`, `tr` ship (`androidResources.localeFilters`). New strings go into `values/`, `values-es/` and `values-tr/` with identical format arguments; `check_resources.py` fails otherwise. See `docs/LOCALIZATION.md`.
- JVM tests render real English strings through `app/src/test/.../support/EnglishStrings.kt`, which reads `strings.xml` from disk relative to the working directory.
- Emulator results don't validate Samsung current calibration, battery capacity or physical monitoring overhead (`docs/BUILD_AND_INSTALL.md`). Record actual results in `docs/VALIDATION.md`.
