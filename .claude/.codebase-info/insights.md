# Insights

*Last Updated: 2026-10-09*

Insights (story US-6) turns the recorded discharge sessions and per-app batterystats snapshots into ranked
**findings** ("Chrome is draining more than usual"), each with evidence against a per-device baseline and
optional **recommendations** that can apply a privileged system setting and undo it later. Method,
thresholds and limits are written up for users in `docs/MEASUREMENTS.md` ("Insights") and the privileged
side in `docs/PLATFORM_NOTES.md` ("Privileged actions in Insights", "Rolling back"). Paths below are under
`app/src/main/java/com/akane/voltwise/`.

## Flow

```
Room (sessions, session_app_usage, *_device_wakers, insight_*)
  └─ battery/insights/InsightInputsBuilder.kt      builds one immutable input set
       └─ battery/insights/engine/InsightEngine.kt  pure: detectors → rank → take(12)
            └─ battery/insights/InsightRepository.kt  persists findings, keeps `report: StateFlow<InsightReport?>`
                 ├─ viewmodel/InsightsRepository.kt (DefaultInsightsRepository) → InsightsViewModel / FindingDetailsViewModel
                 ├─ viewmodel/NowRepository.kt → NowUiState.insightsSummary (Now card)
                 ├─ viewmodel/AppDetailsViewModel.kt → AppFinding rows (App details "Findings")
                 └─ battery/insights/InsightNotifier.kt  one HIGH finding per 24 h
```

`InsightRepository.refresh()` runs under a mutex and captures `HistoryMaintenance`'s clear generation before
reading; its writes happen under `HistoryMaintenance.mutations` and are dropped when a history clear ran in
between (no stale report after Clear history). `dismiss(key)` / `notAProblem(key)` write user feedback
(a down-weighting multiplier) that the next refresh honours, under the same `mutations` lock and clear-generation
check; `lastAnalyzedAt` starts null and loads on IO (`awaitLastAnalyzedAt()` for the startup catch-up); a dismissed finding keeps its severity
high-water mark, so it reappears only when it gets worse than when it was dismissed. The device SDK is passed
explicitly through the engine to `Recommender`, which offers an action only where its operation works
(STANDBY_* needs API 28). Inputs cover the last
`InsightInputsBuilder.HISTORY_DAYS` (90) days of the main profile only (uid rows of other users are dropped,
the Others row kept; the stored waker count is taken before that filter); stale findings are purged together with history retention
(`purgeFindingsSeenBefore`, called from `battery/data/HistoryPolicy.kt`).

## Packages

| Path | Contents |
| --- | --- |
| `battery/insights/model/InsightModels.kt` | `Finding`, `FindingType`, `Severity`, `Confidence`, `Evidence`, `Recommendation`, `ActionType`, `InsightReport` |
| `battery/insights/engine/` | `InsightEngine.kt` (entry; ranks and caps at 12), `AppFindings.kt`, `ActionEffects.kt` (before/after association for applied actions; measures `AppliedActionInput.metric`, the fired finding's lead metric, before falling back to the action type), `Trends.kt` (7-day vs 21-day) |
| `engine/eligibility/AppWindows.kt` | Which DISCHARGE windows are comparable (≥ 1 h, READY/DELTA basis). Censoring of an app absent from a truncated window is per metric family: POWER bounded by the smallest stored leader; alarms/partial wakelocks exactly 0 when fewer than 10 waker rows were stored, else the waker cutoff / Others value; other metrics the Others row value or unsupported |
| `engine/stats/` | `RobustBaseline.kt` (decayed median/MAD, `MAD_SCALE = 1.4826`), `EffectSize.kt`, `TheilSen.kt` |
| `engine/detectors/app/` | Per-app detectors on process-state proxies: `AppDrainAnomaly`, `BackgroundRunaway`, `BackgroundLocation`, `BackgroundRadio`, `StuckWakelock`, `WakeupStorm`, `JobStorm`, `LingeringForegroundService`, `NewHeavyApp`; shared thresholds in `AppContext.kt` |
| `engine/detectors/device/` | `DeviceDetectors.kt`, `DeviceMeasurements.kt` (Doze / deep sleep), `Attributions.kt` (device wakers; per-kind quota of 3 kernel wakelocks by time and 2 wakeup reasons by count, then filled), `ChargingHealth.kt` |
| `engine/recommend/Recommender.kt` | Maps findings to `Recommendation`s (standby bucket, background op, Doze whitelist removal, force-stop, OPEN_* settings intents) |
| `battery/insights/FindingCodec.kt` | Finding ⇄ `InsightFindingEntity` (JSON evidence) |
| `battery/insights/actions/` | `InsightActionRepository.kt` (apply / undo / `reconcile()` over a PREPARED→APPLIED/FAILED/UNKNOWN journal in `insight_actions`; an undo that finds the setting changed outside Voltwise settles REVERTED with message `CHANGED_EXTERNALLY` without touching the device), `ActionExecutor.kt`, `TargetInspector.kt` (revalidates the target package/uid before acting) |
| `battery/actions/` | `PrivilegedCommand.kt` (fixed argv templates: `am get/set-standby-bucket`, `cmd appops get/set`, `cmd deviceidle whitelist [+/-pkg]`, `am force-stop`, `dumpsys deviceidle`), `CommandPolicy.kt` (allow-list + protected packages, shared with `ShellUserService`), `ActionReadback.kt` (reads the state back; UNKNOWN on unparseable OEM output) |
| `battery/insights/InsightNotifier.kt` | `InsightNotificationPolicy` (HIGH severity, confidence ≥ MEDIUM, 24 h cooldown; notified finding keys kept as a JSON set in the `insights` store, pruned to the current report, legacy single `LAST_KEY` migrated) and the `insights` channel (IMPORTANCE_LOW); content intent opens the Insights tab via `Destinations.INSIGHTS` |

## UI

| Screen | Files | ViewModel |
| --- | --- | --- |
| Insights tab | `ui/screens/insights/InsightsScreen.kt` (loading state until `loaded`; `ResultSnackbar` shows `apply.lastResult` once, then sends `InsightsEvent.ResultShown`, so it survives rotation), `InsightsPanels.kt` (`SeverityChip`, shared `AppliedFixesPanel`), `InsightLabels.kt` (type titles, `evidenceLine`, `statusLabelRes()`: "Changed outside Voltwise" when `AppliedInsightAction.changedExternally`), `InsightApplyDialog.kt` | `viewmodel/InsightsViewModel.kt` (`InsightApplyFlow` drops a pending apply when its finding or recommendation leaves the report, but not while `DefaultInsightsRepository.privileged` is still null/unknown; results go to the app-scoped `InsightApplyResults` single, so an outcome survives the destination popping and is shown once on whichever screen consumes it; `loaded` = a report exists; state in `InsightsUiState.kt`) |
| Finding details | `ui/screens/insights/FindingDetailsScreen.kt`, `FindingChart.kt` (usual band vs observed; one TalkBack summary) | `viewmodel/FindingDetailsViewModel.kt` (feedback runs on the injected application scope so it survives the screen popping) |
| Now card | `ui/screens/now/NowInsights.kt` (`InsightsPanel`: headline / all good / not analysed; hidden with no data) | `NowViewModel` (`NowUiState.insightsSummary`, null until the first analysis has run) |
| App details | "Findings" panel in `ui/screens/AppDetailsScreen.kt` | `AppDetailsViewModel` (`AppFinding.evidence`, `AppDetailsEvent.OpenFinding`) |

Strings: `res/values*/strings_insights.xml`, `strings_finding.xml`, `strings_insights_notification.xml`.

## Wiring and lifecycle
- Koin (`di/AppModules.kt`): `InsightDao`, the action executor and inspector, `InsightActionRepository`,
  `InsightRepository`, `DefaultInsightsRepository`, `InsightApplyResults` (shared by both Insights view models; not kept across process death), and a plain lazy `InsightNotifier` single (not
  `createdAtStart`: an eager single needs an Android context in JVM tests and would resolve
  Android-backed dependencies on the main thread at `startKoin`). All share the app-wide `appScope` (SupervisorJob + fixed-code failure handler).
- Startup (`battery/BatteryApp.kt`, `startInsightNotifications` on `Dispatchers.IO`): after `SettingsMigrator.awaitMigrated()`, `InsightActionRepository.reconcile()`,
  then a catch-up `refresh()` when the awaited last analysis is missing, older than 6 h or in the future (a failure is
  recorded as `APP_SCOPE_FAILED` and collection still starts), then `startInsightNotifications` collects
  `InsightRepository.report`.
- `battery/service/BatteryMonitorService.kt` refreshes insights when sessions finalise
  (`refreshOnFinalizedSessions`, conflated; failures recorded as a fixed diagnostic code).
- Privileged actions need Shizuku or root (`ShellRunner.detectMode()`); without them recommendations show
  "needs Shizuku or root" and OPEN_* settings intents still work.

## Tests
JVM tests mirror the packages under `app/src/test/java/com/akane/voltwise/battery/insights/` and
`battery/actions/`, plus `viewmodel/Insights*`, `FindingDetailsViewModelTest`, `di/InsightsWiringTest`,
`InsightNotifierPolicyTest`. Screenshot tests: `InsightsScreenshotTest`, `FindingDetailsScreenshotTest`,
the Now card previews in `NowScreenScreenshotTest`, the Findings previews in `AppDetailsScreenshotTest`.
