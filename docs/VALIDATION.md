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

Standard command: `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon -Dorg.gradle.jvmargs=-Xmx1024m --max-workers=1`.

`python3 scripts/check_migrations.py` passes actual SQL1/2/3→4 schema/indices/row preservation/sentinel handling. `python3 scripts/check_history_queries.py` passes source filtering, import identity, duplicate-point transaction rollback, overlapping exports, retention,125-row paging and bounded/gap-preserving session chart queries. These run on host SQLite, not Android.

JUnit fixtures exercise calculations, parsers, access transport, transitions, missing values, import integrity, alerts, history evidence, diagnostics, settings validation, palette contrast and shared display text. Synthetic battery inputs are not measurements.

Android migration/history/notification/diagnostic persistence/settings tests compile; none have executed yet. No current UI, Binder, notification delivery or widget rendering success is claimed. Store phone screenshots and the duplicate phone image in tvScreenshots were visually inspected: they show legacy controls, not this implementation. Current screenshots remain required.

The disposable API36 ATD has no KVM. Software emulation first hit watchdog failures; `ro.hw_timeout_multiplier=100` allowed one verified boot before a user pause. Baseline installation was interrupted. A later emulator/build combination exhausted3.8GiB RAM+4GiBswap; task emulator stopped. Run builds and emulators sequentially. Injected100%,5000mV,25°C,900000µA readings do not establish hardware accuracy. No Samsung hardware is available; AOD, vendor current/units, real energy use and One UI process management remain unverified.
