# Validation record

Results below are local execution, not GitHub Actions or physical Samsung validation.

| Stage | Actual checks | Result |
| --- | --- | --- |
| Baseline | Dependency metadata | Failed in57s: alpha Compose required SDK37.1; project declared37.0. Stable BOM fixed this. |
| 3b | Debug assembly | Passed; APK is older than current source. Final assemblies required. |
| 3c / 3d | JVM regressions | 45 / 51 passed. |
| 3d | Lint | Failed in10m29s:125 MissingTranslation errors/216 warnings. API28 charge-time guard subsequently fixed. Translations remain open; errors are not suppressed. |
| 4a | JVM + Android test compilation | 63 passed,0fail/error/skip,2m6s (`/tmp/batstats-stage4a-retest.log`). Initial Android compilation failed on invalid RoomDatabase.use; corrected explicit cleanup, assertions retained. |
| 4b1 | JVM + Android test compilation | 73 passed,1m46s (`/tmp/batstats-stage4b1-retest.log`). |
| 4b2 | JVM + Android test compilation | 76 passed,2m19s (`/tmp/batstats-stage4b2-tests.log`). |
| 4b3 | JVM + Android test compilation | 82 passed,2m27s (`/tmp/batstats-stage4b3-tests.log`). |
| 4c1 | JVM + Android test compilation | 86 passed,2m38s (`/tmp/batstats-stage4c1-tests.log`). |
| 4c2 | JVM + Android test compilation | 90 passed,1m24s (`/tmp/batstats-stage4c2-final.log`). |
| 4c3a | JVM + Android test compilation | 90 passed,1m59s (`/tmp/batstats-stage4c3a-tests.log`). |
| 4d1 | JVM + Android test compilation | 94 passed,0fail/error/skip. Initial process exited143 after JVM success, during Android compilation; daemon reported client disconnection, cause unknown (`/tmp/batstats-stage4d1-tests.log`). Unchanged rerun completed in44s with JVM results up-to-date (`/tmp/batstats-stage4d1-retest.log`). |
| 5a | Debug/Preview JVM suites + debug/preview/Android test APK assembly | Passed21m54s:94 cases in each JVM suite,0fail/error/skip, including preview R8/resource shrinking and lintVitalPreview. Initial task selection failed because preview host tests were disabled by AGP9 default; explicitly enabling that component fixed it. Full lint still pending. |

Stage5a command: `./gradlew :app:testDebugUnitTest :app:testPreviewUnitTest :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest --no-daemon -Dorg.gradle.jvmargs=-Xmx1024m --max-workers=1`. Log `/tmp/batstats-stage5a-rebuild.log`. `actionlint1.7.12` passes both changed workflows. Actual artifact collection/apksigner verification passes for both universal APKs; preview manifest min26/target36, nondebuggable and label BatStats Preview. Both use development signerSHA256 `93e78296c5eb4a5a2970679e96454de0c6979918c8f248765fddc1eb87ace8dd`. Generated build-info records actual APK code737 and the precommit working-tree state. These are intermediate builds; known recovery fixes/translations and final runtime validation remain.

Standard command: `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon -Dorg.gradle.jvmargs=-Xmx1024m --max-workers=1`.

`python3 scripts/check_migrations.py` passes actual SQL1/2/3→4 schema/indices/row preservation/sentinel handling. `python3 scripts/check_history_queries.py` passes source filtering, import identity, duplicate-point transaction rollback, overlapping exports, retention,125-row paging and bounded/gap-preserving session chart queries. These run on host SQLite, not Android.

JUnit fixtures exercise calculations, parsers, access transport, transitions, missing values, import integrity, alerts, history evidence, diagnostics, settings validation, palette contrast and shared display text. Synthetic battery inputs are not measurements.

Android migration/history/notification/diagnostic persistence/settings tests compile; none have executed yet. No current UI, Binder, notification delivery or widget rendering success is claimed. Store phone screenshots and the duplicate phone image in tvScreenshots were visually inspected: they show legacy controls, not this implementation. Current screenshots remain required.

The disposable API36 ATD has no KVM. Software emulation first hit watchdog failures; `ro.hw_timeout_multiplier=100` allowed one verified boot before a user pause. Baseline installation was interrupted. A later emulator/build combination exhausted3.8GiB RAM+4GiBswap; task emulator stopped. Run builds and emulators sequentially. Injected100%,5000mV,25°C,900000µA readings do not establish hardware accuracy. No Samsung hardware is available; AOD, vendor current/units, real energy use and One UI process management remain unverified.

Stage4d2: Debug and Preview each passed94 JVM tests,0fail/error/skip; Android tests compiled in2m32s (`/tmp/batstats-stage4d2-tests.log`). Command adds `-Pkotlin.compiler.execution.strategy=in-process` to constrained build flags. Four new repository recovery tests use isolated storage and injected broadcast/read failures; compiled only, not executed.

Stage5b: four new actual-device test methods compile; fresh Debug and Android-test APK assembly PASSED1m59s (`/tmp/batstats-stage5b-device-build.log`). No tests executed on-device yet. Full API36 google_apis selected for screenshots/notification shade because [ATD omits SystemUI and disables rendering](https://developer.android.com/studio/test/managed-devices#atd). Shizuku setup script uses the [official native starter](https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/jni/starter.cpp); checksum fixed to the reviewed13.6 APK. Bash/actionlint/Python argument parsing pass; live setup is still unverified.

Stage4e: `python3 scripts/check_resources.py` passes Spanish/Turkish493-key completeness, matching format arguments/newlines and parsing all resource XML. `aapt2 compile --dir app/src/main/res -o /tmp/batstats-stage4e-resources.zip` passes. No Gradle build ran alongside the emulator; full lint/resource-link and translated-screen execution remain pending. Baseline English duplicates were consolidated, not replaced with fake translations; see LOCALIZATION.md.

Full emulator first failed disk preflight. Removed only the task ATD AVD and redundant task-generated ABI APK copies (retained universal APKs), then initialized sparse6GiB userdata using the SDK data directory. Full API36 image now boots with2560MiB (its enforced minimum),384×800/dpi160/software acceleration off. Log `/tmp/batstats-api36-full.log`; startup not complete at this checkpoint.

Stage5c initial full checks failed5m30s at lintDebug:7 LocalContextGetResourceValueCall errors/202 warnings, zero MissingTranslation errors. All25 Android test methods compiled;94-case Debug/Preview JVM suites remained up-to-date passing. Preview full lint and assemblies were not reached. Corrections use stringResource/LocalResources and actual window width; navigation test cleanup preserves device font if its emulator guard fails. No suppression or assertion weakening. Revalidation pending. Log `/tmp/batstats-stage5c-full-checks.log`.

Stage5c rerun:94 JVM cases pass in each variant,0fail/error/skip. Full Debug/Preview lint passes0errors with201/200warnings; no suppression. Warnings reviewed: mostly unused resources/plural suggestions/dependency versions; target36 intentionally matches the requested platform. Context warnings refer to application contexts passed by Koin, not retained activities. Full command completed successfully in16m, including optimized/shrunk Preview and fresh Debug/test APKs (`/tmp/batstats-stage5c-retest.log`). All25 Android methods compiled, none executed. Full lint covered both variants; AGP skipped redundant lintVital tasks in the same invocation.

Stage5c command: `./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:testPreviewUnitTest :app:lintDebug :app:lintPreview :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest --no-daemon -Dorg.gradle.jvmargs=-Xmx1024m -Pkotlin.compiler.execution.strategy=in-process --max-workers=1`. SQL/resource/actionlint/bash checks and `git diff --check` also pass. Device report reruns clear only generated reports/screenshots after emulator guards; no old capture can stand in for a new run.
