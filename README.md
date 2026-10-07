# BatStats

![Banner](fastlane/metadata/android/en-US/images/banner.svg)

BatStats monitors battery readings and observed charging/discharging sessions. Ordinary readings use Android BatteryManager. Advanced statistics use Shizuku when running and authorized, otherwise root, then ADB where supported; on Android 16/API 36, per-app batterystats access needs Shizuku or root. Availability depends on the device; missing data is not zero consumption.

This development branch targets Android 16/API 36. The [step-by-step baseline-to-current changelog](docs/BASELINE_TO_CURRENT.md) is a historical implementation record; current project guidance is in this README and the [build guide](docs/BUILD_AND_INSTALL.md). Implementation and validation details in older checkpoint reports are historical; see the [validation record](docs/VALIDATION.md) for their scope. Device-specific behavior must be verified on the target device.

## What's in the app

Four tabs: **Now** (live readings, a Health card, a daily summary of today's charge/discharge, and today's top-draining apps), **History** (past charging/discharging sessions), **Apps** (per-app battery/CPU/network breakdown from Android's own statistics), and **Settings**. Now's Health card and the dedicated Health screen show a full-charge capacity estimate, based on observed charge/discharge sessions, and a health percentage against design capacity. A charging estimate to full uses Android's own estimate where the device supports it, otherwise the app's own charger-taper model. Each finished discharge session records its own per-app breakdown, from a privileged dump taken automatically at unplug and again at the next plug-in — no periodic per-app polling happens otherwise. A Quick Settings tile shows live current/power while the shade is open and toggles monitoring.

The UI is dark-only, with an OLED (pure black) toggle and opt-in dynamic (Material You) color on Android 12+. On devices that report `CURRENT_NOW` in the wrong unit or with an inverted sign, BatStats can detect and correct this automatically from observed evidence, with a dismissible, undoable notice when a correction changes what's shown; Settings can also set the unit/sign by hand. See [the measurement guide](docs/MEASUREMENTS.md) for how the detection works and where it stops.

Removed since the previous stable release: the old Kernel/System detail tabs, the in-app "Reset Android battery statistics" action, the theme picker (the app is now dark-only), and the per-user sampling-interval setting — cadence is now fixed (2 s while something needs a live reading, 30 s with the screen on, 300 s with it off) and not user-configurable.

## Advanced access

Start Shizuku and authorize BatStats from the app. Shizuku is the preferred source when running and authorized. Its shell mode does not grant every root-only capability. On connection loss, ordinary readings remain available; a failed privileged read is not silently replaced by another source.

For ADB mode, use the installed package (`org.mlm.batstats.debug` for debug builds). Android 16/API 36 requires both permissions below and usage-stat app-op access for the ADB grant, but cross-user refusal still prevents ADB-only access to per-app batterystats; that capability requires Shizuku or root. The grant itself may persist across reboot, but a persistent grant does not restore that capability. For API 36 per-app stats, select Shizuku (when running and authorized) or root; otherwise the ADB path remains available only where platform access permits it.

```sh
adb shell pm grant org.mlm.batstats.debug android.permission.DUMP
adb shell pm grant org.mlm.batstats.debug android.permission.PACKAGE_USAGE_STATS
adb shell appops set org.mlm.batstats.debug GET_USAGE_STATS allow
```

Return to Advanced statistics and refresh. Grants may be refused by a device policy or build; collection errors remain visible. BATTERY_STATS and cross-user permissions are not substitutes for these dump permissions. Backend selection uses Shizuku only when it is running and authorized, otherwise root, then ADB where supported. Root mode requires an installed, authorized `su` implementation. This access behavior is implemented in `ShellRunner`; device availability remains platform-dependent.

Per-app breakdowns need `QUERY_ALL_PACKAGES` to name and draw the icon of any app batterystats reports, including ones with no launcher entry. This permission is subject to Google Play review and a submission can be rejected over it even though a local build installs fine; GitHub and F-Droid distribution are not affected. Non-root Shizuku also does not survive a reboot on its own — expect the Apps screen to show "no access" after a restart until it's started again, which is normal Shizuku behavior, not a BatStats failure.

## What the values mean

- Android properties use µA (current), µAh (charge), nWh (energy); broadcast voltage uses mV and temperature uses tenths Celsius. Positive current means charging according to the Android contract. The database always keeps the raw value; a per-device unit/sign correction can be detected and applied automatically from observed evidence, shown with an undoable notice, and the charge counter itself is never rescaled. Vendor behavior still needs hardware verification.
- BatStats observation starts when monitoring starts/resets. It excludes detected gaps and earlier consumption. Screen-off includes noninteractive AOD; it does not prove CPU sleep. CPU suspend and Android Doze are reported separately.
- Counter-derived charge changes and voltage-based energy estimates include coverage. Remaining-time predictions require enough stable observed data and remain estimates.
- Advanced UID estimates use Android’s cumulative statistics window, not BatStats history. Shared UIDs cannot be split reliably into separate app consumption. Activity counts and duration do not prove excessive drain.

See [platform and source notes](docs/PLATFORM_NOTES.md) for contracts and limits. Existing production installations require the original signing key for an in-place update; development APKs use a separate package/signature.

## Monitoring and alerts

Sampling runs at 2 seconds while something needs a live reading (Now, an open session's details, or the Quick Settings tile with the shade open), 30 seconds with the screen on otherwise, and 300 seconds with the screen off; this is fixed, not a setting. Sampling does not wake the CPU; gaps remain explicit. No measured battery-saving percentage is claimed. Per-app dumps are separate and never periodic: on-demand from a screen, or automatically at a session's start/end, cached for 60 seconds.

Battery alerts run only while monitoring is active. Low/high level and temperature alerts use hysteresis and remember the current alert episode across service restarts. Full means Android reports FULL, not merely 100%. High-discharge alerts require at least 3 readings spanning 1 minute and do not identify the cause; with the screen off (300-second polling) that can put the alert roughly 10–15 minutes behind the actual onset — see [platform notes](docs/PLATFORM_NOTES.md). Sound, vibration and permission are controlled in Android notification settings. The monitoring notification stays quiet and detailed; ineffective legacy display/heuristic switches have been retired while saved keys remain compatible. Some OEM skins (e.g. MIUI) ignore the notification's small bitmap icon and show the launcher icon instead — a platform limitation, not a bug.

## History and privacy

History export is deliberate: JSON and CSV include units, UTC timestamps, data sources and reporting periods. Date filters select samples and overlapping sessions; session totals keep their full original windows. Imports validate the complete file before committing, skip identical records, reject conflicts, and never resume imported sessions. Files are limited to 64 MiB and imports cannot exceed 100,000 stored samples or 10,000 sessions.

Clearing history stops monitoring and deletes battery samples, sessions and stored app statistics. It does not reset Android system battery statistics, preferences or alarm rules. Export first to retain a copy. Automatic cloud/device transfer includes only preferences; battery history requires explicit export. Reports stay local until you choose a destination or share them.

## Build and install

See the [phone build and signing guide](docs/BUILD_AND_INSTALL.md) for the manual APK workflow, Preview installation and update compatibility. Historical hosted validation checkpoints are recorded in [VALIDATION.md](docs/VALIDATION.md); they do not establish validation of later source changes or device-specific behavior.

The [measurement guide](docs/MEASUREMENTS.md) explains sources, units, observation periods and estimates. See [actual validation](docs/VALIDATION.md) for completed checks and hardware limitations, and [localization](docs/LOCALIZATION.md) for supported translations and English fallback. Spanish (`values-es`) and Turkish (`values-tr`) are complete but machine-quality translations, flagged for native-speaker review; other languages fall back to English.

## Development

Use JDK 21 and Android SDK 37 to compile (configure the SDK through `ANDROID_HOME`; the documented host path is only an example). The project targets API 36 and supports minimum API 26. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Android tests require a connected device/emulator (`:app:connectedDebugAndroidTest`).

## Contributing and license

Issues and PRs should include Android version, access mode, reporting period and reproducible behavior. Avoid sharing private app/activity data unnecessarily. See [LICENSE](LICENSE).
