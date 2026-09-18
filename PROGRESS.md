# BatStats Development Progress

## Scope and authority
- Baseline revision: `76bc831328572c81717b97ffb0e280b10b14b8ad` (source6.2.6/code734). Branch: `codex/android16-reliability`. No unrelated changes at start.
- Goal: accurate, efficient Android16/API36 monitoring, Shizuku primary; ordinary/ADB/Root preserved, useful rich notification, modern accessible UI, reliable history/export/diagnostics, meaningful tests and installable APKs.
- Follow AGENTS.md: focused stages, update this record and commit locally after each stage, reread before continuing; after compaction inspect records/status/recent commits/full diff before implementation.
- Local edits, dependencies, builds, tests, disposable emulators and commits authorized. **No GitHub writes/pushes/Actions approved or performed.** Finish local validation before proposing exact remote actions and automatic workflow triggers.
- No physical Samsung. Do not request phone testing or claim emulator values validate hardware current/capacity/energy savings/One UI behavior. User-requested UX work overrides generic audit skill enhancement restriction.
- Per-commit identity: `git -c user.name=Codex -c user.email=codex@openai.com commit`; preserve unrelated work/processes and never commit signing keys/secrets.

## Baseline artifacts and signing
- Supplied6.2.6 APK: `/tmp/batstats-artifacts/baseline-6.2.6.apk`, package `org.mlm.batstats`, code735/target37/min26. SHA256 `4009bc1f13d7c871ddc1c61f3696ed224f1ad5fbfe547ed452fc2aeaca86b3c5`.
- Verified original signerSHA256 `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`; key unavailable. Debug `.debug` coexists; still prepare signed nondebug `.preview` and explain update compatibility.
- Official Shizuku13.6.0: `/tmp/batstats-artifacts/shizuku-13.6.0.apk`, SHA256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`, signerSHA256 `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`. Not installed yet.

## Committed stages
- `31c9a55`: instructions and progress. `8ebc074`: baseline APK/source audit.
- `6e7e801`: API36 specialUse FGS/boot handling, stable Compose, bounded Shizuku-first command transport with explicit source/failure.
- `08307d4`: nullable validated units, monotonic ObservationEngine, counter-based stable ETA. No fixed4000mAh or cycle-based health.
- `e0d1eb3`: repository single-owner bounded actor, automatic sessions/shared observation for UI/notification, DB4 nondestructive migration, unique active session/observed points, bounded charts/history. Removed unsupported80/20mA per-app heuristic and unused duplicate hourly dumper; detailed UID capability retained.
- `e95273c`: Android16 ADB permission/app-op gate, non-consuming `-c --charged`, producer-backed parser units/windows/UID mappings, advanced UI. Synthetic regressions reproduced12 parser failures before correction.
- `3584abc`: cancellable request-ID Shizuku helper/bridge, interruptible Root/ADB, current-generation collector, actual curated su/kernel reads with validated ABI units/source/errors. Binder/vendor hardware verification pending.
- `a2b9021`: transactional bounded JSON/CSV import/export, deterministic import identity/dedup/conflict rollback, source-separated local charts, serialized clear/start gate, bounded retention and settings-only automatic backup. Imports close foreign active sessions; native totals remain authoritative.
- `d628a90`: working battery alerts with saved episode latches/hysteresis/current qualification, Android FULL semantics, connected discharge; ineffective controls retired while keys retained; channel controls sound/vibration.
- `53f9894`: collector failures in rich notification, shared empty/zero labels, fractional current, distinct PendingIntent/open-drain navigation; widget snapshot/freshness/stopped/temperature handling and valid initial layout. Runtime rendering/navigation pending.

## Actual verification
- Baseline dependency metadata FAILED57s: alpha Compose1.13a03 required SDK37.1 while configured37.0. Stable BOM2026.09.00/UI1.12.1 now compiles with compile37/target36/min26, version6.2.7-dev/code736.
- Stage3b debug APK assembled at `app/build/outputs/apk/debug/batstats-debug-6.2.7-dev-universal.apk` plus ABI splits. **Older than current source; final APK builds required.**
- Stage3c45 and3d51 JVM tests passed. Stage3d combined tests/lint/assemble FAILED10m29s:125 MissingTranslation errors/216 warnings; assembly after lint did not run. API28/min26 charge-time error fixed. Real translations required; no suppressions/English placeholder copies.
- Stage4a63 JVM tests + Android compilation PASSED2m6s (`/tmp/batstats-stage4a-retest.log`). Initial test compilation failed because RoomDatabase is not Closeable; explicit try/finally fixed cleanup, assertions unchanged.
- Stage4b1 final73 JVM tests + Android compilation PASSED1m46s (`/tmp/batstats-stage4b1-retest.log`). Covers alert hysteresis/missing data/full/current gaps/vendor direction/connected discharge/delivery retry.
- Stage4b2 final76 JVM tests + Android compilation PASSED2m19s (`/tmp/batstats-stage4b2-tests.log`),0fail/error/skip. Adds engine-to-display screen-off/reset/empty CPU/Doze regressions; actual notification construction test compiled only.
- `python3 scripts/check_migrations.py` and `python3 scripts/check_history_queries.py` PASS actual SQL/schema/indices/row preservation, unique points/rollback, source filtering, overlap and retention. Host SQLite is not Android execution.
- Android migration/import/notification tests compile; **none executed on device yet.** Last XML check76 files parsed; diff checks pass.
- Standard validation: `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon -Dorg.gradle.jvmargs=-Xmx1024m --max-workers=1`.

## Environment and runtime limits
- JDK21; SDK35/36/37/build-tools36; Gradle9.7.1. Host3.8GiB RAM+4GiBswap, limited disk. **Builds and emulator must run sequentially.** Preserve unrelated Java process98543/other AVDs.
- Task AVD `batstats_atd36`, port5580, software `-accel off`,1536MiB, noKVM; sparse6GiB userdata. First boot~85min repeatedly hit watchdog. Setting documented `ro.hw_timeout_multiplier=100` on disposable ATD/restarting zygote allowed one verified `sys.boot_completed=1` before user pause.
- Baseline install attempts failed before package service/boot; third interrupted at pause, installed status unknown. No screenshots/UI/instrumentation/Shizuku runtime success claimed.
- User resumed; emulator105403/session97691 restarted, timeout100 restored, then stopped when combined build/emulator exhaustedRAM/swap. **Currently stopped.** `/tmp/batstats-emulator-resumed.log`. Return adbd to shell before Shizuku shell-mode tests.
- Emulator battery100%,5000mV,25°C,900000µA injected; timeout accommodation is not performance evidence.
- Never edit main/DAO sources while Kotlin/KSP runs: previous stale generated adapters resolved after source changes stopped. Do not print environment/full process arguments; use safe PID/comm inspection.

## Stage4b3 checkpoint — diagnostics
- Fixed-code local DiagnosticLog:60 events/32KiB input, repeat coalescing, bounded actor queue, AtomicFile in noBackupFilesDir, at most one write/minute without alarms/wake locks. Recent events can be lost on process death. Codes only, no command/package/exception payloads.
- Repository/collector/service emit failure/access/recovery events. Dashboard source dialog replaced with scrolling Sources and diagnostics screen: raw units/UTC timestamps, ordinary refresh, distinct local/system windows, limitations, deliberate share chooser. Reports omit internal UUIDs/device identifiers/app lists/raw dumps.
- Six new JVM log/report tests and one Android AtomicFile persistence test authored. Technical report fields currently English; full locale work pending4c.
- Validation PASSED2m27s:82 JVM tests,0fail/error/skip plus Android-test compilation (`/tmp/batstats-stage4b3-tests.log`). Android AtomicFile persistence test compiles, not executed. Diff checked; emulator stopped, no active build.

## Exact next action and remaining work
1. Stage4b3 validated, ready for focused commit; reread this file before4c1 history/session UI corrections.
2. Stage4c history/UI: paginate/filter all history; bounded session-only charts without reloading closed history on live samples; loading/deleted/error states; label imported/legacy continuity and old capacity honestly. Correct dark secondary contrast, settings localized metadata/options, ordinary UI/notification/report strings and real translations across16locale directories. Inspect actual screens when emulator ready.
3. Remaining reliability/efficiency follow-ups: fractional current/rate formatting; parser sub-mAh capacity/overflow edge cases; notification snapshot coherence; alert channel setup failures; remove only confirmed unused background dependencies. Review all store assets/docs/README broken banner link and stale localized claims.
4. Final lint/debug/nondebug/Android test APKs; API36/Shizuku/Binder disconnect/reconnect/UI/large fonts/dark-light/screenshots as environment supports. Do not silently skip failing assertions; distinguish compilation, synthetic data and actual execution.
5. Prepare local phone-launchable manual Actions artifact/test-report workflow, signing/update guide and PR description. Existing push/PR workflows auto-run; no GitHub writes/Actions without explicit approval after concrete local validation.

Audit findings/coverage: AUDIT_REPORT.md. Platform contracts/privacy: docs/PLATFORM_NOTES.md. No pending user approval blocks local work.
