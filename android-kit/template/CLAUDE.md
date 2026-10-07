<!-- android-kit -->
# CLAUDE.md — <APP NAME>

<ONE LINE: what the app does and for whom.>
Android, developed on Ubuntu from the CLI. **If the STACK block still has `<placeholders>`, run `/bootstrap` first.**

<!-- STACK:BEGIN (filled by /bootstrap) -->
- Origin: <new | fork of OWNER/REPO @ SHA | existing> · Integration branch: <main>
- Language: <kotlin | java | mixed — ratio> · UI: <Compose | Views | both> · screenshot tests: <compose-preview | none>
- JDK target <17 | 21> · Gradle <x.y> · AGP <x.y> · Kotlin <x.y> · compileSdk <NN> · minSdk <NN>
- Modules: <:app | list> · Architecture: <single-module MVVM | …> · DI/DB/Net: <hilt|koin|none> / <room|none> / <retrofit|ktor|none>
- App id: <APP_ID> · Launcher: <.MainActivity>
- Baseline: <build ok | failing: …> · <N> unit tests, <M> pre-existing failures (PROGRESS.md)
<!-- STACK:END -->

## How work flows here
- **The main session orchestrates; it doesn't implement.** Work becomes Sidequest tickets, executors do it in isolated worktrees on the model each ticket's category routes to (Claude, or GPT through Model Gateway), and the main session integrates after verification. Quick one-line edits to named files stay inline.
- **Routing** (Sidequest profile `android-kit`; `sidequest models` shows it live): GPT implements (GPT-6 Luna mechanical, GPT-6.1 Sol default, GPT-6 Astra hard via `coding.hard.frontier`); UI, review and escalation stay on Claude (Opus, Fable only after Opus stalls); research on Sonnet. UI is never routed to GPT.
- **Big or multi-step changes:** plan mode → `/plan-audit` (routed review ticket with the Android checklist) → pin the plan as a Sidequest story → dispatch waves.
- **UI overhaul:** `/ui-overhaul <scope>`.
- **Bugs:** every ticket's tests must pass before it's integrated; risky ones (weak tests, unchecked callers, high-stakes) also get an Opus review. `/bug-hunt [area]` hunts in existing code; `/code-review` checks a diff before you push.
- **End of a session or story:** `/wrap-up` turns corrections and decisions into rules, CLAUDE.md lines and the decision log (you approve each). Personal preferences go to auto memory ("remember that …").
- **Conventions** live in `.claude/live-rules/rules/` and are injected into every session and executor, so they're not repeated here. **Project map:** `.claude/.codebase-info/` (codebase-mapper).
- **Board:** "show the Sidequest board"; side issues mentioned mid-task get filed, not worked.
- **Plugin health / updates:** `/quartermaster:toolshed-doctor`, `/quartermaster:update-toolshed`. After a few weeks of real work: `/quartermaster:resupply`.
- **Compaction:** your last instructions are saved before compaction and re-injected after; re-read them before acting.

## Commands
- Build: `./gradlew :app:assembleDebug --console=plain -q` · Unit: `:app:testDebugUnitTest` · Lint: `:app:lintDebug`
- Screenshots: `:app:updateDebugScreenshotTest` (write refs) · `:app:validateDebugScreenshotTest` (diff)
- Device: `./gradlew :app:installDebug -q && adb shell am start -n <APP_ID>/<LAUNCHER>` · logs: `adb logcat -d --pid=$(adb shell pidof -s <APP_ID>) | tail -80`
- Emulator: `emulator -avd <AVD> -no-window -no-audio &` then `adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'`
