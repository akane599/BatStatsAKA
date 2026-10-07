# Entry Points

*Last Updated: 2026-10-07*

Everything is declared in `app/src/main/AndroidManifest.xml`. Paths below are under
`app/src/main/java/app/batstats/`.

| Entry | Class / file | What starts it | Notes |
| --- | --- | --- | --- |
| Application | `battery/BatteryApp.kt` (`BatteryApp`) | process start | `startKoin(appModule)`, `ShizukuBridge.warmUp()`, runs `SettingsMigrator`, then `backfillDailySummariesOnce()`. `BatteryGraph` is a legacy Koin accessor. |
| Launcher activity | `battery/BatteryMainActivity.kt` | launcher, `QS_TILE_PREFERENCES` (tile long-press) | Edge-to-edge dark bars, asks for `POST_NOTIFICATIONS` (API 33+), reads the `destination` extra in `onCreate`/`onNewIntent`, hosts `MainTheme` → `ui/screens/MainScreen.kt`. |
| Foreground service | `battery/service/BatteryMonitorService.kt` | `MonitoringController.start()` | `specialUse` FGS. Promotes to the foreground first, then `repository.startSampling()`, `SessionSnapshotCollector.run()`, the notification loop and widget pushes. |
| Boot receiver | `battery/service/BootReceiver.kt` | `BOOT_COMPLETED` | When `autoStartOnBoot` is set, starts monitoring through `MonitoringControl`; if Android blocks it, `Notifier.promptStartOnBoot` shows a notification. |
| QS tile | `battery/tile/MonitorTileService.kt` | the user adds the tile | Live value while the shade is open (holds `SamplingDemand`); a tap toggles monitoring. |
| Widgets | `battery/widget/BatteryLevelWidget.kt`, `BatteryTempWidget.kt`, `BatteryTimeWidget.kt` | `APPWIDGET_UPDATE`, `app.batstats.battery.widget.ACTION_REFRESH` | RemoteViews (`res/layout/widget_*.xml`, `res/xml/widget_*.xml`). `WidgetUpdater` builds the content. |
| Notification actions | `battery/drain/DrainNotificationReceiver.kt` | `…drain.ACTION_RESET`, `…drain.ACTION_STOP` | `ACTION_RESET` calls `repository.resetObservation()`, which the writer applies only to an open DISCHARGE session (`resetApplies` in `data/BatteryRepository.kt`); `ACTION_STOP` calls `MonitoringControl.stop()`. |
| Shizuku provider | `rikka.shizuku.ShizukuProvider` (library) | the Shizuku manager | Authority `${applicationId}.shizuku`; `battery/shizuku/ShellUserService.kt` is the Shizuku user service binder, which runs in a privileged helper process and exposes only fixed commands. |

## In-app navigation
- `ui/navigation/Routes.kt`: a `@Serializable sealed interface Routes : NavKey` with the tabs `Now`,
  `History`, `Apps`, `Settings` and the detail routes `SessionDetails(sessionId)`,
  `AppDetails(uid, packageName)`, `Health`, `SettingsData`, `SettingsStatus`.
- `ui/navigation/TopLevelBackStack.kt`: one `NavBackStack` per tab; `select`, `navigate`, `onBack`,
  `openDestination(value)`, and `blockLeaving(entry, onBlocked)` leave blockers (DataScreen blocks while a
  task runs; `popToRoot`/`openRoot` refuse to drop a blocked entry).
- `ui/NavGraph.kt`: the `entry<Routes.X>` → screen mapping. Screens get Koin VMs (`koinViewModel`).
  `AppDetails` and `SessionDetails` take parameters.
- `ui/navigation/Destinations.kt`: string values for the `destination` deep-link extra (`now`,
  `history`, `apps`, `settings`, `health`, `status`, `session:<id>`). `initialDestination(extra, restored,
  launchedFromHistory)` ignores the extra after a state restore and on a launch from Recents.

## Build / CLI entry points
See [onboarding.md](onboarding.md). There are Gradle tasks, plus helper scripts in `scripts/`
(`check_resources.py`, `check_migrations.py`, `check_history_queries.py`, device-test helpers) and in
`.claude/scripts/run_screenshot_tests.sh`.
