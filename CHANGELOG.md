## 6.2.7-dev — UI and measurement overhaul (unreleased)

### Added
- Four-tab navigation: Now, History, Apps, Settings, replacing the previous screen layout.
- Health card (Now) and a dedicated Health screen: full-charge capacity and a health percentage estimated from observed charge/discharge sessions.
- A daily summary of today's charge and discharge, and today's top-draining apps, on Now.
- Automatic per-device detection of `CURRENT_NOW` readings reported in the wrong unit or with an inverted sign, applied only once the evidence is consistent, with a dismissible, undoable notice when it changes a reading; Settings can still set the unit/sign by hand.
- A per-app battery breakdown recorded automatically for every finished discharge session, in addition to the existing on-demand Apps tab.
- A Quick Settings tile showing live current/power while the shade is open, and toggling monitoring.
- An OLED (pure black) display option and opt-in dynamic (Material You) color on Android 12+.
- Spanish and Turkish translations (machine-quality; flagged for native-speaker review).

### Changed
- Charging time-to-full now prefers Android's own estimate where the device supports it, and otherwise learns an 80→100% taper per charger instead of a flat estimate.
- Discharge time-to-empty now uses a time-weighted average of the observed drain rate, seeded from the app's own 7-day typical rate before enough live data has accrued.
- Sampling cadence is now fixed (2 seconds while a screen needs a live reading, 30 seconds with the screen on, 300 seconds with it off) instead of a user-adjustable interval.
- Per-app dumps are on-demand and event-triggered only (a screen asking, or a session's start/end), cached for 60 seconds; nothing polls per-app data on a timer.
- Reworked validated readings, observed intervals, automatic sessions, Shizuku/access recovery and Android 16 statistics parsing.
- Reworked history migrations/import/export, diagnostics, alerts, and the monitoring notification and widgets for the new UI.

### Removed
- The Kernel/System detail tabs.
- The in-app "Reset Android battery statistics" action.
- The theme picker (light/dark selection) — the app is dark-only, with the new OLED toggle in its place.
- The user-configurable sampling-interval setting.

### Fixed
- Now's Health card and the Health screen always show the same health percentage: both read one app-wide design-capacity value, checked once instead of per screen, so reopening Health no longer re-prompts for root or flashes a "Checking" state.
- A discharge session's per-app breakdown is no longer captured from a plug/unplug that immediately reverses: the start and end dumps are debounced, so a quick plug cycle doesn't waste a privileged dump or record a misleading breakdown.

Final reset-dialog/notification changes remain unverified; saved APKs predate those changes. See the [ordered baseline-to-current changelog and remaining tasks](docs/BASELINE_TO_CURRENT.md) for details and actual validation limits.

## v6.2.6

- Cleanup old data and show Shared UIDs as System
- chore(deps): bump the gradle-dependencies group with 5 updates
- Map process by UIDs now, for the stats


## v6.2.5

- Cleanup old data and show Shared UIDs as System
- chore(deps): bump the gradle-dependencies group with 5 updates
- Map process by UIDs now, for the stats


## v6.2.4

- chore(deps): bump com.android.application
- chore(deps): bump the gradle-dependencies group with 2 updates


## v6.2.3

- chore(deps): bump com.android.application
- chore(deps): bump the gradle-dependencies group with 2 updates


## v6.2.2

- fix a format error (crashes prev. on opening the alarm screen)
- Add pure black theme from fluffy
- Add the ability to grant priv. perms via adb (#31)
- change app id and add more icon files
- Bump the gradle-dependencies group with 2 updates
- Bump the gradle-dependencies group with 2 updates
- Bump gradle-wrapper from 9.6.1 to 9.7.0 in the gradle-dependencies group
- Bump com.google.devtools.ksp in the gradle-dependencies group
- Bump the gradle-dependencies group with 4 updates
- Bump com.android.application in the gradle-dependencies group
- Bump androidx.compose.runtime:runtime from 1.11.2 to 1.11.4
- Bump dependabot/fetch-metadata from 2 to 3 in the github-actions group
- add a setting to control stats refresh interval
- Bump androidx.compose.animation:animation from 1.11.2 to 1.11.4
- Bump com.google.devtools.ksp from 2.3.8 to 2.3.10
- Bump com.android.application from 9.2.1 to 9.3.0
- Bump org.jetbrains.kotlin:kotlin-stdlib from 2.3.21 to 2.4.10
- Bump androidx.compose:compose-bom-alpha from 2026.05.01 to 2026.07.00
- Bump androidx.core:core-ktx from 1.18.0 to 1.19.0
- Bump gradle-wrapper from 9.4.1 to 9.6.1
- Bump com.google.androidbrowserhelper:androidbrowserhelper
- Bump io.insert-koin:koin-bom from 4.2.1 to 4.2.2
- Bump navigation3Runtime from 1.1.2 to 1.1.4
- Bump lifecycle from 2.10.0 to 2.11.0
- Bump kotlin from 2.3.21 to 2.4.10
- Bump actions/checkout from 6 to 7 in /.github/workflows
- Update compile-sdk to 37, and bump all libs...
- Bump softprops/action-gh-release from 2 to 3 in /.github/workflows
- Bump dependabot/fetch-metadata from 2 to 3 in /.github/workflows
- Bump gradle/actions from 5 to 6 in /.github/workflows
- Bump actions/upload-artifact from 6 to 7 in /.github/workflows
- fix build, and bump dep versions
- Use m3e components
- bump agp to 9, jvm to 21


## v6.2.1

- fix a format error (crashes prev. on opening the alarm screen)
- Add pure black theme from fluffy
- Add the ability to grant priv. perms via adb (#31)
- change app id and add more icon files
- Bump the gradle-dependencies group with 2 updates
- Bump the gradle-dependencies group with 2 updates
- Bump gradle-wrapper from 9.6.1 to 9.7.0 in the gradle-dependencies group
- Bump com.google.devtools.ksp in the gradle-dependencies group
- Bump the gradle-dependencies group with 4 updates
- Bump com.android.application in the gradle-dependencies group
- Bump androidx.compose.runtime:runtime from 1.11.2 to 1.11.4
- Bump dependabot/fetch-metadata from 2 to 3 in the github-actions group
- add a setting to control stats refresh interval
- Bump androidx.compose.animation:animation from 1.11.2 to 1.11.4
- Bump com.google.devtools.ksp from 2.3.8 to 2.3.10
- Bump com.android.application from 9.2.1 to 9.3.0
- Bump org.jetbrains.kotlin:kotlin-stdlib from 2.3.21 to 2.4.10
- Bump androidx.compose:compose-bom-alpha from 2026.05.01 to 2026.07.00
- Bump androidx.core:core-ktx from 1.18.0 to 1.19.0
- Bump gradle-wrapper from 9.4.1 to 9.6.1
- Bump com.google.androidbrowserhelper:androidbrowserhelper
- Bump io.insert-koin:koin-bom from 4.2.1 to 4.2.2
- Bump navigation3Runtime from 1.1.2 to 1.1.4
- Bump lifecycle from 2.10.0 to 2.11.0
- Bump kotlin from 2.3.21 to 2.4.10
- Bump actions/checkout from 6 to 7 in /.github/workflows
- Update compile-sdk to 37, and bump all libs...
- Bump softprops/action-gh-release from 2 to 3 in /.github/workflows
- Bump dependabot/fetch-metadata from 2 to 3 in /.github/workflows
- Bump gradle/actions from 5 to 6 in /.github/workflows
- Bump actions/upload-artifact from 6 to 7 in /.github/workflows
- fix build, and bump dep versions
- Use m3e components
- bump agp to 9, jvm to 21


## v6.2.0

- Add the ability to grant priv. perms via adb (#31)
- change app id and add more icon files
- Bump the gradle-dependencies group with 2 updates
- Bump the gradle-dependencies group with 2 updates
- Bump gradle-wrapper from 9.6.1 to 9.7.0 in the gradle-dependencies group
- Bump com.google.devtools.ksp in the gradle-dependencies group
- Bump the gradle-dependencies group with 4 updates
- Bump com.android.application in the gradle-dependencies group
- Bump androidx.compose.runtime:runtime from 1.11.2 to 1.11.4
- Bump dependabot/fetch-metadata from 2 to 3 in the github-actions group
- add a setting to control stats refresh interval
- Bump androidx.compose.animation:animation from 1.11.2 to 1.11.4
- Bump com.google.devtools.ksp from 2.3.8 to 2.3.10
- Bump com.android.application from 9.2.1 to 9.3.0
- Bump org.jetbrains.kotlin:kotlin-stdlib from 2.3.21 to 2.4.10
- Bump androidx.compose:compose-bom-alpha from 2026.05.01 to 2026.07.00
- Bump androidx.core:core-ktx from 1.18.0 to 1.19.0
- Bump gradle-wrapper from 9.4.1 to 9.6.1
- Bump com.google.androidbrowserhelper:androidbrowserhelper
- Bump io.insert-koin:koin-bom from 4.2.1 to 4.2.2
- Bump navigation3Runtime from 1.1.2 to 1.1.4
- Bump lifecycle from 2.10.0 to 2.11.0
- Bump kotlin from 2.3.21 to 2.4.10
- Bump actions/checkout from 6 to 7 in /.github/workflows
- Update compile-sdk to 37, and bump all libs...
- Bump softprops/action-gh-release from 2 to 3 in /.github/workflows
- Bump dependabot/fetch-metadata from 2 to 3 in /.github/workflows
- Bump gradle/actions from 5 to 6 in /.github/workflows
- Bump actions/upload-artifact from 6 to 7 in /.github/workflows
- fix build, and bump dep versions
- Use m3e components
- bump agp to 9, jvm to 21


## v6.1.1

- Bump actions/checkout from 6 to 7 in /.github/workflows
- Update compile-sdk to 37, and bump all libs...
- Bump softprops/action-gh-release from 2 to 3 in /.github/workflows
- Bump dependabot/fetch-metadata from 2 to 3 in /.github/workflows
- Bump gradle/actions from 5 to 6 in /.github/workflows
- Bump actions/upload-artifact from 6 to 7 in /.github/workflows
- fix build, and bump dep versions
- Use m3e components
- bump agp to 9, jvm to 21


## v6.0.3

- Bump softprops/action-gh-release from 2 to 3 in /.github/workflows
- Bump dependabot/fetch-metadata from 2 to 3 in /.github/workflows
- Bump gradle/actions from 5 to 6 in /.github/workflows
- Bump actions/upload-artifact from 6 to 7 in /.github/workflows
- fix build, and bump dep versions
- Use m3e components
- bump agp to 9, jvm to 21


## v6.0.2

- Use m3e components
- bump agp to 9, jvm to 21


## v6.0.1

- bump major ver
- remove unneeded tasks
- Add detailed drain stats notification (requires shizuku) (fix #5)


## v5.0.2

- update lib ver
- Remove old rules
- Rm nav bar related, and other leftovers
- Navigation 3 impl, and remove unused dialogs
- Implement settings export
- Update to my kmp-settings module, and use koin everywhere... (Settings Export, next commit)
- Update README.md
- Bump actions/upload-artifact from 5 to 6 in /.github/workflows


## v5.0.1

- update lib ver
- Remove old rules
- Rm nav bar related, and other leftovers
- Navigation 3 impl, and remove unused dialogs
- Implement settings export
- Update to my kmp-settings module, and use koin everywhere... (Settings Export, next commit)
- Update README.md
- Bump actions/upload-artifact from 5 to 6 in /.github/workflows


## v4.3.4

- Update proguard-rules.pro


## v4.3.3

- add shizuku rule


## v4.3.2

- debug shizuku collection works, check with rel version


## v4.3.1

- Update README.md
- Add extended battery stats (most devices will definitely not have 50% of details, that is related to the OSs and hardware).
- Bump actions/checkout from 5 to 6 in /.github/workflows
- rm warns
- use remember savable for better perf
- No cost, too great (add top drainers perm either via shizuku/root or usage stats)
- Use date formatter everywhere, add conf cache
- Bump actions/upload-artifact from 4 to 5 in /.github/workflows
- add the test monochrome icon
- Bump gradle/actions from 4 to 5 in /.github/workflows


## v4.1.4

- add the test monochrome icon


## v4.1.3

- Fix crash on A15+ when using "Autostart with boot" and restarting


## v4.1.2

- Only auto stop charge sessions


## v4.1.1

- Bump build.gradle.kts
- Fix session card data only displaying 0% -> 0%
- Misc fixes
- Fix graph (not sessions yet)


## v4.0.1

- Fix monitoring button


# Changelog

## v3.2.7

- Update android.yml
- Fix widgets and improve ui for release
- Change descs and screenshots
- first commit

