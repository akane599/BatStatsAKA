# Repository Guidelines

## Persistent Development Workflow

- Treat the supplied repository/APK as the baseline; verify existing behavior before claiming improvements. Target Android 16/API 36, including Samsung One UI, with Shizuku primary and other access modes preserved.
- Work autonomously in focused stages: baseline/audit, access/lifecycle, measurements/sessions, advanced analysis/efficiency, UI/notification/history, and validation/delivery. Adjust order for dependencies. Implement requested improvements; preserve the useful monitoring notification.
- Keep changing state in `PROGRESS.md`: goals/constraints, starting revision, branch, completed stages/commits, decisions, actual test results, unresolved issues, pending approvals, and exact next action. Keep audit findings and coverage in `AUDIT_REPORT.md`.
- After every stage, update `PROGRESS.md` and make a focused local commit containing only relevant work. Re-read `PROGRESS.md` before starting the next stage. Record incomplete work and verification before an anticipated context reset.
- After compaction or a new session, pause implementation: read `PROGRESS.md` and `AUDIT_REPORT.md`, inspect `git status`, recent commits, and all uncommitted diffs. Reconcile discrepancies before resuming the recorded next action.
- Local task-related edits, refactors, branches, commits, dependency installation, APK builds, tests, and disposable emulators are authorized without repeated approval, subject to environment permissions. Preserve unrelated work and never commit secrets or signing keys.
- Obtain explicit approval before any GitHub write/remote mutation, including pushes, PRs, comments, merges, releases, settings, or triggering/rerunning/cancelling Actions. First finish local work and validation; describe exact proposed remote actions and automatic workflow triggers. Continue other local work while approval is pending.
- Use automated tests, available emulators, and platform documentation. No physical Samsung device is available; do not pause for phone testing. Distinguish simulated inputs from measurements, estimates from measured values, and unavailable readings from zero. Report hardware limitations honestly; do not claim measured energy savings from code changes.
- Deliver a committed development branch, updated records, installable APKs with signing/update guidance, actual test/visual results, unresolved limitations, and locally prepared PR description and manually launchable build workflow.

## Project Structure & Module Organization

BatStats is a single-module Android app using Kotlin and Jetpack Compose.

- `app/src/main/java/app/batstats/`: application source. `ui/` contains screens, components, and themes; `viewmodel/` manages UI state; `di/AppModules.kt` wires Koin dependencies.
- `battery/`: monitoring services, drain tracking, Root/Shizuku integrations, widgets, and persistence (`data/db/` contains Room entities and DAOs).
- `app/src/main/res/`: localized strings, drawables, and widget layouts. Store listing assets and changelogs live in `fastlane/metadata/android/en-US/`.
- `gradle/libs.versions.toml`: dependency and plugin versions. `.github/workflows/` invokes shared CI and release workflows.

## Build, Test, and Development Commands

Use JDK 21 and Android SDK 37; the minimum supported device API is 26. Configure the SDK path in untracked `local.properties` or your environment. Run from the repository root:

- `./gradlew :app:assembleDebug`: build debug APKs under `app/build/outputs/apk/debug/`.
- `./gradlew :app:installDebug`: install on a connected device or emulator; launch BatStats from the device.
- `./gradlew :app:lintDebug`: run Android Lint with `app/lint.xml`.
- `./gradlew :app:testDebugUnitTest`: run JVM tests once test sources and dependencies are added.
- `./gradlew :app:connectedDebugAndroidTest`: run instrumentation tests once configured, with a connected device/emulator.

Use `gradlew.bat` on Windows.

## Coding Style & Naming Conventions

Follow the configured official Kotlin style with four-space indentation. Use PascalCase for classes and composables (`DashboardScreen`), camelCase for functions/properties, and UPPER_SNAKE_CASE for constants. Match existing `*Screen.kt`, `*ViewModel.kt`, and `*Widget.kt` naming. Use snake_case resource names and place new user-facing strings in `res/values/strings.xml`. Keep dependency versions in the version catalog. No ktlint or detekt configuration is present; use Android Studio formatting and Android Lint.

## Testing Guidelines

No test sources, active test dependencies, or coverage threshold currently exist. `AndroidJUnitRunner` is declared. Add JVM tests under `app/src/test/` and device/Compose tests under `app/src/androidTest/`, mirroring source packages with `*Test.kt` names; configure dependencies first. For fixes, reproduce the affected behavior and record device/API, privilege mode (standard, ADB, Root, or Shizuku), and validation results.

## Commit & Pull Request Guidelines

History mixes plain imperative summaries with `chore:`, `chore(deps):`, `docs:`, and `ci:` prefixes. Prefer concise, scoped summaries. PRs should explain the problem and behavior change, link related issues, list checks performed, and include screenshots for UI changes. Investigate OS-specific behavior before reporting bugs.

## Security & Configuration

Keep keystores and signing credentials out of commits. Release signing reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Debug builds use `org.mlm.batstats.debug`; adapt README permission commands to that package when testing.
