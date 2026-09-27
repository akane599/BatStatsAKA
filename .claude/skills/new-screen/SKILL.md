---
name: new-screen
description: Scaffold a new BatStats Compose screen the way the existing 8 are built — Koin wrapper + stateless XxxContent, Navigation 3 route in ui/NavGraph.kt, strings in all locales, optional ViewModel + unit test, and a @ScreenPreviews screenshot test with reference images. Use when the user asks to add a screen/page/destination.
argument-hint: "<ScreenName> — <one-line purpose>"
---

# New BatStats screen

Arguments: `$ARGUMENTS` (e.g. `Alarms — configured battery alarms`). Derive `Xxx` (PascalCase, no "Screen" suffix). Code shapes are in [template.md](template.md) — copy them, don't improvise a different structure.

## Rules (from CLAUDE.md, enforced by review)
- Wrapper `XxxScreen` holds everything stateful: `koinViewModel`/`koinInject`, `collectAsStateWithLifecycle`, effects, launchers, Intents, clipboard, system services, root/Shizuku. `XxxContent` is stateless: plain state + lambdas, `modifier: Modifier = Modifier` first optional param, applied to the root `Scaffold`.
- No literal user-facing strings, no `!!`, trailing commas, `val` by default. Don't add `Color(0x…)`; use `MaterialTheme`.
- Keep other screens' public signatures stable; if a caller needs an `onOpenXxx` callback, update its wrapper, its `XxxContent`, and its screenshot test together.

## Steps
1. **State.** If the screen reads data, add `app/src/main/java/app/batstats/viewmodel/XxxViewModel.kt` exposing `StateFlow`s, register it in `app/src/main/java/app/batstats/di/AppModules.kt` next to the others (`viewModel { XxxViewModel(get()) }`), and add `app/src/test/java/app/batstats/viewmodel/XxxViewModelTest.kt` (JUnit4 + kotlinx-coroutines-test, hand-written fakes — the project has no mocking library).
2. **Screen.** Create `app/src/main/java/app/batstats/ui/screens/XxxScreen.kt` from the template. More than ~6 inputs → an `XxxUiState` data class in the same file.
3. **Navigation.** In `app/src/main/java/app/batstats/ui/NavGraph.kt` add `@Serializable data object Xxx : Screen` (or a `data class` for arguments) to `sealed interface Screen`, an `entry<Screen.Xxx> { XxxScreen(onBack = popBack) }`, and wire the caller's `onOpenXxx = { backStack.add(Screen.Xxx) }`.
4. **Strings.** Add every new key to `app/src/main/res/values/strings.xml` **and** `values-es/` **and** `values-tr/` — `scripts/check_resources.py` fails on missing translations or mismatched format args. Flag machine-made translations in your report.
5. **Screenshot test.** Create `app/src/screenshotTest/kotlin/app/batstats/ui/screens/XxxScreenshotTest.kt` from the template: `@PreviewTest @ScreenPreviews` populated state, `@PhonePreview` OLED, plus `@PhonePreview`/`@TallPhonePreview` for states that look very different (empty, loading, error). Dates only from `FIXED_TIME_MS`; no `System.currentTimeMillis()`/`Random`; preview names without ".".
6. **Build + references** (one Gradle run per step; zsh — redirect and check `$?`):
   - `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain -q`
   - `python3 scripts/check_resources.py`
   - `./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --console=plain -q` (adds the new screen's PNGs; existing ones stay byte-identical — check `git status --porcelain -- app/src/screenshotTestDefaultDebug` shows only `??` for this screen)
   - Read the new `W400H500`, `W900H1000` and `Dark` PNGs and fix anything clipped, blank, or below the fold.
7. **Verify (CLAUDE.md):** dispatch the `compose-reviewer` agent on the new files and a fresh agent to run build + unit + `:app:testDebugScreenshotTestDefaultTestSuite` and open the screen on the emulator. Update `PROGRESS.md`.
