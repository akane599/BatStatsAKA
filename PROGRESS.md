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
2. Build/platform and privileged access lifecycle: pending.
3. Measurements, session accounting, storage and advanced analysis: pending.
4. Efficiency, notification, visual overhaul, history/diagnostics/export: pending.
5. Automated/device/visual validation, APKs, workflow and PR preparation: pending.

## Validation and decisions
- Inspected initial status/revision, contributor guide, build declarations and tool availability.
- Java, Android SDK tools, emulator and GitHub CLI are available; installed versions/build feasibility not yet verified.
- No existing tests/test dependencies. Untouched `:app:assembleDebug :app:lintDebug` running (log `/tmp/batstats-baseline-build.log`, session 68296); dependency/configuration phase, no result yet.
- User-requested UX improvements are in scope even though generic audit skill normally only records enhancements.

## Outstanding issues and next action
- APK: package `org.mlm.batstats`, versionCode 735 (source 734), versionName 6.2.6, target/compile 37. SHA-256 `4009bc1f13d7c871ddc1c61f3696ed224f1ad5fbfe547ed452fc2aeaca86b3c5`. Certificate SHA-256 `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`; signature verifies. Original signing key unavailable; development builds must coexist unless original key is supplied outside Git.
- Environment: Java 21, SDKs 35/36/37.0; emulator 37.1.11. Created task-only AVDs `batstats_api36`/`batstats_atd36` on port 5580. Default 6 GiB userdata exceeds free disk; trying sparse 2 GiB userdata in task-only ATD AVD. No device tests yet.
- Next: collect untouched build result and baseline emulator screenshots; then stage 2 platform/access fixes with regression tests. Follow with pure monotonic observation accounting shared by notification/dashboard, nullable readings, and Room migrations. Git identity absent: use per-commit `git -c user.name=Codex -c user.email=codex@openai.com commit` (no global config changes).
