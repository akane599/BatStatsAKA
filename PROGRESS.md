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
- `6bb4bf1` stage5f: reuse APK workflow from push/PR CI with mandatory API36/Shizuku tests; no CI signing secrets; actionlint passes. Hosted execution unapproved.
- `dd7f2c0` stage5c: configuration-aware Compose resources/current-window layout, dashboard recovery test, guarded font cleanup and clean device-report reruns.

- `af1cd66` native packaging evidence/offline guides; `5f0ff85` prebuilt ADB runner and six runner-result regressions.

## Actual verification
- Full source validation PASSED in16m at dd7f2c0: **94 JVM tests per Debug/Preview variant**, zero failures/errors/skips; full lint **0 errors,201/200 warnings**; Debug, optimized nondebuggable Preview and Android-test APKs assembled. Log `/tmp/batstats-stage5c-retest.log`. Initial7 Compose resource errors fixed without suppression. APK source has not changed since that build.
- Host migration/history SQL,493-key Spanish/Turkish XML/format checks, actionlint1.7.12 and bash syntax pass. Six additional synthetic Python runner-output regressions pass. Native-speaker review remains unavailable.
- Universal signatures/hashes verified from clean dd7f2c0; metadata in `artifacts/build-info.json`. Both ZIP/PT_LOAD16KiB alignment checks pass. Stricter graphics-path RELRO-end check flags0x6000/0x7000; no writable data overlaps the rounded protection range, but16KiB runtime remains unverified. No vendor binary changed.
- **25 Android test methods compile; zero executed.** No BatStats UI, Binder, notification/widget/alert delivery or Android migration execution is verified. Synthetic inputs and host results do not establish device behavior.
- Actual commands, initial failures and limitations: `docs/VALIDATION.md`. Measurement contract: `docs/MEASUREMENTS.md`; platform/Samsung boundaries: `docs/PLATFORM_NOTES.md`.

## Environment and completed local attempts
- JDK21, compile SDK37/target36/min26; Gradle9.7.1/AGP9.4. Host3.8GiB RAM+4GiBswap, **noKVM**. Do not run Gradle with an emulator. Constrained flags: `--no-daemon -Dorg.gradle.jvmargs=-Xmx1024m -Pkotlin.compiler.execution.strategy=in-process --max-workers=1`.
- Task AVD `batstats_api36` (API36 google_apis,x86_64,384×800,dpi160) is **stopped**. Last PID132824/session27136 ended after an explicit emulator-console stop; wrapper reported139 during shutdown. No task build/test process remains. Userdata/configuration retained; no snapshot saved.
- Full cold boot progressed through ART verification and launcher/window initialization, but sys.boot_completed stayed absent after about68minutes. Both baseline installations were rejected as still booting; no BatStats/Shizuku APK installed. Final window dump returned a service timeout. One late screenshot was captured and inspected: blank pale background, not an app screen. No app compatibility conclusion follows.
- Evidence: `/tmp/batstats-api36-cold-stage5d2.log`, `...-baseline-install2.log`, `...-boot-policy.txt`, `...-window-boot.txt`, `...-boot-screen2.png`, `...-stop.log` (same `/tmp/batstats-api36` prefix). Watchdog100/shellUID2000 were verified. Animations set to zero. Expired task-owned crash_dump64 helper was stopped after host timeout; no app/system-server process was deliberately killed.
- Earlier snapshot restore failed (RAM length mismatch/error-22); unusable task snapshot removed with evidence preserved. Freed only redundant task APKs/reproducible intermediates; universal/test APKs/reports retained. Large Preview mapping/usage files are `.gz`.
- Shared ATD SDK r01 was restored after discovering other AVD dependencies. **Preserve other AVDs and shared SDK images.** ATD is unsuitable for full SystemUI/notification visual review.

## Delivery and unresolved work
- Installable files: `artifacts/app-universal-debug.apk`, `artifacts/app-universal-preview.apk`; checksums/signatures and offline guides alongside. Separate `.debug`/`.preview` packages install beside supplied org.mlm.batstats. Original key unavailable: no in-place update of supplied APK. Preview updates require a matching certificate. CI ephemeral certificates may differ between runs.
- Prepared PR: `docs/PR_DESCRIPTION.md` (draft/device validation pending). Workflow: `.github/workflows/build-apk.yml`; push/PR CI reuses it with mandatory API36 ordinary/Shizuku tests. No stable signing secrets passed by CI. Manual phone workflow must reach main before GitHub displays Run workflow.
- Still needed: actual API36 UI/empty/error/recovery/large-font/notification/widget tests, Room/device checks and real shell-UID2000 Shizuku authorization/loss/reconnection tests. The `--prebuilt` runner avoids Gradle memory competition and fails on incomplete boot, zero/failed/skipped/crashed/incomplete test results. No assertion weakened or compatibility failure silently skipped.
- Physical Samsung current calibration, capacity, AOD/background restrictions, vendor kernel access and energy overhead remain unverified; no phone required/requested.16KiB runtime and native-speaker locale review remain open.
- Existing Dependabot auto-review/merge workflow is constrained by repository policy (automatic reviews disabled); no settings changed. This is separate from app validation.

## Outstanding approval and exact next action
**GitHub approval requested asynchronously, still unanswered. No GitHub mutation or Actions run has been performed.** Proposed scope: push/update codex/android16-reliability to akane599/BatStatsAKA; create/update its draft PR; run/rerun validation for verified task fixes. Push and PR events may each automatically start the shared APK/Android16/Shizuku workflow and upload artifacts. No merge, release, settings changes or cancellation authority requested. Never treat elapsed time or a suggested option as approval.

Next: obtain the explicit answer to that publication/validation request. If approved, push the current committed branch, open a draft PR with `docs/PR_DESCRIPTION.md`, inspect actual CI device reports/screenshots, investigate failures locally and update records/commits. If continuing locally, start the retained task emulator alone, verify complete boot, inspect the baseline, then run `ANDROID_SERIAL=emulator-5580 bash scripts/check_android_device.sh --prebuilt`; no success is presumed. Do not restart implementation after a new session without the recovery checks above.
