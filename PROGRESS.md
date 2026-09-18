# BatStats Development Progress

## Scope and authority
- Starting revision `76bc831328572c81717b97ffb0e280b10b14b8ad` (source6.2.6/code734); branch `codex/android16-reliability`. No unrelated changes at start.
- Deliver accurate, efficient Android16/API36 monitoring with Shizuku primary, ordinary/ADB/Root preserved, useful rich notification, accessible UI, reliable history/diagnostics/export, tested installable debug/nondebug APKs, signing guide and local PR/manual-workflow preparation.
- Follow AGENTS.md: focused stages, update this record and commit locally after each stage; reread before the next. After compaction read this and AUDIT_REPORT, status/recent commits/full uncommitted diff; reconcile before implementation.
- Local task work/builds/dependencies/emulators/commits authorized. **No GitHub writes/pushes/Actions approved or performed.** Finish local work before proposing exact remote actions and automatic triggers. No routine local approval needed.
- No physical Samsung; do not request phone testing. Distinguish simulated inputs, estimates, missing values and measurements. No emulator-based hardware/energy-saving claims. User-requested UX improvements are authorized beyond the generic audit skill.
- Commit identity: `git -c user.name=Codex -c user.email=codex@openai.com commit`. Preserve unrelated work/processes; never commit keys/secrets.

## Baseline artifacts
- `/tmp/batstats-artifacts/baseline-6.2.6.apk`: supplied release, `org.mlm.batstats`, code735/target37/min26. SHA256 `4009bc1f13d7c871ddc1c61f3696ed224f1ad5fbfe547ed452fc2aeaca86b3c5`; signerSHA256 `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`. APK-dist0.5.2 adds1 to the universal APK version code; source734/APK735 is expected and does not establish a different source revision. Original key unavailable. Debug `.debug` coexists; nondebug `.preview` built in stage5a.
- `/tmp/batstats-artifacts/shizuku-13.6.0.apk`: official RikkaApps release. SHA256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`; signerSHA256 `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`. Not installed.

## Completed stages and decisions
- `31c9a55` instructions; `8ebc074` baseline audit; `6e7e801` stable Compose/API36 specialUse FGS/boot/bounded source-aware access.
- `08307d4` nullable units/monotonic observation/stable ETA; `e0d1eb3` single actor/automatic sessions/DB4/nondestructive migrations/shared totals. No fixed4000mAh, cycle-health or80/20mA app heuristic; detailed UID capability retained.
- `e95273c` Android16 parser/ADB gate/non-consuming `-c --charged`; `3584abc` cancellable request-ID Shizuku helper and real bounded Root/kernel reads. Runtime/vendor checks pending.
- `a2b9021` bounded transactional history/import/export/dedup/clear gate/retention/settings-only backup; `d628a90` validated persistent alert episodes and effective settings.
- `53f9894` rich stable notification/navigation/widget freshness; `1f32614` bounded local fixed-code diagnostics/provenance/sharing; `4ce8957` complete paged history/bounded linked charts/legacy and missing states.
- `9c07740` localized settings metadata, bounded validated preferences import and palette contrast; `0a1e566` shared resource-backed monitoring/notification labels with explicit endpoints, responsive cards and tiny nonzero formatting.
- `50d766e` stage4d1: queued SQL cannot overwrite a newer live point; fractional capacity retained and invalid discharge counters rejected; current-direction contradictions flagged without guessing; recoverable notification errors and alert-channel work only on delivery attempts. Four new synthetic regressions pass.
- Technical diagnostic-report keys remain stable English identifiers; explanatory screens are localizable. Preserve17 locale directories/16 languages; real translations needed, no placeholder English copies or lint suppression. Banner.svg exists; old missing-link suspicion disproved.

## Actual validation and environment
- Latest stage4d1:94 JVM tests,0fail/error/skip. Initial Gradle client interrupted(exit143) after tests; unchanged rerun passed Android compilation in44s. `/tmp/batstats-stage4d1-tests.log`, `/tmp/batstats-stage4d1-retest.log`.
- Host migration/history SQL checks pass. **Android tests compiled only, none executed.** Stage5a debug, preview and Android test APKs assembled. Last lint failed missing translations. Full historic results and commands: [docs/VALIDATION.md](docs/VALIDATION.md).
- JDK21, SDK37 compilation/target36/min26, stable Compose BOM2026.09.00/UI1.12.1. Host3.8GiB RAM+4GiBswap,~1.6GiB free disk. Builds use heap1024/workers1. **Builds and emulator sequentially; do not edit main/DAO during KSP/Kotlin.** Preserve unrelated Java98543/other AVDs. Never print env/full process args.
- Task AVD `batstats_atd36`, port5580, software `-accel off`,1536MiB/noKVM/sparse6GiB userdata. One verified boot before pause using watchdog multiplier100; baseline install interrupted/status unknown. Resumed emulator105403 stopped after memory exhaustion. **Emulator stopped; no active build.** Log `/tmp/batstats-emulator-resumed.log`; return adbd to shell before Shizuku checks.
- Actual app screens, Shizuku Binder/reconnection, widgets/alerts, One UI/current calibration/capacity/energy use remain unverified. Legacy store screenshots reviewed, cannot validate current build.

## Exact next action
1. Stage4d2 validated and ready for the commit containing this checkpoint. Reread this record, then author API36 UI/Shizuku integration checks.
2. Author API36 UI/Shizuku integration checks and build fresh debug/test/preview APKs. Run emulator separately; use long software boot time for translations/docs. Do not weaken tests or silently skip failed compatibility checks.
3. Complete genuine translations across17locale directories (about330 new strings each); inspect actual English/localized, light/dark/large-font screens, loading/empty/error/recovery. Correct stale ja/zh README/store claims and replace old screenshots with actual captures where possible.
4. Final JVM/SQL/lint/build/Android checks; signatures/checksums and actual results. Complete AUDIT_REPORT/this record, signing/phone-build guide, locally prepared PR description and focused commits.
5. Only after concrete local validation, request explicit approval for exact GitHub actions. Existing push/PR CI runs automatically; a new workflow_dispatch file must reach the default branch before GitHub exposes Run workflow, then branches can be selected. Continue local work while approval is pending.

No pending approval blocks local work. Audit findings: AUDIT_REPORT.md. Source contracts/privacy/release-workflow evidence: docs/PLATFORM_NOTES.md.

## Stage5a checkpoint — build and delivery preparation
- Nondebuggable/minified/shrunk `.preview` build with optional complete PREVIEW_* signing configuration, otherwise development key; distinct launcher labels. Production signing preserved.
- Removed confirmed unused WorkManager (including initializer), old navigation-compose/constraint/browser libraries and unused BATTERY_STATS/INTERACT_ACROSS_USERS requests/helpers. Shizuku provider cross-user protection retained.
- New read-only manual phone workflow: pinned actions, JVM/SQL/lint/build checks, optional(default on)API36 device tests, artifacts/reports/checksums/public fingerprints, optional temporary stable-preview key. No remote action. Release workflow now gates publication on the existing upload input, forwards prerelease, defaults Play/bump to none and uses the correct production ID.
- `actionlint1.7.12` passes both changed workflows; downloaded official checksum verified. Python artifact collector syntax and actual APK collection/signatures pass. Shizuku integration/UI-specific tests still to author.

Stage5a initial build failed during task selection: AGP9 only enables JVM test components for the instrumented build type by default, so testPreviewUnitTest was absent. Task inventory and current AGP API confirmed this; preview host tests now explicitly enabled with beforeVariants. Rebuild passed after this correction; no assertions removed or disabled.

## Follow-up found while designing recovery UI tests
- B31 confirmed: refreshNow/capture sets the shared error on missing battery data but a successful stopped/manual refresh never clears it; only a successful history write clears the shared field. That same unconditional clear also hides failed screen-event registration. After stage5a build/commit, separate battery/history/event-subscription failures, clear only the recovered source, and preserve unavailable screen accounting when events cannot be observed. Add regression coverage including a ContextWrapper that simulates absent battery broadcasts then recovery.
- Tighten B30 matching to all captured input fields, excluding only persisted session/ETA/boundary metadata; same-millisecond captures with different screen/status fields must not overwrite each other. Test this before UI validation.
- README ja/zh and store description now explain estimates/UID limitations rather than promising all-app energy accuracy. Actual new screenshots still pending.

Stage5a verification PASSED21m54s (`/tmp/batstats-stage5a-rebuild.log`):94 JVM tests in EACH Debug and Preview,0fail/error/skip; debug/preview/test APKs assembled and fatal lintVitalPreview passed. Full lint remains pending translations. Artifact script ran successfully; both APKs verify V2 with signerSHA256 `93e78296c5eb4a5a2970679e96454de0c6979918c8f248765fddc1eb87ace8dd`, universal code737/base736+1, target36/min26. Preview is nondebuggable,2.9MiB; debug25MiB. Merged manifest has no WorkManager initializer or removed permissions. Artifacts in ignored `artifacts/`; current metadata records precommit50d766e+dirty state. Regenerate final artifacts after remaining code/translation fixes. Idle task Kotlin daemon119631 was verified and stopped after compilation to free1.1GiB during D8; unrelated98543 untouched. Consider `-Pkotlin.compiler.execution.strategy=in-process` on subsequent constrained builds.
- Debug SHA256 `3c6166876754fcdf5a4068422f41abfea34ea13db16655b511becf86250893ca`; preview `44209dc41b2f475bdc6209447885d97055290e1783f68fd484a27099ff98a2f7`. Paths `artifacts/app-universal-debug.apk`, `artifacts/app-universal-preview.apk`; test APK `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.


Translation preparation only: `/tmp/batstats-es-new.tsv` contains331 genuine Spanish translations covering every currently missing key, matching format arguments. Not yet copied into either es-rES/es-rUS or Android-compiled. Region wording can be adapted (vídeo/video). Four changed existing reset strings also need locale updates; list found by comparing baseline/current strings.xml: reset_all, reset_ui, reset_all_settings, reset_ui_settings.

## Stage4d2 — verified, committing
- Source-specific error ownership (battery, state events, history writes, retention and sample-count preference). Successful capture clears only battery failure; successful writes do not erase observation/maintenance failures. Automatic/broadcast reads now catch RuntimeException instead of terminating their coroutine or crashing the receiver; failed properties remain distinct from unsupported sentinels.
- Failed state-event registration keeps ordinary snapshots available but pauses observed intervals rather than manufacturing continuity or zero-length sessions. Maintenance attempts remain bounded; failed count writes retry at most once/minute during monitoring. Startup resets the in-memory window before SQL and retries interrupted-session closure before recording when storage recovers.
- B30 persisted metadata now merges only with an equal captured reading (same-millisecond screen/status differences rejected); existing regression strengthened. Four Android repository regressions authored: missing/manual recovery without monitoring, subscription failure with no invented history and restart recovery, automatic/broadcast exception recovery, and failed-storage startup resetting the window while retaining ordinary readings and the correct error. Scripted broadcasts/subscriptions, isolated in-memory DB/preferences/logs; not physical measurements. Gradle Debug/Preview JVM suites each passed94 tests,0fail/error/skip, plus Android compilation in2m32s (`/tmp/batstats-stage4d2-tests.log`), in-process Kotlin to reduce peak memory. Android tests still have not executed. Emulator stopped; stage5a APKs predate these changes.
