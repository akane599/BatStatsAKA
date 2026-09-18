# BatStats Development Progress

## Scope and authority
- Starting revision `76bc831328572c81717b97ffb0e280b10b14b8ad` (source6.2.6/code734); branch `codex/android16-reliability`. No unrelated changes at start.
- Deliver accurate, efficient Android16/API36 monitoring with Shizuku primary, ordinary/ADB/Root preserved, useful rich notification, accessible UI, reliable history/diagnostics/export, tested installable debug/nondebug APKs, signing guide and local PR/manual-workflow preparation.
- Follow AGENTS.md: focused stages, update this record and commit locally after each stage; reread before the next. After compaction read this and AUDIT_REPORT, status/recent commits/full uncommitted diff; reconcile before implementation.
- Local task work/builds/dependencies/emulators/commits authorized. **No GitHub writes/pushes/Actions approved or performed.** Finish local work before proposing exact remote actions and automatic triggers. No routine local approval needed.
- No physical Samsung; do not request phone testing. Distinguish simulated inputs, estimates, missing values and measurements. No emulator-based hardware/energy-saving claims. User-requested UX improvements are authorized beyond the generic audit skill.
- Commit identity: `git -c user.name=Codex -c user.email=codex@openai.com commit`. Preserve unrelated work/processes; never commit keys/secrets.

## Baseline artifacts
- `/tmp/batstats-artifacts/baseline-6.2.6.apk`: supplied release, `org.mlm.batstats`, code735/target37/min26. SHA256 `4009bc1f13d7c871ddc1c61f3696ed224f1ad5fbfe547ed452fc2aeaca86b3c5`; signerSHA256 `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`. Original key unavailable. Debug `.debug` coexists; nondebug `.preview` still to prepare.
- `/tmp/batstats-artifacts/shizuku-13.6.0.apk`: official RikkaApps release. SHA256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`; signerSHA256 `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`. Not installed.

## Completed stages and decisions
- `31c9a55` instructions; `8ebc074` baseline audit; `6e7e801` stable Compose/API36 specialUse FGS/boot/bounded source-aware access.
- `08307d4` nullable units/monotonic observation/stable ETA; `e0d1eb3` single actor/automatic sessions/DB4/nondestructive migrations/shared totals. No fixed4000mAh, cycle-health or80/20mA app heuristic; detailed UID capability retained.
- `e95273c` Android16 parser/ADB gate/non-consuming `-c --charged`; `3584abc` cancellable request-ID Shizuku helper and real bounded Root/kernel reads. Runtime/vendor checks pending.
- `a2b9021` bounded transactional history/import/export/dedup/clear gate/retention/settings-only backup; `d628a90` validated persistent alert episodes and effective settings.
- `53f9894` rich stable notification/navigation/widget freshness; `1f32614` bounded local fixed-code diagnostics/provenance/sharing; `4ce8957` complete paged history/bounded linked charts/legacy and missing states.
- `9c07740` localized settings metadata, bounded validated preferences import and palette contrast; `0a1e566` shared resource-backed monitoring/notification labels with explicit endpoints, responsive cards and tiny nonzero formatting.
- Stage4d1 now validated, ready to commit: queued SQL cannot overwrite a newer live point; fractional capacity retained and invalid discharge counters rejected; current-direction contradictions flagged without guessing; recoverable notification errors and alert-channel work only on delivery attempts. Four new synthetic regressions pass.
- Technical diagnostic-report keys remain stable English identifiers; explanatory screens are localizable. Preserve17 locale directories/16 languages; real translations needed, no placeholder English copies or lint suppression. Banner.svg exists; old missing-link suspicion disproved.

## Actual validation and environment
- Latest stage4d1:94 JVM tests,0fail/error/skip. Initial Gradle client interrupted(exit143) after tests; unchanged rerun passed Android compilation in44s. `/tmp/batstats-stage4d1-tests.log`, `/tmp/batstats-stage4d1-retest.log`.
- Host migration/history SQL checks pass. **Android tests compiled only, none executed.** Latest assembled debug APK is stage3b, older than source. Last lint failed missing translations. Full historic results and commands: [docs/VALIDATION.md](docs/VALIDATION.md).
- JDK21, SDK37 compilation/target36/min26, stable Compose BOM2026.09.00/UI1.12.1. Host3.8GiB RAM+4GiBswap,~2.4GiB free disk. Builds use heap1024/workers1. **Builds and emulator sequentially; do not edit main/DAO during KSP/Kotlin.** Preserve unrelated Java98543/other AVDs. Never print env/full process args.
- Task AVD `batstats_atd36`, port5580, software `-accel off`,1536MiB/noKVM/sparse6GiB userdata. One verified boot before pause using watchdog multiplier100; baseline install interrupted/status unknown. Resumed emulator105403 stopped after memory exhaustion. **Currently stopped, no active build.** Log `/tmp/batstats-emulator-resumed.log`; return adbd to shell before Shizuku checks.
- Actual app screens, Shizuku Binder/reconnection, widgets/alerts, One UI/current calibration/capacity/energy use remain unverified. Legacy store screenshots reviewed, cannot validate current build.

## Exact next action
1. Commit validated stage4d1, reread this record. Prepare preview build/signing and local manual Actions checks/artifacts; remove only confirmed unused background dependencies/permissions. Existing release workflow is unsafe by default and ignores upload/prerelease inputs; see PLATFORM_NOTES.
2. Author API36 UI/Shizuku integration checks and build fresh debug/test/preview APKs. Run emulator separately; use long software boot time for translations/docs. Do not weaken tests or silently skip failed compatibility checks.
3. Complete genuine translations across17locale directories (about330 new strings each); inspect actual English/localized, light/dark/large-font screens, loading/empty/error/recovery. Correct stale ja/zh README/store claims and replace old screenshots with actual captures where possible.
4. Final JVM/SQL/lint/build/Android checks; signatures/checksums and actual results. Complete AUDIT_REPORT/this record, signing/phone-build guide, locally prepared PR description and focused commits.
5. Only after concrete local validation, request explicit approval for exact GitHub actions. Existing push/PR CI runs automatically; a new workflow_dispatch file must reach the default branch before GitHub exposes Run workflow, then branches can be selected. Continue local work while approval is pending.

No pending approval blocks local work. Audit findings: AUDIT_REPORT.md. Source contracts/privacy/release-workflow evidence: docs/PLATFORM_NOTES.md.
