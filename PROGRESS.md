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
- Fresh stage5c outputs: `app/build/outputs/apk/{debug,preview}/app-universal-{debug,preview}.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. These include recovery, complete locales, configuration-aware resource fixes and all25 Android test methods. Ignored `artifacts/` recollected/verified from clean dd7f2c0 (build-info/checksums/signatures). Packages `.debug`/`.preview`, code737/base736, target36/min26; original release key unavailable.

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

- `ba68c88` measurement documentation and handoff reconciliation.
- `4d08bdb` stage5d: signed current artifacts collected; failed snapshot/disk recovery documented; shared ATD SDK restored; cold-start launch and watchdog100/shell2000 verified. Android boot/runtime checks continue.
- `1cecee6` store copy and runtime/GitHub-policy findings.
- Stage5f (this commit): reuse APK workflow from push/PR CI with mandatory API36/Shizuku tests; no CI signing secrets; actionlint passes. Hosted execution unapproved.
- `dd7f2c0` stage5c: configuration-aware Compose resources/current-window layout, dashboard recovery test, guarded font cleanup and clean device-report reruns.

## Actual verification
- Stage5c full command PASSED in16m (`/tmp/batstats-stage5c-retest.log`):94 tests in EACH Debug and Preview,0fail/error/skip; full lintDebug/lintPreview0errors (201/200warnings); Debug, optimized nondebuggable Preview and Android test APK assembly. All25 Android test methods compile, **none executed**. Initial5c failed7 Compose resource-access errors; fixed without suppression. Previous translation errors are gone.
- Host migration/history SQL checks,493-key Spanish/Turkish completeness/format/newline/XML checks, actionlint1.7.12 and bash syntax pass. Native-speaker review not performed. Lint warnings are mostly unused resources/plural suggestions/dependency versions; application-context warnings and intentional target36 reviewed.
- No current app UI/Binder/widget/alert rendering is verified. Runtime and actual screenshots remain required.
- Actual historical results/commands/failures: `docs/VALIDATION.md`; measurement limits: `docs/MEASUREMENTS.md`, `docs/PLATFORM_NOTES.md`; signing/phone workflow: `docs/BUILD_AND_INSTALL.md`. PR draft validation must be finalized before publication.

## Current environment and emulator
- JDK21, SDK37/target36/min26, Compose BOM2026.09/UI1.12.1, Gradle9.7.1/AGP9.4. Host3.8GiB RAM+4GiBswap, noKVM. **No Gradle with emulator.** Build flags: `--no-daemon -Dorg.gradle.jvmargs=-Xmx1024m -Pkotlin.compiler.execution.strategy=in-process --max-workers=1`.
- Full AVD `batstats_api36`, port5580, PID132824/session27136, software rendering,384×800/dpi160, enforced2560MiB RAM. Cold boot active. Startup log `/tmp/batstats-api36-cold-stage5d2.log` (no snapshots). No guest app installed.
- Snapshot restore failed with RAM length mismatch (`0x4000000 != 0x10000`, error-22), then emulator fell back to cold boot. Removed unusable task snapshot after preserving metadata in `/tmp/batstats-failed-snapshot-evidence` and logs. Do not try to restore it again.
- Disk preflight initially failed; reclaimed245MiB redundant generated APKs and all reproducible intermediates. Universal/test APKs and reports retained; large Preview mapping/usage files compressed losslessly to .gz. Failed snapshot stayed open after deletion; stopping the emulator released its1.8GiB.
- Watchdog setup completed in session4313: property100 and shell UID2000 verified (`/tmp/batstats-api36-cold-setup-retry.log`). First helper saw a transient root-restart error; its retry verified state. Package Manager later registered; baseline install was refused because boot remained incomplete. Latest evidence shows first-boot ART verification progressing successfully for system packages. No app started.
- Discovered other AVDs reference the ATD image removed earlier. Restored shared `system-images;android-36;google_atd;x86_64` r01 successfully in session36081; log `/tmp/batstats-restore-shared-atd-retry.log`. First attempt ran out of disk because failed snapshot was still open; that attempt cleaned its temporary files. Keep other AVDs/shared SDK images untouched. AOSP36 default image exists but was not installed because of space; continue current full boot.

## Incomplete work and exact next action
1. Request explicit GitHub approval for publishing/updating codex/android16-reliability in akane599/BatStatsAKA, opening/updating its draft PR, and running/rerunning Android validation (including automatic push/PR checks and artifact uploads). Host validation and local workflow preparation are complete; live device verification needs a practical runtime environment. No merge/release/settings authority is requested. While approval is pending, continue local boot checks; no remote writes without an answer.
2. Continue emulator readiness checks (latest `/tmp/batstats-api36-dexopt-logcat.txt`, capture session33488 `/tmp/batstats-api36-cold-logcat6.txt`). Watcher18571 ended after30minutes without boot completion; ART verify jobs are progressing. Watchdog100/shell2000 verified. Shared ATD restored; do not remove it. APK source remains dd7f2c0; no rebuild needed for docs/CI/store copy. No Gradle alongside emulator.
3. Finish boot; install/inspect supplied baseline then current Debug/test/Preview APKs. Run actual API36 ordinary/Room/recovery/UI/FGS/notification and Shizuku checks using prebuilt APKs/ADB (no Gradle alongside emulator). Shizuku setup asserts emulator/shell2000 and official APK checksum; unavailable service fails its suite, never silently skips.
4. Inspect actual captures, loading/empty/partial/error/recovery, large text, dark/light, translated/RTL and navigation states; investigate failures without weakening assertions. Dashboard recovery inputs are scripted, not physical measurements. Physical Samsung/AOD/current calibration/capacity/energy overhead remain unverified.
5. Finalize actual runtime/signature/checksum metadata, AUDIT_REPORT, this record, validation and PR description; focused local commits after each stage. Any merge/release/settings change needs separate explicit approval. Manual workflow must reach default branch before Run workflow appears. Pending publication approval does not block local emulator investigation.

## Current validation checkpoint
- Current software-emulator boot is still incomplete after the30-minute observation window (over45minutes total). Package Manager registered, but baseline install session49121 failed `Error: device is still booting` (`/tmp/batstats-api36-baseline-install.log`). No app installed, no device test run or skipped.
- System app scan/extraction finished; debuggerd trace `/tmp/batstats-api36-systemserver-trace.log` showed active package configuration. Later ArtService logs show successful system-package verify jobs, some taking minutes. `pm.dexopt.first-boot=verify`; no compilation filters or test assertions changed. ART shell cancellation supports shell/background jobs, not this boot task; no cancellation attempted.
- GitHub read-only inspection: origin akane599/BatStatsAKA; default main; tokens read; automatic review approvals disabled. Existing Dependabot policy constraint recorded in AUDIT_REPORT. No GitHub mutation or approval yet.
- CI reuse/static validation is complete. Proposed publication scope includes branch/draft PR updates and validation runs needed for this task. User approval is the next required step for remote execution, not for continued local work.
