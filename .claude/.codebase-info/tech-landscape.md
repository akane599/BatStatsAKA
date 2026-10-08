# Tech Landscape

*Last Updated: 2026-10-08*

| Area | Choice | Source of truth |
| --- | --- | --- |
| Language | Kotlin only (`kotlin.code.style=official`), JVM toolchain 21 | `app/build.gradle.kts`, `gradle.properties` |
| Build | Gradle wrapper 9.7.1, AGP `9.5.0-alpha07`, Kotlin `2.4.20`, KSP `2.3.12`, configuration cache on, `org.gradle.workers.max=2` | `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradle.properties` |
| SDK | compileSdk 37, targetSdk 36, minSdk 26; core library desugaring | `app/build.gradle.kts` |
| App id / namespace | `com.akane.voltwise` (`.debug`, `.preview` suffixes) / `com.akane.voltwise` | `app/build.gradle.kts` |
| UI | Jetpack Compose (BOM `2026.09.00`, enforced platform), Material 3 + Expressive opt-ins, material-icons-extended | `app/build.gradle.kts` |
| Navigation | Navigation 3 (`navigation3-runtime/ui` 1.1.5, `lifecycle-viewmodel-navigation3`) | `ui/navigation/`, `ui/NavGraph.kt` |
| DI | Koin BOM 4.2.2 (`koin-android`, `koin-androidx-compose`) | `di/AppModules.kt` |
| Persistence | Room 2.8.5 (KSP), DataStore Preferences 1.2.1, SharedPreferences | [database.md](database.md) |
| Settings | `io.github.mlm-games:kmp-settings-*` 0.8.3 (core, ui-compose, KSP) | `settings/` |
| Serialization | kotlinx-serialization-json 1.11.0 (routes, export files) | `ui/navigation/Routes.kt`, `battery/data/ExportImport.kt` |
| Async | kotlinx-coroutines 1.11.0, Flow/StateFlow; one `HandlerThread` for sampling | `battery/data/sampling/SamplingController.kt` |
| Privilege | Shizuku `dev.rikka.shizuku:api`/`provider` 13.1.5, root (`su`), ADB-granted `DUMP` | [privileged-shell.md](privileged-shell.md) |
| Distribution | `io.github.mlm-games.apk-dist` 0.5.2 (ABI splits + universal APK → `build/outputs/distribution`), R8 full mode, fastlane metadata | `app/build.gradle.kts`, `fastlane/metadata/android/en-US/` |
| Tests | JUnit 4.13.2 + kotlinx-coroutines-test (JVM, fakes); AndroidX test runner/rules/ext-junit, UiAutomator, Compose ui-test (device); Compose Preview Screenshot Testing `0.0.1-alpha16` as an AGP test suite | [patterns.md](patterns.md) |
| Lint | Android lint only (no detekt/ktlint/spotless) | — |
| CI | GitHub Actions: `test_if_it_builds.yml` (push/PR → reusable `build-apk.yml` with Android 16 emulator tests), `build-apk.yml` (manual installable APKs), `android.yml` (releases, opt-in), `dependabot-auto-merge.yml` | `.github/workflows/` |

## Dependency notes
- The Compose BOM is applied as `enforcedPlatform`, so kmp-settings-ui can't pull Material3 to an
  incompatible alpha (comment in `app/build.gradle.kts`).
- The XML theme parent is the platform `Theme.Material` (`res/values/styles.xml`), with no MDC library.
- Never bump SDK/AGP/Gradle/Kotlin/JDK or add a dependency unless a ticket says so (live rule).

## Signing / variants
- `debug` (debuggable, `.debug`), `release` (minified, shrunk, signed from `KEYSTORE_PATH`/`KEY_ALIAS` env),
  `preview` (`initWith(release)`, `.preview`, signed with the optional `PREVIEW_*` env keystore, otherwise debug).
- Details: `docs/BUILD_AND_INSTALL.md`. Never read or commit keystores or `local.properties`.

## Localization
- `en`, `es`, `tr` (`androidResources.localeFilters`, `res/xml/locales_config.xml`).
- Strings are split per screen: `res/values*/strings_<screen>.xml`. `scripts/check_resources.py`
  enforces parity and format arguments. See `docs/LOCALIZATION.md`.
