# BatStats Development Progress

## Baseline and scope
- Starting revision: `76bc831328572c81717b97ffb0e280b10b14b8ad` (6.2.6); branch: `codex/android16-reliability`.
- Initial working tree contained only the previously requested untracked `AGENTS.md`; incorporated with authorization. Supplied APK downloaded from the 6.2.6 GitHub release to `/tmp/batstats-artifacts/baseline-6.2.6.apk`; no assumed completed improvements.
- Goal: accurate, reliable, efficient Android 16/API 36 monitoring, Shizuku primary; preserve ordinary/ADB/Root modes and useful notification. Improve UI, history, diagnostics, export and delivery.
- No physical Samsung device; emulator/synthetic results cannot validate real current, capacity, battery overhead or One UI lifecycle behavior.
- Local work authorized. All GitHub mutations and Actions execution require explicit approval after local validation. No remote writes authorized or performed.

## Stages
0. Workflow record: complete (`31c9a55`); persistent instructions saved before implementation.
1. Baseline inventory and critical-path audit: complete; remaining UI/resource review continues in its implementation stage.
2. Build/platform and privileged access foundations: implementation checkpoint; bounded command/pipe protocol, Shizuku-first single-backend reads, binder cleanup, partial-failure reporting, specialUse FGS and API 36 target implemented; 8 JVM tests authored, verification pending; full build runs separately while work proceeds.
3. Measurements, session accounting, storage and advanced analysis: next; implement pure tested observation model first.
4. Efficiency, notification, visual overhaul, history/diagnostics/export: pending.
5. Automated/device/visual validation, APKs, workflow and PR preparation: pending.

## Validation and decisions
- Inspected initial status/revision, contributor guide, build declarations and tool availability.
- Java 21, Android SDK 36/37.0, emulator 37.1.11 and GitHub CLI are installed; baseline dependency failure reproduced.
- No existing tests/test dependencies. Initial `:app:assembleDebug :app:lintDebug` failed `:app:checkDebugAarMetadata` after Kotlin/Java compilation. Stopped remaining expensive D8 work (task-owned daemon PIDs 67765/69114) to investigate failure. Isolated pristine `git archive 76bc831` metadata reproduction FAILED in 57s: five Compose 1.13.0-alpha03 artifacts require compile SDK 37.1 (log `/tmp/batstats-baseline-metadata.log`). No baseline source APK/lint success. Initial compile included unused new command files; original tracked behavior unchanged until after failure.
- User-requested UX improvements are in scope even though generic audit skill normally only records enhancements.

## Outstanding issues and next action
- APK: package `org.mlm.batstats`, versionCode 735 (source 734), versionName 6.2.6, target/compile 37. SHA-256 `4009bc1f13d7c871ddc1c61f3696ed224f1ad5fbfe547ed452fc2aeaca86b3c5`. Certificate SHA-256 `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`; signature verifies. Original signing key unavailable; development builds must coexist unless original key is supplied outside Git.
- Environment: Java 21, SDKs 35/36/37.0; emulator 37.1.11. Created task-only AVDs `batstats_api36`/`batstats_atd36` on port 5580. Emulator enforces 6 GiB userdata. Precreated sparse ext4 userdata bypassed initial allocation; host lacks KVM, software boot reached adb offline. Task emulator PID 68819 stopped to prioritize baseline build (only ~3.8 GiB host RAM). No device tests yet. Relaunch task-only ATD with `-accel off -memory 1536 -port 5580 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect`.
- Current build fix: alpha Compose BOM selects UI 1.13.0-alpha03 requiring compile SDK 37.1; baseline declares 37.0. Switched to stable BOM 2026.09.00 (verified UI 1.12.1 metadata requires 37.0); target SDK now 36, compile 37 retained for dependencies. Reduced Gradle heap/workers to fit host. Updated `:app:checkDebugAarMetadata` now passes; first JVM build failed on experimental wavy indicators/MaterialExpressiveTheme unavailable in stable Material 3. Replaced those calls with stable Material equivalents; rerunning tests. Log `/tmp/batstats-stage2-tests.log`.
- Next: commit platform/access checkpoint, re-read this file, then implement pure validated readings and monotonic observation engine with regression tests. Build `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` is still running (session 75170, `/tmp/batstats-stage2-build.log`); compile/DEX in progress. `CommandOutput` was strengthened during that compile, so repeat JVM/build validation after the change; do not attribute this intermediate build to an exact commit. Tests/build/lint have NOT passed yet. Emulator stopped (PID 70489) after 8 minutes offline to free RAM; next launch needs `-show-kernel`, 1536 MiB and no concurrent heavy build. All `/tmp/batstats-*-stage.py` scripts have already run: do not rerun.
- Stage 2 service reconfiguration on access change and all polling ownership will be completed with shared observation engine in stage 3 (dependency). Remaining core work includes nullable readings, monotonic intervals, migrations/imports, truthful advanced parser, bounded diagnostics, UI/notification overhaul, exports/retention/alarms, tests/workflow/delivery.
- Git identity absent: use per-commit `git -c user.name=Codex -c user.email=codex@openai.com commit`; do not change global configuration.
