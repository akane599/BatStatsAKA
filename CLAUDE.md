# CLAUDE.md — BatStats

Android app developed on Ubuntu, CLI only (no Android Studio in the loop). Keep this file short; it is in every prompt.

## Session protocol
- **Start:** read `PROGRESS.md` (Now / Next / Blockers). Don't re-explore the codebase for anything it already answers.
- **End:** update `PROGRESS.md` (done items, decision log, audit table), then `/revise-claude-md` if this file was wrong or missing something.
- Decisions go in the PROGRESS.md decision log with a one-line "why". Chat is not a record.

<!-- STACK:BEGIN  (filled by /bootstrap — do not hand-edit values you haven't verified) -->
- Language: Kotlin only (no Java) · New code in: Kotlin
- UI: Compose (Material 3) · previews: 0 in main · screenshot tests: compose-preview via AGP test suite `screenshotTest` (engine 0.0.1-alpha16; host TZ/locale pinned to UTC/en-US) · dark-only (+ OLED toggle) · dynamic color: opt-in (API 31+, accents only) · custom typography: Space Grotesk (`res/font`, numbers use `tnum`) · literal colors outside theme: 0 · raw `.dp` in `ui/screens`: 54 · 4 XML layouts are RemoteViews app widgets only
- JDK: build targets 21; `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (system default; pinned in gradle.properties: no)
- Gradle 9.7.1 · AGP 9.5.0-alpha07 (alpha, for screenshot test suites) · Kotlin 2.4.20 · compileSdk 37 · minSdk 26 · version catalog: yes (`gradle/libs.versions.toml`)
- Modules: `:app` · applicationId `org.mlm.batstats` (debug: `.debug`, preview: `.preview`) ≠ namespace `app.batstats`
- Architecture: single-module MVVM; layers `ui/` (navigation, components/chart, theme, screens) `viewmodel/` `data/` `di/` + feature package `battery/` (apps, data/db, data/sampling, service, measurement, drain, diagnostics, shizuku, util, widget)
- DI: koin · DB: room · Network: none · Async: coroutines/Flow
- Navigation: Navigation 3 (`NavDisplay`/`NavKey`, `ui/NavGraph.kt`) · Firebase: no
- Tests: JUnit4 + kotlinx-coroutines-test (no mockk/turbine) · androidTest: yes (UiAutomator + Compose UI test) · Espresso: no
- Lint: Android lint only (no detekt/ktlint/spotless)
- LSP: official `kotlin-lsp` plugin (JetBrains kotlin-lsp, `~/.local/bin/kotlin-lsp`) · AVD: batstats-api36 (API 36) · KVM: yes
<!-- STACK:END -->

## Map
- UI: `battery/BatteryApp.kt` (Koin) → `battery/BatteryMainActivity.kt` (`MainTheme`) → `ui/screens/MainScreen.kt` (tabs Now/History/Apps/Settings; bar <600 dp, rail ≥600 dp) → `ui/NavGraph.kt`; routes + `TopLevelBackStack` in `ui/navigation/`; deep links = `destination` intent extra (`Destinations.kt`).
- Data: `battery/service/BatteryMonitorService.kt` (specialUse FGS) → `battery/data/sampling/SamplingController.kt` (HandlerThread; 2 s demand / 30 s screen-on / 300 s screen-off; logcat tag `BatStatsSampler`) → `battery/data/BatteryRepository.kt` (`PersistPolicy`, one transaction per save, UI flows) → `battery/measurement/ObservationEngine.kt`. Privileged: `battery/util/ShellRunner.kt` → Shizuku/root/ADB → `BatteryStatsParser`. Units/sign rules: `docs/MEASUREMENTS.md`.

## Environment
- `ANDROID_HOME=$HOME/Android/Sdk`; `platform-tools`, `emulator`, `cmdline-tools/latest/bin` on PATH.
- Per-project JDK: if the box has several JDKs, export the `JAVA_HOME` above before Gradle, or pin `org.gradle.java.home` in `gradle.properties`. Never change the project's target JDK to match the machine.
- Headless emulator: `emulator -avd batstats-api36 -no-window -no-audio -gpu swiftshader_indirect &`
- Wait for boot: `adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'`
- Battery states on the emulator: `adb shell dumpsys battery unplug` · `set level 42` · `set ac 1` · `reset`.
- ADB privileged mode: `adb shell pm grant org.mlm.batstats.debug android.permission.DUMP` + `…PACKAGE_USAGE_STATS` + `adb shell appops set org.mlm.batstats.debug GET_USAGE_STATS allow`.
- Connected tests uninstall the debug app: reinstall, then `adb shell pm grant org.mlm.batstats.debug android.permission.POST_NOTIFICATIONS`.
- `rm -rf` is denied in `.claude/settings.json`; use `rm -r` (never on paths you haven't looked at).

## Commands (always `--console=plain -q`; never pipe full Gradle output into context)
- Build:         `./gradlew :app:assembleDebug --console=plain -q`
- Install + run: `./gradlew :app:installDebug --console=plain -q && adb shell am start -n org.mlm.batstats.debug/app.batstats.battery.BatteryMainActivity`
- Unit tests:    `./gradlew :app:testDebugUnitTest --console=plain -q`
- Instrumented:  `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=app.batstats.test.RequiresShizuku --console=plain -q` (emulator booted; CI's ordinary phase — `@RequiresShizuku` tests need `/device-check`). One class: `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`
- Lint:          `./gradlew :app:lintDebug --console=plain -q`
- CI parity:     after build/dependency changes run the "Verify and build" step of `.github/workflows/build-apk.yml` (4 python checks + one Gradle line incl. preview variant and lint)
- Screenshots:   `bash .claude/scripts/run_screenshot_tests.sh` (runs `:app:testDebugScreenshotTestDefaultTestSuite --rerun`, lists failures; exit 0 pass / 1 failures / 3 build broke). Re-baseline: ask the user to run `/screenshot-rebaseline` (never a bare `update…` run: renamed/removed previews leave stale PNGs). `@PreviewTest` previews in `app/src/screenshotTest/kotlin/`, refs in `app/src/screenshotTestDefaultDebug/reference/` (commit with the UI change)
- Failures only: append `2>&1 | grep -E "error:|FAILED|e: |warning: \[" | head -40`
- Exit codes:    shell is zsh — a pipe hides Gradle's status (`${PIPESTATUS}` is empty); redirect to a file and check `$?`, or use `$pipestatus[1]`. Quote globs in args (`--include='*.kt'`): unquoted ones abort with "no matches found".
- Task names:    don't use `./gradlew tasks --all` (AGP 9.5.0-alpha07 fails creating `generateReleaseComposePreviewRunfiles`: release has unit tests disabled); list via an init script printing `project(":app").tasks.names`
- Logs:          `adb logcat -d --pid=$(adb shell pidof -s org.mlm.batstats.debug) | tail -80` — never unbounded `adb logcat`
- Reports:       `app/build/reports/tests/`, `app/build/reports/lint-results-debug.html`, `app/build/reports/tests/testDebugScreenshotTestDefaultTestSuite/index.html` · rendered PNGs: `app/build/intermediates/debug/testDebugScreenshotTestDefaultTestSuite/results/rendered/`

## Subagent orchestration
Main context is for decisions and edits. Everything that reads a lot or reviews goes to a subagent that returns a summary, not file dumps.
- **Explore before touching:** for anything spanning >3 files, dispatch an `Explore` agent ("find X, return file list + 5-line summary") and read only the files it names.
- **New feature:** `/feature-dev <description>` — it runs explorer → architect → reviewer agents itself. Answer its clarifying questions; don't skip the architecture step.
- **Review before merge:** `/review-pr` (local) or `/code-review` (GitHub PR). For UI changes also run the `compose-reviewer` agent.
- **Project tools:** `/new-screen <Name — purpose>` scaffolds a screen (wrapper + `XxxContent` + route + strings ×3 locales + screenshot test); `screenshot-diff-triager` agent when the screenshot suite fails; `/screenshot-rebaseline` and `/device-check` (CI's emulator suite) are user-invoked — ask the user to run them. The PostToolUse hook `.claude/hooks/check-res-db.sh` runs `check_resources.py` / `check_migrations.py` on res and `battery/data/db` edits — fix its exit-2 feedback, don't bypass it.
- **Audits:** `/claude-security` for security; `module-graph-auditor` agent on any module/dependency change; `android-emulator-qa` skill for on-device behavior. Log every run in the PROGRESS.md audit table.
- **Agent names:** `compose-reviewer`, `module-graph-auditor`, `migration-expert` are plugin agents → `subagent_type: android-kmp-playbook:<name>`; only `screenshot-diff-triager` is local.
- **Parallelism:** independent reviews/searches go out in one batch (one message, several Agent calls). Sequential only when one result feeds the next.
- **UI overhauls (Compose):** `/ui-overhaul <scope>` — inventory → baseline screenshots → brief + tokens (approval gate) → theme first → one agent per screen → screenshots → independent design critique. Uses the `compose-design` skill; never restyle screens before the theme tokens exist.
- **Big refactors:** plan first (write it to PROGRESS.md "Now"), then hand each module to its own agent with a strict file scope so agents don't collide; parallel agents get `isolation: worktree` and run Gradle only as `flock /tmp/batstats-gradle.lock ./gradlew …` (~4 GB free with the emulator up). Worktrees may start at origin/main, so the agent checks `git log -1` and resets its fresh tree to the branch. Worktree agents can't write into the main checkout, so their reports stay in the worktree; main context copies them over and merges.
- **Plans:** run `/plan-audit` on any multi-phase plan before approving it (catches parallel-agent file collisions and phase-ordering breaks).
- **Verification is an agent's job too:** after implementation, a fresh agent that didn't write the code runs build + tests and reports pass/fail. Don't self-grade.
- **Loop control:** if an agent returns "done" without evidence (test output, build result), re-dispatch with "show the command output" — don't accept it.

## Conventions
- Kotlin: official style, trailing commas, `val` by default, sealed interfaces for state/events, no `!!` outside tests.
- Compose: stateless composables, `modifier: Modifier = Modifier` first optional param, state hoisted to ViewModel. Screens = public wrapper `XxxScreen` (Koin, flows, effects, launchers, Intents, root/Shizuku) + stateless `XxxContent`; every screen has `@PreviewTest @ScreenPreviews` in `app/src/screenshotTest/kotlin/app/batstats/ui/screens/` (helpers in `ui/ScreenshotPreviews.kt`; dates from `FIXED_TIME_MS`).
- Compose design: all colors/type/shapes/spacing via `MaterialTheme` + `MaterialTheme.batColors` / `.chartColors` / `.spacing` (+ shapes, `Motion.kt`) in `ui/theme/`; agents report missing tokens, main context adds them; no `Color(0x…)`, raw `.dp`/`.sp` in screen files. Load `compose-design` before designing or restyling any screen.
- App widgets: RemoteViews XML in `res/layout/widget_*.xml`, driven by `battery/widget/WidgetUpdater.kt` — keep them RemoteViews-compatible (no ViewBinding, no custom views).
- Strings in `values/`, `values-es/`, `values-tr/` — any `strings*.xml` file (e.g. `strings_components.xml`); `check_resources.py` and `EnglishStrings.kt` merge them per locale and fail on missing translations, format-arg mismatches or duplicate keys. Dimensions/colors via theme/resources, never literals in code.
- androidTest finds UI by text/contentDescription and `ui/TestTags` (`testTagsAsResourceId`; nav items tagged); renaming strings breaks `NavigationDeviceTest` & co. Palette edits must keep `ThemeContrastTest` (text ≥4.5:1; chart/semantic colors + control `outline` ≥3:1 on every surface tier) green.
- Room bump: `check_migrations.py` + `check_history_queries.py` auto-detect the newest schema and check the whole migration chain; `DatabaseMigrationTest` seeds v4 SQL (no room-testing dependency).
- Settings (kmp-settings, `settings/SettingsDefinition.kt`): removing/renaming a key needs `SCHEMA_VERSION` + a `MigrationManager` step in `di/AppModules.kt`.
- Changing `ShellUserService` (e.g. its command allowlist) → bump `SERVICE_VERSION` in `battery/shizuku/ShizukuBridge.kt`.
- Compose behavior tests of `XxxContent` go in `androidTest` (`createAndroidComposeRule<ComponentActivity>()`; no Robolectric). If a test exposes an app bug, log it in PROGRESS.md debt instead of silently changing app behavior.
- Every new use case / repository / ViewModel gets a unit test. Prefer fakes over mocks.
- Never bump `compileSdk`/`targetSdk`, AGP, Gradle, Kotlin, or JDK target without asking. Dependencies go through the version catalog if one exists.
- Never read, print, or commit: keystores, `local.properties`, `google-services.json`, `.env*`.

## Token discipline
- Read files by section; grep first. Skip `build/`, `.gradle/`, `*.iml`, generated `R`/`BuildConfig`/`*_Impl`.
- For AndroidX / library APIs use context7 instead of recalling from memory.
- One build per change-set, not per file. Batch edits, then build once.
- Don't restate file contents in chat; reference `path:line`.
