# BatStats Development Progress

## Baseline, scope and authorization
- Start: `76bc831328572c81717b97ffb0e280b10b14b8ad` (source6.2.6/code734). Branch: `codex/android16-reliability`. Initial untracked requested AGENTS.md incorporated; no unrelated changes.
- Supplied APK: `/tmp/batstats-artifacts/baseline-6.2.6.apk`, package `org.mlm.batstats`, name6.2.6/code735, target37. SHA256 `4009bc1f13d7c871ddc1c61f3696ed224f1ad5fbfe547ed452fc2aeaca86b3c5`; verified signerSHA256 `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`. Original key unavailable. Debug `.debug` coexists; prepare separately signed non-debug `.preview` too.
- Goal: accurate, efficient Android16/API36 monitoring, Shizuku primary; ordinary/ADB/Root modes, useful rich notification, modern accessible UI, reliable history/export/diagnostics, tests/APKs/workflow/PR description.
- Local work/dependencies/emulators/branches/commits authorized. **No GitHub writes, pushes or Actions approved/performed.** Prepare concrete publication scope only after local validation; existing push/PR workflows may auto-run.
- No physical Samsung available; do not request phone testing. Software emulator/synthetic tests cannot establish real current accuracy, capacity, energy savings or One UI lifecycle reliability.
- Follow AGENTS.md stage/commit/recovery rules. User-requested UX enhancements override generic audit skill scope restriction. Commit identity per-call: `git -c user.name=Codex -c user.email=codex@openai.com commit`; no global identity changes.

## Completed checkpoints
- `31c9a55`: persistent instructions and progress before implementation.
- `8ebc074`: APK/source baseline and prioritized confirmed defects (AUDIT_REPORT.md).
- `6e7e801`: API36 specialUse FGS, boot handling, stable Compose dependencies, Shizuku-first bounded command/framed-pipe transport, no backend fallback on failed reads.
- `08307d4`: validated nullable units, monotonic ObservationEngine and stable counter-based ETA.
- `e0d1eb3`: repository bounded single-owner actor/shared observation, auto sessions and DB4 nondestructive migrations/unique active session; shared notification/dashboard/drain periods; timestamp/gap-aware charts and responsive navigation; removed unsupported80/20mA per-app heuristic and unused duplicate hourly dumper. Existing detailed UID capabilities retained. Raw samples capped100k; representative charts~360 points.

## Actual validation
- Pristine source metadata FAILED57s: alpha Compose1.13a03 needs compile37.1 but source37.0. Stable BOM2026.09.00/UI1.12.1 metadata passes compile37.0; target36, code736/name6.2.7-dev. Stable Material3 replaces unavailable experimental components.
- Stage3b `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` PASSED5m35s, **29 JVM tests,0fail/skip**; migration instrumentation sources compile, not executed. `/tmp/batstats-stage3-verify.log`. Earlier stale KSP DAO adapter failure resolved by rebuilding after DAO edit (do not edit main source while compile runs).
- `python3 scripts/check_migrations.py` PASSED actualSQL1/2/3→4 against Room4 schema/indices/PKs, row preservation, sentinel cleanup and active uniqueness. Android migration execution still pending.
- Stage3b `:app:assembleDebug` PASSED: `app/build/outputs/apk/debug/batstats-debug-6.2.7-dev-universal.apk` (+ABI splits). Subsequent `:app:lintDebug` FAILED one error: API28 computeChargeTimeRemaining called at min26. Guard fixed in current stage; lint not yet rerun. Warnings await review. Combined log `/tmp/batstats-stage3-apk.log`,11m10s.
- Advanced parser regression baseline: **41 JVM tests,12 failures** (12 new producer-based synthetic cases). Confirmed job/sync swapped fields, Bluetooth layout, missing identity/activity, invalid energy, false zero state, Doze maintenance and wakelock types. Current fix verification pending. First fixed compile required adapting a now-nullable test argument (assert non-null then exact value); no assertion weakened.

## Stage3c checkpoint (ready for local commit)
- Corrected Android16 ADB gate: DUMP + PACKAGE_USAGE_STATS permission + allowed/default usage app-op; BATTERY_STATS alone is insufficient.
- Current non-consuming `dumpsys batterystats -c --charged` replaces `--checkin` (which may consume saved completed reports). Helper whitelist changed. Output bounded8MiB; parser discards included history.
- Reworked producer-backed v9 parser: nullable unsupported values, valid epoch/window, shared/multi-user UID maps, appUID classification, jobs/sync/background timers, alarm wakeup counts, f/p/w wakelocks, mobile-active µs→ms, global Bluetooth, UID activity/process/sensor/CPU frequencies, Android component estimates. Duplicate UID energy rows rejected; proportional total not added to base.
- Advanced screen rewritten with source/window/error visibility, complete scrolling lists and app search/details, separate current state, attribution/capacity limitations, resource strings. Removed cycle-derived health. Kernel collectors still need real privileged reads/units/errors (current UI is not final). Restore explicit confirmed system-stats reset control during next access/UI pass.
- Collector validates core epoch and stages results until access generation/source agree. Need finish stale access ViewModel, interruption/stop cancellation, diagnostics, failure publishing and reset concurrency.
- Standalone pure suite PASSED45 tests (16 parser cases +29 previous),1.07s JUnit runtime, `/tmp/batstats-parser-fixed-tests.log`. Full Gradle `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` PASSED4m40s:45 tests,0fail/error/skip; Android test sources compile. `/tmp/batstats-stage3c-tests.log` (heap1024m/workers1).

## Environment and device validation
- Java21, SDK35/36/37.0 +build-tools36, Gradle9.7.1. Host~3.8GiB RAM/4GiBswap, disk~2.4GiB free. Do not remove unrelated files/processes/AVDs.
- Task emulator `batstats_atd36`, port5580, PID73885/session9765, software `-accel off`,1536MiB, no KVM. Sparse6GiB userdata created only in own AVD to fit disk. Earlier own emulator stopped.
- ADB reports device, boot still incomplete (latest package scan completed, system services starting). Baseline install initially FAILED package service absent; no UI/device tests/screenshots yet. Package service is now available; baseline install retry FAILED: device is still booting (`/tmp/batstats-baseline-install2.log`). Kernel logs show zygote killed twice (seconds1520/2455); investigate slow-host watchdog before another boot attempt. Logs `/tmp/batstats-emulator-kernel.log`, `/tmp/batstats-emulator-latest.txt`; shell/logcat commands can take >30s. Continue other work while boot progresses; do not assume boot success.
- Battery healthd100%,5000mV,25°C,900000µA are emulator-injected values, not measurements.

## Remaining work / exact next action
1. Commit stage3c, reread this file, then implement stage3d stale-access ViewModel, reset/permission UI and promptly cancelled command transport plus actual privileged kernel collection. Restore app sorting and kernel details using validated values. Re-run relevant tests/lint and commit the focused stage.
2. Fix RootStatsCollector actual su reads, nullable vendor data and bounded source diagnostics; investigate modern wakeup_sources replacement. Primary kernel docs consulted for µ-units, capacity limitations and CPU time_in_state10ms units. Shizuku runtime/disconnect/reconnect/cancellation tests still pending.
3. Fix transactional bounded imports/exports with units/windows, clear/reset coordination, retention/unused settings/alarms. Finish source/freshness diagnostics, resource extraction, notification rich layout/values and all-screen/light/dark/large-font validation.
4. Complete automated/device/UI/Shizuku tests where emulator supports; investigate failures. Build verified debug+nondebug APKs, signatures/update guidance, local manual Actions workflow/test artifacts, PR description. Reconcile full audit coverage/status. No remote mutation without explicit approval.
