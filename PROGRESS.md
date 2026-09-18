# BatStats Development Progress

## Scope and authorization
- Starting revision `76bc831328572c81717b97ffb0e280b10b14b8ad` (source6.2.6/code734). Branch `codex/android16-reliability`. No unrelated changes at start.
- Deliver accurate, efficient Android16/API36 monitoring with Shizuku primary and ordinary/ADB/Root preserved; useful rich notification, accessible UI, reliable history/diagnostics/export, installable debug/nondebug APKs, signing guide and locally prepared PR/manual workflow.
- Follow AGENTS.md: focused stages; update this record and commit relevant work after each; reread before next. After compaction read this and AUDIT_REPORT, inspect status/recent commits/all uncommitted diffs and reconcile before implementation.
- Local task edits/refactors/branches/commits/dependencies/builds/tests/disposable emulators authorized, within environment permissions. Preserve unrelated work and secrets. **No GitHub writes/pushes/Actions approved or performed.** Finish local validation before proposing exact remote actions and automatic triggers.
- No physical Samsung; do not request phone testing. Distinguish injected test inputs, reported values, estimates and missing readings. No unsupported hardware/energy-saving claims. UI improvements are explicitly authorized beyond the generic audit skill.
- Commit identity per command: `git -c user.name=Codex -c user.email=codex@openai.com commit`. Never print environment/full process arguments.

## Baseline and intermediate artifacts
- Supplied APK: `/tmp/batstats-artifacts/baseline-6.2.6.apk`, `org.mlm.batstats`, code735/target37/min26. SHA256 `4009bc1f13d7c871ddc1c61f3696ed224f1ad5fbfe547ed452fc2aeaca86b3c5`; signerSHA256 `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`. APK-dist universal code=source+1; source734/APK735 does not establish a different revision. Original key unavailable.
- Official Shizuku13.6 APK: `/tmp/batstats-artifacts/shizuku-13.6.0.apk`; SHA256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`; signerSHA256 `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`. Not installed yet.
- Stage5a artifacts in ignored `artifacts/`: Debug25MiB and optimized/nondebuggable Preview2.9MiB. Both signerSHA256 `93e78296c5eb4a5a2970679e96454de0c6979918c8f248765fddc1eb87ace8dd`, separate `.debug`/`.preview` packages, code737/base736, target36/min26. Stage5a SHA256 Debug `3c6166876754fcdf5a4068422f41abfea34ea13db16655b511becf86250893ca`; Preview `44209dc41b2f475bdc6209447885d97055290e1783f68fd484a27099ff98a2f7`. These are **intermediate**, not current final builds.
- Stage5b fresh Debug/test APKs: `app/build/outputs/apk/debug/app-universal-debug.apk`, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. They include recovery/device suites but predate locale changes and pending dashboard recovery test. Preview output predates4d2. Keep actual artifact provenance; regenerate all final artifacts/signatures/metadata later.

## Completed focused commits
- `31c9a55` instructions; `8ebc074` baseline audit; `6e7e801` stable Compose/API36 specialUse FGS/boot/source-aware bounded access.
- `08307d4` nullable units/monotonic observation/stable ETA; `e0d1eb3` single writer/automatic sessions/DB4/nondestructive migrations/shared totals. No fixed4000mAh, cycle-health percentage or arbitrary80/20mA per-app heuristic.
- `e95273c` Android16 parser/ADB gate/non-consuming current checkin; `3584abc` request-ID cancellable Shizuku transport and bounded real Root/kernel reads.
- `a2b9021` bounded transactional history/import/export/dedup/clear gate/retention/settings-only backup; `d628a90` validated persistent alert episodes and effective settings.
- `53f9894` stable rich notification/navigation/widget freshness; `1f32614` bounded local diagnostics/provenance/sharing; `4ce8957` full paged history/bounded linked charts/legacy evidence.
- `9c07740` resource-backed settings/validated import/contrast; `0a1e566` shared localized monitoring labels with endpoints/responsive cards/tiny nonzero precision.
- `50d766e` live-capture freshness/fractional capacity/direction conflicts/notification recovery; `46b6962` Preview signing/manual APK workflow/release gate/unused dependencies and permission removal.
- `454a156` source-specific collection errors/safe polling/no invented intervals without state events/startup recovery/full captured-input matching.
- `8ccbcaf` Android16 navigation/font200/FGS notification and real Shizuku integration suites; required ordinary and Shizuku workflow phases with separate reports.
- `208d9b8` truthful locale resources: all17 original locale files were English duplicates. Consolidated fallback and added complete493-key Spanish/Turkish resources. No genuine translation removed and no lint suppression. Earlier assumption that directories represented16 translated languages was disproved by comparing every baseline value.

## Actual verification
- Latest JVM run4d2:94 tests in EACH Debug and Preview,0fail/error/skip, plus Android compilation PASSED2m32s (`/tmp/batstats-stage4d2-tests.log`). Tests are synthetic inputs, not measurements.
- Host migration/history SQL checks pass. Resource completeness/format/newline/XML checks and direct AAPT2 compilation pass for Spanish/Turkish. actionlint1.7.12 passes modified workflows; Python Shizuku setup argument parsing/bash syntax pass.
- Stage5a Debug/Preview/test assemblies and fatal lint passed21m54s. Stage5b Debug/test assemblies with new device suites passed1m59s (`/tmp/batstats-stage5b-device-build.log`). Full lint last failed125 missing translations/216 warnings before locale correction; rerun required, not suppressed.
- **No Android test has executed and no current app UI/Binder/widget/alert rendering is verified.** Sources compile through5b. The pending dashboard recovery test is not yet compiled. Native-speaker translation review not performed.
- Full commands/history/failures: `docs/VALIDATION.md`. Measurement contracts and limits: `docs/MEASUREMENTS.md`, `docs/PLATFORM_NOTES.md`. Build/signing/phone workflow: `docs/BUILD_AND_INSTALL.md`. PR draft exists but validation section must be finalized before publication. Documentation/handoff reconciliation is the commit containing this checkpoint; pending dashboard test remains outside that documentation commit.

## Current environment and emulator
- JDK21, SDK37 compilation/target36/min26, stable Compose BOM2026.09/UI1.12.1; Gradle9.7.1/AGP9.4. Host3.8GiB RAM+4GiBswap,~1.9GiB disk free. **No Gradle JVM with emulator; never edit main/DAO during KSP/Kotlin.** Builds: `--no-daemon -Dorg.gradle.jvmargs=-Xmx1024m -Pkotlin.compiler.execution.strategy=in-process --max-workers=1`.
- Full AVD `batstats_api36`, port5580, PID126067/tool session27784, software/noKVM,384×800/dpi160, enforced2560MiB RAM. Sparse6GiB userdata initialized from SDK data directory. Log `/tmp/batstats-api36-full.log`; recent logcat `/tmp/batstats-api36-logcat.txt`.
- Initial full launch failed disk preflight. Reclaimed only disposable task ATD AVD and redundant generated ABI APK copies; retained universal APKs/logs. ATD is unsuitable for notification/visual checks because it omits SystemUI. Its one previous successful boot did not validate the app; original baseline install had been interrupted.
- Full emulator boot remains incomplete: zygote running, Package Manager not found. Watchdog property100 and adbd shell UID2000 verified. Too-short initial ADB setup loop126388 was stopped and replaced by successful one-shot setup (session62115 completed). No build running. Preserve unrelated projects/processes/AVDs.

## Incomplete work and exact next action
1. Continue monitoring full-emulator initialization with bounded ADB/logcat checks; investigate actual boot failures. Once Package Manager/boot ready, install and inspect supplied baseline, then stage5b Debug/test APKs. Record actual baseline/runtime outcomes. Existing5b APK lacks Turkish resources, so localization test needs a fresh build before execution.
2. Working tree: `DashboardRecoveryDeviceTest.kt` added; shared recovery test fixture visibility widened to internal. It renders the production dashboard with isolated scripted missing/error/recovered/partial inputs; **not compiled**. Compile it with locale changes when the emulator can be stopped/snapshotted safely. Do not run Gradle concurrently.
3. Run actual API36 ordinary/Room/recovery/UI/FGS/notification and Shizuku suites. Shizuku preparation script asserts emulator/shell2000 and verifies official APK checksum. A missing/incompatible service fails its suite, never silently skips. Inspect captures, loading/empty/partial/error/recovery, large text, dark/light, Spanish/Turkish/RTL and navigation; fix failures without weakening assertions.
4. Full Debug/Preview lint/JVM/SQL/resource checks; fresh Debug/Preview/test APKs and final install/runtime/signature/checksum metadata. Review current screenshots/store assets. Physical Samsung/AOD/current calibration/capacity/energy overhead remain explicitly unverified.
5. Finalize AUDIT_REPORT, this record, actual validation and PR description. After all possible local work, ask for exact GitHub approval with push/PR automatic CI triggers. The new manual workflow must reach default branch before Run workflow appears. No pending approval blocks local work.
