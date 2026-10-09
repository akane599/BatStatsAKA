# Privileged Shell and Per-App Stats

*Last Updated: 2026-10-09*

Per-app battery use needs `dumpsys batterystats`, which normal apps can't run. Everything that needs
privilege goes through one path. Paths below are under `app/src/main/java/com/akane/voltwise/battery/`.

```
AppsViewModel / AppDetailsViewModel / SessionSnapshotCollector
        │
AppStatsRepository (apps/AppStatsRepository.kt)   ── StatsShell seam; ShellRunnerStatsShell on device
        │  COMMAND = "dumpsys batterystats -c --charged"; concurrent callers share one dump; cached
ShellRunner (util/ShellRunner.kt)                   ── Mode { ROOT, SHIZUKU, ADB, NONE }, Outcome sealed class
        │  selectShellMode(): SHIZUKU (running + authorized) → ROOT → ADB (DUMP granted) → NONE; cached 10 s (root access loss clears it)
        ├─ SHIZUKU: ShizukuBridge.run() (shizuku/ShizukuBridge.kt) → bound ShellUserService (shizuku/ShellUserService.kt)
        ├─ ROOT:    CommandOutput.run(["su","-c",cmd])           (util/CommandOutput.kt)
        └─ ADB:     CommandOutput.run(cmd.split(' '))            (needs `pm grant … DUMP`; PrivilegeChecker.hasAdvancedViaAdb)
        │
BatteryStatsParser (util/BatteryStatsParser.kt) → FullSnapshot / AppPowerStats
```

## Pieces
| File | Role |
| --- | --- |
| `shizuku/ShizukuBridge.kt` | Binds the Shizuku user service (`SERVICE_VERSION`, bind timeout), runs commands over a pipe (`runViaPipe`), retries ping, and unbinds after `IDLE_UNBIND_MS` (60 s) idle (`IdleCountdown`, `HelperBinding`). `RunResult.Success/Error(Failure)`; `classifyAfterRead` separates lost access (not running / no permission) from transport and command failures. Exposes a `blocked` flow when the user denied permission permanently (`shizukuPermissionBlocked`); `requestPermission` is then a no-op. |
| `shizuku/ShellUserService.kt` | A `Binder` running in the Shizuku helper process. It runs only what `battery/actions/CommandPolicy.allows` admits (`ShellUserService.allows`): `dumpsys batterystats -c --charged`, `dumpsys battery`, `dumpsys deviceidle`, and the Insights action templates from `battery/actions/PrivilegedCommand.kt` (`am get/set-standby-bucket`, `am force-stop`, `cmd appops get/set` with allow/ignore only, `cmd deviceidle whitelist [+/-pkg]`), each with a validated package name; mutations (`set-standby-bucket`, `force-stop`, `appops set`, `whitelist +/-pkg`) on protected packages are refused while read-only gets stay allowed (`CommandPolicy.isProtected` adds uid < 10000 and non-primary users for the action layer). Transactions: `TRANSACTION_RUN_PIPE`, `TRANSACTION_CANCEL`, `TRANSACTION_DESTROY` (the literal 16777115, pinned against Shizuku's constant by `ShellUserServiceTest`). |
| `util/CommandProtocol.kt`, `util/CommandOutput.kt` | Bounded process execution and the pipe framing between the helper and the app. `CommandOutput.Result.accessFailure` (`DENIED` / `EXECUTABLE_UNAVAILABLE`) classifies a su denial or missing `su`; `ShellRunner` then drops the cached ROOT mode and reports `NoAccess` (`rootAccessLost`). An ordinary command failure keeps the mode. |
| `util/DumpOutput.kt` | Recognizes refusal/failure text in dump output. |
| `util/RootStatsCollector.kt` | Root probe (`su -c id`) and the one root sysfs read (`charge_full_design` from `/sys/class/power_supply/battery/uevent`) used by `data/DesignCapacitySource.kt`. |
| `util/PrivilegeChecker.kt` | Detects the ADB-granted `DUMP` permission. |
| `apps/AppInfoRepository.kt`, `apps/AppInfoCache.kt` | Labels and icons for UIDs/packages (needs `QUERY_ALL_PACKAGES`); icons are dropped on trim memory. |
| `apps/AppUsageSnapshot.kt`, `apps/AppUsageDelta.kt`, `apps/TopApps.kt` | Snapshot model, the BASELINE→END delta (in a DELTA, a null baseline counter counts as 0 when the baseline has any non-null extended counter; an all-null pre-upgrade baseline stays unknown), and the top-N plus "others" ranking. Shared UIDs: `UidIdentity` / `AppPowerStats.identity()`. |
| `apps/SessionSnapshotCollector.kt`, `apps/SessionSnapshotStore.kt` | Per-discharge-session breakdown (BASELINE on discharge open, END at plug-in, abandoned → FAILED), stored via `RoomSessionSnapshotStore` in `app_snapshots`, `app_snapshot_uids` and `session_app_usage`. |
| `viewmodel/ShizukuState.kt` | UI-facing Shizuku state (`running`, `granted`, `blocked`). A blocked permission shows an Open Shizuku action instead of Allow (Apps, AppDetails, Status). |

## Gotchas
- Never widen `CommandPolicy.allows` casually: it is the privilege boundary for both the Shizuku helper and the Insights actions (see [insights.md](insights.md)).
- `BatteryStatsParser.parseCheckin(..., sdkInt)` picks process-state columns by SDK (background/cached at 8/9
  before API 28, 7/10 after); `AppStatsRepository` passes `Build.VERSION.SDK_INT`.
- `ShellRunner.access` / `lastError` feed the notification issue line and the Status screen.
- Checkin is not escaped CSV (`BatteryStatsParser.splitCheckinLine`): `wl` names keep raw quotes (commas are
  already `_`), `sy`/`jb` names are framed between 4 header and 4 tail fields, `wr`/`kwl` quoted names run
  through the last field that closes the quote (the `kwl` tail has two or five timer fields), an unmatched
  quote rejects the row, `pr` names are unquoted. Malformed rows count as `rejected`.
- `ShizukuBridge.readPipeResult` reports a helper call the service refused as a refusal, not a timeout.
- Instrumented Shizuku tests are annotated `com.akane.voltwise.test.RequiresShizuku` and run as a separate
  phase (see `scripts/prepare_shizuku.py`, `scripts/test_device_phases.py`).
- Background: `docs/PLATFORM_NOTES.md`, `docs/MEASUREMENTS.md`.
