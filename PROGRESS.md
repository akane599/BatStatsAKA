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
- `6bb4bf1` stage5f: reuse APK workflow from push/PR CI with mandatory API36/Shizuku tests; no CI signing secrets; actionlint passes. Hosted execution subsequently approved (scope above).
- `dd7f2c0` stage5c: configuration-aware Compose resources/current-window layout, dashboard recovery test, guarded font cleanup and clean device-report reruns.

- `af1cd66` native packaging evidence/offline guides; `5f0ff85` prebuilt ADB runner and six runner-result regressions.

## Latest completed stages
- `91c6053`: approved publication; [draft PR1](https://github.com/akane599/BatStatsAKA/pull/1). `32a01ae`: corrected SDK package to platforms;android-37.0 after actual CI/local failure. `dc9ee2f`: one-worker/in-process/3GiB CI after hosted packaging OOM.
- `ff792f4`: separated APK-dist outputs from AGP inputs after connected-test task validation failure; all variants verified locally. Both subsequent hosted builds passed; their device failures led to the fixes below.
- `220b4d3`: populated history/chart/deletion UI regression with explicitly scripted imported samples;26 device methods compile.
- `a01e24c`: fixed runtime Material3/Foundation ABI mismatch by enforcing the stable BOM; verified45 referenced Material3 members against stable1.4.0. Guarded NavDisplay back handling; corrected receiver overload and JUnit void-return fixtures. Device phases now run independently, both required, with retained per-phase reports and AGP additional-output screenshots.

## Current validation and delivery state
- Latest published source0e9b301: full local compile, both94-case JVM suites, full lint0errors/201Debug+200Preview warnings and Debug/optimized nondebug Preview/test APK builds pass. Logs /tmp/batstats-stage6h-validation2.log (14m29s), /tmp/batstats-stage6h-capture-rebuild.log (2m13s).
- Actual API36 PR35331604578/job105556978396: ordinary25pass/2fail; push35331600751/job105556961354: ordinary26pass/1fail. Both execute27 ordinary methods, no skips. Both Shizuku phases PASS1/1: actual Binder/shellUID2000, permission, commands, helper recovery, server loss/restart/reconnect. Reports under /tmp/batstats-ci-{35331604578,35331600751}.
- All5 storage recovery cases pass, confirming B34; adjacent clocks agree after B35. Dashboard recovery/history/navigation/font200/import/settings/notification checks pass. Widgets pass in push but time out in PR; phase unknown, add diagnostics without bypassing delivery.
- PR lifecycle behavior assertions pass, cleanup fails because ActivityScenario matches original MAIN intent while notification onNewIntent replaces it. Restore the test-owned activity's original intent before closing. Push lifecycle fails when a poll sees wake before SCREEN_ON, dropping the preceding off interval: confirmed B36, bounded capture sequencing in progress.
- **No visual review yet.** All exported PNG/XML files are zero bytes: SELinux blocks run-as writing to external FUSE stdout. Capture privately, then pipe through shell-owned cat and verify exact nonzero byte counts; filenames/exit0 are not proof.
- Clean0e9b301 APKs in artifacts/: Debug c1948ac2900c5623628484dc398d494aafc10c6d23d5981193b3c602b810f156; Preview52dc7d194b31e78dd3e5221eee7152b1fe1a36e92123081f261bea3833cfd292. Both signer93e78296c5eb4a5a2970679e96454de0c6979918c8f248765fddc1eb87ace8dd. Separate .debug/.preview packages cannot update original. CI ephemeral certificates can differ between runs. Metadata records clean source; regenerate after production changes.

## Held stages and active work
- bebe479 extends dark/font200 navigation into landscape, with orientation cleanup. Android compilation/test APK PASS1m; runtime pending.
- a59149c adds sequential API36 google_apis_ps16k validation using prebuilt APKs, grouped reports, required page-size guard, native library loading test.29 methods compile/test APK PASS1m4s;14 host tests/bash/actionlint pass. Runtime16KiB pending. Both commits held locally until current failures are corrected.
- Active stage6k: StateEventSequencer is integrated and main-thread resets are in place. Buffer one actual poll capture for up to2s until matching real event; no timer/wakeup, missing/conflicting events remain gaps. Stale screen broadcasts use actual state plus a gap; queue overflow between retained/event samples preserves a gap. Capture pipe/byte counts and ActivityScenario cleanup are fixed; widget phase/actual-value diagnostics added. Targeted104-case JVM+device compile/test APK passed2m. Extended sequencing to unrelated state broadcasts (one additional regression); full105-case Debug/Preview JVM suites and both lint variants now pass (0errors,201/200warnings). Full build including Debug/optimized Preview/test APK packaging PASSED15m10s; /tmp/batstats-stage6k-validation.log. No assertions may be weakened.

## Environment, limitations and exact next action
- JDK21/Gradle9.7.1/AGP9.4; SDK /home/dev/android-sdk, compile37 (platforms;android-37.0), target36/min26.3.8GiB RAM+4GiB swap, noKVM. Local one-worker/in-process Kotlin/1GiB Gradle; never build alongside local emulator. No active compiler or emulator.
- Software emulator failed68min boot; baseline installations rejected as still booting. Hosted accelerated Google APIs boots. Preserve sharedSDK/unrelatedAVDs. No physical Samsung current/capacity/AOD/vendor-kernel/background/energy results. ZIP/PT_LOAD16KiB checks pass; graphics-path stricter RELRO-end warning remains; no runtime16KiB claim.
- Draft PR1 https://github.com/akane599/BatStatsAKA/pull/1 remains approved. Update via REST (gh pr edit deprecated GraphQL fails). Existing Dependabot startup permission failure is separate; do not broaden privileges/settings. Phone Run workflow control needs default-branch merge, not approved.
- Exact next: commit completed stage6k, reread this record, push held landscape/16KiB commits plus validated corrections and update PR under approval. Reassemble/collect APK metadata from clean committed source while CI runs. Inspect standard+16KiB ordinary/Shizuku results and actually view nonempty captures; finalize clean APKs/docs/provenance. Keep PR draft; no merge/releases/settings/cancellation.
