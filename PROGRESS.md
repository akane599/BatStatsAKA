# BatStats Development Progress

## Scope, baseline and authorization
- Starting source: `76bc831328572c81717b97ffb0e280b10b14b8ad`; branch `codex/android16-reliability`. No unrelated changes at start. Current history is authoritative.
- Deliver accurate, efficient Android16/API36 monitoring with Shizuku primary and ordinary/ADB/Root preserved, useful rich notification, accessible UI, reliable history/diagnostics/export, Debug/Preview APKs, signing guide, draft PR and phone build workflow. No physical Samsung is available; do not request phone testing or claim physical accuracy/energy savings from emulation.
- Follow AGENTS.md: update this record and commit after focused stages; reread before the next stage. After interruption read this/AUDIT_REPORT, inspect status/recent commits/all uncommitted diffs before implementing. Keep detailed validation evidence in docs/VALIDATION.md.
- Local edits/refactors/commits/dependencies/builds/tests/disposable emulators are authorized within environment permissions. Preserve unrelated files and secrets. Commit identity: `git -c user.name=Codex -c user.email=codex@openai.com commit`.
- **Approved:** push/update this branch, open/update draft PR, run/rerun validation for task fixes in akane599/BatStatsAKA. Push and PR events automatically run CI. **Excluded:** merging, releases, repository settings and workflow cancellation. No repeated approval needed for the approved scope.
- Supplied APK: `/tmp/batstats-artifacts/baseline-6.2.6.apk`, original package `org.mlm.batstats`, code735/target37/min26. SHA256 `4009bc1f13d7c871ddc1c61f3696ed224f1ad5fbfe547ed452fc2aeaca86b3c5`; signer `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`. APK-dist universal +1 explains source734/APK735. Original key unavailable.

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

## Latest completed stages
- `91c6053`: approved publication; [draft PR1](https://github.com/akane599/BatStatsAKA/pull/1). `32a01ae`: corrected SDK package to platforms;android-37.0 after actual CI/local failure. `dc9ee2f`: one-worker/in-process/3GiB CI after hosted packaging OOM.
- `ff792f4`: separated APK-dist outputs from AGP inputs after connected-test task validation failure; all variants verified locally. Both subsequent hosted builds passed; their device failures led to the fixes below.
- `220b4d3`: populated history/chart/deletion UI regression with explicitly scripted imported samples;26 device methods compile.
- `a01e24c`: fixed runtime Material3/Foundation ABI mismatch by enforcing the stable BOM; verified45 referenced Material3 members against stable1.4.0. Guarded NavDisplay back handling; corrected receiver overload and JUnit void-return fixtures. Device phases now run independently, both required, with retained per-phase reports and AGP additional-output screenshots.

## Actual validation and current failures
- Latest full local command at stage6e PASSED23m46s: Android test compilation,94 JVM cases per Debug/Preview variant (0fail/error/skip), full lint0errors/201Debug+200Preview warnings, Debug/optimized nondebug Preview/test APKs. Log `/tmp/batstats-stage6e-validation.log`.
- Eleven host parser/orchestration tests, migration/history SQL,493-key Spanish/Turkish resource checks, actionlint and bash syntax pass. Native-speaker review unavailable.
- First actual API36 runs at ff792f4: PR35323229857 reports21 entries16pass/5fail; push35323226385 reports21 entries17pass/4fail. Each executed20 methods; one initialization error prevented4 recovery methods. Migrations/import/export/history queries, diagnostics, notification construction and settings resources passed. Large-font navigation passed only in push run.
- Confirmed failures: alpha Material3 crash, wrong scripted receiver overload, nonvoid recovery test; local fixes above await runtime proof. Notification navigation/screen timing and system-back failures remain unresolved. No Shizuku test executed yet. Screenshots were lost by uninstall; retention correction awaits validation.
- Reports: `/tmp/batstats-ci-{35323229857,35323226385}`; job logs `/tmp/batstats-ci-job105530260291.log` and `/tmp/batstats-ci-job105530248386.log`. Do not confuse teardown runDetachLifecycle with earlier root AbstractMethodError.
- Fresh stage6e APK signatures/16KiB ZIP alignment pass; `/tmp/batstats-stage6e-apk-verification.json` has hashes. APKs in app/build/outputs/apk. **artifacts/ refreshed from clean0e7455a after successful51s incremental Debug/Preview assembly.** Metadata records clean revision and verified hashes; log /tmp/batstats-stage6f-clean-apks.log. Both local signers `93e78296c5eb4a5a2970679e96454de0c6979918c8f248765fddc1eb87ace8dd`. Separate .debug/.preview packages cannot update original; future Preview updates require matching certificate. CI ephemeral keys may differ by run.

## Environment and limitations
- JDK21, Gradle9.7.1/AGP9.4, compile37/target36/min26; SDK `/home/dev/android-sdk`. Host3.8GiB RAM+4GiB swap, noKVM, about1GiB free. Use one worker/in-process Kotlin/1GiB local Gradle; never run local emulator concurrently. Preserve shared SDK/other AVDs.
- Local task AVD batstats_api36 is stopped. Software boot failed to finish after68minutes; baseline installations rejected as still booting. No local app runtime conclusion. Shared ATD SDK restored; logs under `/tmp/batstats-api36*`. Hosted accelerated full Google APIs emulator boots successfully.
- No physical Samsung current/calibration/capacity/AOD/vendor-kernel/background or energy-overhead validation.16KiB runtime unverified; graphics-path RELRO-end warning remains despite valid ZIP/PT_LOAD alignment. No app screenshots visually reviewed yet; widget rendering pending.
- Official pinned Shizuku13.6 APK available in /tmp/batstats-artifacts; setup script verifies checksum and shellUID2000. Actual authorization/Binder/loss/reconnection remains pending.
- Existing Dependabot workflow separately fails startup permission validation; do not expand privileges/settings. Manual phone workflow Run control requires workflow on default branch; merging is not approved.

## Current work and exact next action
- Stage6f0e7455a published; both hosted builds pass. PR35327883884/job105545122680 and push35327878801/job105545107463 now complete: ordinary25methods19pass/6fail, Shizuku1fail, no skips. Reports under /tmp/batstats-ci-{35327883884,35327878801}; corresponding job logs in /tmp.
- Five ordinary failures are screenshot EACCES: AGP created the external collection directory before app installation. The remaining ordinary failure is storage-restart timeout. Shizuku reached real Binder/shellUID2000 and displayed its permission activity, but the test's button resource selector failed because the official APK obfuscates resource names. No screenshot survived; later UI assertions remain unexecuted.
- Stage6g widget regression committed d9f353d, compilation/Android test APK assembly passed1m13s; held locally. It uses actual system widget binding/delivery and scripted readings; not executed yet.
- Stage6h in progress: private-cache PNG/XML capture followed by test-shell copy to /sdcard/Download/batstats-validation-screenshots; AGP/ADB collect that directory. Shizuku selector uses package plus verified English Allow all the time text from the pinned release. Eleven host tests, bash/actionlint/diff checks pass.
- Confirmed B34: Room2.8.5 close cancels its query scope; DBUtil uses that context. Repository rethrows the resulting CancellationException even when its owner is active, silently ending the event writer. Fix distinguishes owner cancellation using ensureActive, publishes stop/reset before failed persistence, drops stale in-memory session references and retries interrupted-session recovery. Existing failing assertion preserved, with phase diagnostics; added unavailable-storage stop/reset regression.
- Confirmed B35: elapsed/uptime reads straddle BatteryManager IPC; hosted snapshot showed uptime42ms beyond elapsed. Move property reads before the adjacent clock pair to avoid injecting IPC delay into CPU-suspend/gap calculations. No physical CPU-sleep accuracy claim.
- Full stage6h build was deliberately interrupted (exit130) during compilation to include B35; do not count it as a pass. Replacement full compile/JVM/lint/Debug+Preview+test build PASSED14m29s; /tmp/batstats-stage6h-validation2.log. Both94-case JVM suites/full lint passed (0errors,201/200warnings); all APKs assembled. After test compilation completed, the capture helper was corrected to feed an actual shell through UiAutomation.executeShellCommandRw: AOSP confirms string commands use Runtime.exec without quote/redirect parsing. Final Android test recompile/reassembly plus Debug lint PASSED2m13s, /tmp/batstats-stage6h-capture-rebuild.log. All28 methods compile. No active compiler. No local emulator active.
- Next: commit the locally validated stage6h, reread this record, push d9f353d plus fixes under existing approval and update draft PR. Inspect ordinary and Shizuku reports/screenshots, fix actual failures without weakening assertions. Keep PR draft; merging/releases/settings/cancellation excluded.
- Current artifacts remain the clean0e7455a build, before B34/B35; recollect from clean updated source after success. After compaction read records/status/recent commits/all diffs before implementation. PR body uses REST; gh pr edit's deprecated GraphQL query fails.
