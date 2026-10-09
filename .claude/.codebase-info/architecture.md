# Architecture

*Last Updated: 2026-10-09*

Voltwise (formerly BatStats) is a single-module (`:app`) Android app. It reads battery state, records observed
charge/discharge **sessions** into Room, and, when it has privileged access (Shizuku, root or ADB-granted
`DUMP`), shows per-app battery use parsed from `dumpsys batterystats`. **Insights** ranks unusual drain
against per-device baselines and can apply reversible privileged fixes ([insights.md](insights.md)). The UI is Compose Material 3
(dark-only, with an optional OLED theme). Code is MVVM with Koin DI, and coroutines/Flow throughout.

## Layers

```
 UI (Compose)            ui/screens/*, ui/components/*, ui/NavGraph.kt  (Navigation 3, 5 tabs)
   │ collectAsStateWithLifecycle(StateFlow<UiState>), onEvent(...)
 ViewModels              viewmodel/*ViewModel.kt  ── each talks to a <Screen>Repository interface
   │                                                (Default<Screen>Repository adapts the app services)
 App services (Koin singletons, di/AppModules.kt)
   ├─ BatteryRepository        battery/data/BatteryRepository.kt   history writer + live flows
   ├─ SamplingController       battery/data/sampling/              the only BatteryManager sampler
   ├─ AppStatsRepository       battery/apps/                       batterystats reader (privileged)
   ├─ SessionSnapshotCollector battery/apps/                       per-session app breakdowns
   ├─ ShellRunner/ShizukuBridge battery/util, battery/shizuku      privileged command execution
   ├─ InsightRepository        battery/insights/                   findings engine + report flow
   ├─ InsightActionRepository  battery/insights/actions/           privileged apply/undo journal
   ├─ CalibrationStore, DesignCapacitySource, HistoryRetention, ExportImportManager, DiagnosticStore
   └─ SettingsRepository<AppSettings>  (kmp-settings over DataStore; settings/)
 Persistence             battery/data/db/  Room `battery.db` v8   + DataStore + SharedPreferences
 Android surfaces        service/ (FGS), drain/ (notification), widget/ (RemoteViews), tile/ (QS tile)
```

## Key flows

**Monitoring (sampling → history).** The user starts monitoring (Now screen, QS tile or boot) →
`MonitoringController.start()` (`battery/service/MonitoringController.kt`) starts
`BatteryMonitorService` (foreground, `specialUse`) → the service starts the repository's sampling
(`BatteryRepository.startSampling()`) and `SessionSnapshotCollector`, and runs
`DrainNotificationManager`. `SamplingController` owns a `HandlerThread` ("batstats-sampler"),
battery broadcasts and polls at 2 s while any `SamplingDemand` token is held, otherwise at 30 s with
the screen on and 300 s with it off (`measurement/SamplingPolicy.kt`). It sends captures to the
repository's `SamplerSink`. In `BatteryRepository`, the FIFO `HistoryWriter` runs every capture
through `ObservationEngine` (`measurement/ObservationEngine.kt`). `PersistPolicy` picks which captures
become rows, and `PersistDao.persistSample` commits the sample, the session row (`SessionReport`) and
the touched `daily_summaries` in one transaction.

**Live readings.** `BatteryRepository.realtimeFlow` (calibrated by `CalibrationStore`) feeds
`NowViewModel` (via `DefaultNowRepository`), the notification, widgets (`WidgetUpdater`) and the tile.
The database always keeps **raw** `CURRENT_NOW`; calibration (unit/sign) is applied on read. See
`docs/MEASUREMENTS.md`.

**Per-app stats.** `AppsViewModel` / `AppDetailsViewModel` → `AppStatsRepository` → `ShellRunner`
(mode picked in order SHIZUKU → ROOT → ADB → NONE) → `dumpsys batterystats -c --charged` →
`util/BatteryStatsParser.kt`. Concurrent callers share one dump. `SessionSnapshotCollector` takes a
BASELINE dump when a discharge session opens and an END dump at plug-in, then stores the delta as
`session_app_usage`. See [privileged-shell.md](privileged-shell.md).

**Insights.** Once the settings migration has finished (`SettingsMigrator.awaitMigrated`), `BatteryApp` reconciles (off the main thread) the action journal and runs a catch-up
refresh; its application scope refreshes on finalized discharge snapshots and closed non-discharge sessions, and monitoring awaits those subscriptions before starting producers. `InsightRepository.refresh()` builds
inputs from Room (`InsightInputsBuilder`), runs the pure `InsightEngine` (detectors → rank → top 12) and
publishes `report`, which the Insights tab, Finding details, the Now card, App details and
`InsightNotifier` consume. Applying a fix goes `InsightActionRepository` → `CommandPolicy`-checked
`PrivilegedCommand` → `ShellRunner`, journalled PREPARED→APPLIED/FAILED/UNKNOWN with readback.

**Deep links.** A notification, tile or widget passes `Destinations.EXTRA_DESTINATION` →
`BatteryMainActivity` → `MainScreen` → `TopLevelBackStack.openDestination` (`ui/navigation/`).

## Boundaries and invariants
- `SamplingController` is the only reader of BatteryManager/PowerManager for sampling. `readOnce`
  (widgets, refresh) never reaches history.
- `BatteryRepository` writes from a single writer coroutine. Lifecycle events are never dropped;
  samples are bounded.
- Settings migration (`SettingsMigrator`, run from `BatteryApp`) must finish before history retention
  reads settings (`HistoryRetention`).
- Insights startup work waits for `SettingsMigrator.awaitMigrated()`, runs on `Dispatchers.IO` on the shared `appScope`;
  `InsightNotifier` is a lazy Koin single (never `createdAtStart`).
- `battery/actions/CommandPolicy.allows` is the only privileged-command allow-list (helper + actions).
- `MIGRATION_6_7` and `MIGRATION_7_8` are additive and forward-only; reverting the app leaves `battery.db` at v8.
- `MIGRATION_4_5` is irreversible: it drops `alarm_rules` and `app_energy_stats`. See [database.md](database.md).

Related: [modules.md](modules.md), [entry-points.md](entry-points.md), [patterns.md](patterns.md).
