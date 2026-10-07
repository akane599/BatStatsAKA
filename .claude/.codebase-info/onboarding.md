# Onboarding

*Last Updated: 2026-10-07*

## Environment
- JDK 21: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`. The SDK comes from `ANDROID_HOME`; never create
  or edit `local.properties`.
- This dev host has no AVD or attached device and no KVM, so device/instrumented checks don't run locally.
- New machine: run `android-kit/setup.sh` and `code-audit-claude/install.mjs` as in `docs/CLAUDE_SETUP.md`;
  plugins, kit hooks and model env are per machine in `.claude/settings.local.json` (never committed).

## Commands
| Task | Command |
| --- | --- |
| Build debug | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug --console=plain -q` |
| Gradle with log + exit code | `bash .claude/kit/gradle-check.sh <tasks>` |
| Unit tests | `./gradlew :app:testDebugUnitTest --console=plain -q` (add `--tests 'app.batstats.…Test'`) |
| Lint | `./gradlew :app:lintDebug --console=plain -q` |
| Screenshot tests | `bash .claude/scripts/run_screenshot_tests.sh` |
| Resource/migration/SQL checks | `python3 scripts/check_resources.py`, `python3 scripts/check_migrations.py`, `python3 scripts/check_history_queries.py` |
| Instrumented | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=app.batstats.test.RequiresShizuku --console=plain -q` |
| Install + launch | `./gradlew :app:installDebug --console=plain -q && adb shell am start -n org.mlm.batstats.debug/app.batstats.battery.BatteryMainActivity` |

Gradle is heavy here (`org.gradle.workers.max=2`). Run one Gradle job at a time.

## Common tasks
- **New screen:** add a `Routes` key (`ui/navigation/Routes.kt`), an `entry<…>` in `ui/NavGraph.kt`, a
  VM + repository interface in `viewmodel/`, a `viewModel {}` in `di/AppModules.kt`, strings in all three
  `strings_<screen>.xml` locales, and `@PreviewTest` previews in `app/src/screenshotTest/…`. The
  `new-screen` skill (`.claude/skills/new-screen/`) has a template.
- **Schema change:** bump `version` in `BatteryDatabase.kt`, add `MIGRATION_n_n+1` and register it in `get()`,
  commit the new `app/schemas/…/N.json`, then run `scripts/check_migrations.py`. Also update
  `ExportImport.kt` if exported fields change. See [database.md](database.md).
- **New setting:** add the field in `settings/SettingsDefinition.kt`. Renames/removals need a schema bump
  and a step in `SettingsMigrations.kt`.
- **New privileged command:** it must be added to `ShellUserService.COMMANDS` (the privilege boundary).
  See [privileged-shell.md](privileged-shell.md).
- **UI change:** use theme tokens only, update the screenshot references, and check a rendered PNG.

## Read next
[architecture.md](architecture.md) → [modules.md](modules.md) → [patterns.md](patterns.md). The domain
background is in `docs/MEASUREMENTS.md` and `docs/PLATFORM_NOTES.md`.
