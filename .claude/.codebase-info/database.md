# Database

*Last Updated: 2026-10-07*

Room database `battery.db`, **version 5**, `exportSchema = true`.
- Definition and migrations: `app/src/main/java/app/batstats/battery/data/db/BatteryDatabase.kt`
- Entities: `data/db/Entities.kt`, `data/db/AppUsageTables.kt`, `data/db/DailySummary.kt`
- DAOs: `data/db/Dao.kt`
- Exported schemas: `app/schemas/app.batstats.battery.data.db.BatteryDatabase/4.json`, `5.json`
  (KSP arg `room.schemaLocation` in `app/build.gradle.kts`)

## Tables

| Table | Entity | Key | Purpose |
| --- | --- | --- | --- |
| `battery_samples` | `BatterySample` | `id` autoinc; unique (`observationId`,`elapsedMs`); idx `timestamp`, `sessionId` | Persisted captures: level, status, plugged, **raw** `currentNowUa`, charge counter, voltage, temperature, screen, monotonic clocks, ETA, `source`, `boundaryReason`. |
| `charge_sessions` | `ChargeSession` | `sessionId` (UUID text); unique `activeKey` (1 while open, so at most one open session); idx `startTime`, `type` | Observed sessions. `type` is a `SessionType` (CHARGE, DISCHARGE, PLUGGED, UNKNOWN). The v5 nullable columns are charger type, energy, peaks, screen-off suspend, capacity estimate/confidence/basis, `appUsageStatus`, `appUsageBasis`. |
| `daily_summaries` | `DailySummary` | `epochDay` | Per-local-day screen on/off time and discharge, charged µAh, min/max level, peak temperature. Upserted with each persisted sample. |
| `app_snapshots` | `AppSnapshot` | `id` autoinc; idx `sessionId` | BASELINE / END batterystats snapshot headers (`AppSnapshotKind`). |
| `app_snapshot_uids` | `AppSnapshotUid` | (`snapshotId`,`uid`), FK → `app_snapshots` ON DELETE CASCADE | Per-UID power, CPU, foreground/background, wakelock, data bytes. |
| `session_app_usage` | `SessionAppUsage` | (`sessionId`,`rank`), FK → `charge_sessions` ON DELETE CASCADE | The ranked per-session app breakdown (top-N plus an `isOthers` row) with `basis`. |

## DAOs (`Dao.kt`)
- `BatteryDao`: sample insert/lookups, chart queries (`chartSamples`, `sessionChartSamples` bucketed), `latestSamplesBetween` (newest N, returned ascending), `boundStorage`, `purge`, `clearAll`. `ExportImport.kt`'s `BatteryDao.exportSamples` uses it for an ALL export, so that export holds the newest `MAX_SAMPLES`.
- `SessionDao`: `active()` / `activeFlow()`, `filteredSessions`, `capacityEstimates`, `closeInterrupted`, `deleteSession(id, recordingGeneration)` (deletes samples, snapshots and the row together), `purge`.
- `PersistDao`: `persistSample(sample, session, days)` is the single transactional write the repository uses per persisted capture.
- `DailySummaryDao`: per-day upsert/read/range, `purgeBefore`.
- `AppUsageDao`: snapshot header + UID rows, session app usage.

`EnumConverters` (in `BatteryDatabase.kt`) never uses `valueOf`: unknown enum text reads as null, or
as a documented fallback for the two NOT NULL enum columns.

## Migrations
| Step | Change |
| --- | --- |
| 1→2 | adds `app_energy_stats` |
| 2→3 | no-op (keeps rows) |
| 3→4 | rebuilds `battery_samples` and `charge_sessions` with range-sanitised copies; legacy sessions get a close reason |
| 4→5 | **irreversible**: drops `alarm_rules` and `app_energy_stats`, adds the v5 session columns and creates `daily_summaries`, `app_snapshots`, `app_snapshot_uids`, `session_app_usage` (DDL copied from `5.json`). No 5→4 path. |

There's no destructive fallback: every version needs an explicit `MIGRATION_a_b` registered in `get()`.

## Checks
- Host-side: `python3 scripts/check_migrations.py` (replays migrations on SQLite against the newest
  exported schema) and `python3 scripts/check_history_queries.py` (DAO SQL on host SQLite).
- Device: `app/src/androidTest/java/app/batstats/battery/data/DatabaseMigrationTest.kt`,
  `HistoryBrowseTest.kt`, `HistoryImportTest.kt`, `RepositoryRecoveryTest.kt`.
- JVM: `app/src/test/java/app/batstats/battery/data/db/EnumConvertersTest.kt`.

## Other persistence
- Settings: DataStore `batstats_settings` via kmp-settings (`settings/`; schema v3, `SettingsMigrations`).
- SharedPreferences: `CalibrationStore.PREFS_NAME` (calibration) and `SamplerState.PREFS_NAME` (sampler
  state), both wrapped in `SharedPreferencesStore` / `KeyValueStore` (`data/sampling/KeyValueStore.kt`).
- Export/import: `data/ExportImport.kt` (`BatteryExport` format 3; formats 1–2 still import) and
  `data/HistoryFiles.kt`. Backup rules: `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`.
- Retention: `data/HistoryRetention.kt`, `data/HistoryPolicy.kt`; `boundStorage` trims to
  `HistoryLimits.SAMPLE_TRIM_TARGET`/`SESSION_TRIM_TARGET` (cap − 200) every `CLEANUP_SAMPLE_INTERVAL` inserts
  (`data/HistoryFiles.kt`), so tables may sit slightly over `MAX_SAMPLES`/`MAX_SESSIONS` between trims; import
  refuses only its own growth past the cap (`importWithinLimit`).
- Import validation (`HistoryPolicy.kt`): session coverage is clamped to the span within a clock-correction
  allowance (5 s + span/10, capped at 15 min; `normalizeCoverage`, which scales screen-on/off charge with the clamped time, `sampleInSessionWindow`), and
  `planSessionImport` decides ADDED/UPDATED/UNCHANGED/STALE from the raw stored and raw incoming rows.
