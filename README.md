[English](README.md) | [简体中文](README.zh-CN.md) | [日本語](README.ja.md)

# BatStats

![Banner](fastlane/metadata/android/en-US/images/banner.svg)

BatStats monitors battery readings and observed charging/discharging sessions. Ordinary readings use Android BatteryManager. Advanced statistics use Shizuku, Root, or explicitly granted ADB permissions. Availability depends on the device; missing data is not zero consumption.

This development branch targets Android 16/API36. Implementation and actual validation status are recorded in [PROGRESS.md](PROGRESS.md) and [AUDIT_REPORT.md](AUDIT_REPORT.md). Physical Samsung behavior remains unverified.

## Advanced access

Start Shizuku and authorize BatStats from the app. Shizuku is the preferred source when running and authorized. Its shell mode does not grant every root-only capability. On connection loss, ordinary readings remain available; a failed privileged read is not silently replaced by another source.

For ADB mode, use the installed package (`org.mlm.batstats.debug` for debug builds). Android16 requires both permissions below and usage-stat app-op access:

```sh
adb shell pm grant org.mlm.batstats.debug android.permission.DUMP
adb shell pm grant org.mlm.batstats.debug android.permission.PACKAGE_USAGE_STATS
adb shell appops set org.mlm.batstats.debug GET_USAGE_STATS allow
```

Return to Advanced statistics and refresh. Grants may be refused by a device policy or build; collection errors remain visible. BATTERY_STATS and cross-user permissions are not substitutes for these dump permissions. Root mode requires an installed, authorized `su` implementation.

## What the values mean

- Android properties use µA (current), µAh (charge), nWh (energy); broadcast voltage uses mV and temperature uses tenths Celsius. Positive current means charging according to the Android contract. Vendor behavior still needs hardware verification.
- BatStats observation starts when monitoring starts/resets. It excludes detected gaps and earlier consumption. Screen-off includes noninteractive AOD; it does not prove CPU sleep. CPU suspend and Android Doze are reported separately.
- Counter-derived charge changes and voltage-based energy estimates include coverage. Remaining-time predictions require enough stable observed data and remain estimates.
- Advanced UID estimates use Android’s cumulative statistics window, not BatStats history. Shared UIDs cannot be split reliably into separate app consumption. Activity counts and duration do not prove excessive drain.

See [platform and source notes](docs/PLATFORM_NOTES.md) for contracts and limits. Existing production installations require the original signing key for an in-place update; development APKs use a separate package/signature.

## Monitoring and alerts

Ordinary sampling defaults to 30 seconds; privileged collection defaults to 5 minutes. Shorter intervals improve responsiveness and add monitoring work. Sampling does not wake the CPU; gaps remain explicit. No measured battery-saving percentage is claimed.

Battery alerts run only while monitoring is active. Low/high level and temperature alerts use hysteresis and remember the current alert episode across service restarts. Full means Android reports FULL, not merely 100%. High-discharge alerts require at least 3 readings spanning 1 minute and do not identify the cause. Sound, vibration and permission are controlled in Android notification settings. The monitoring notification stays quiet and detailed; ineffective legacy display/heuristic switches have been retired while saved keys remain compatible.

## History and privacy

History export is deliberate: JSON and CSV include units, UTC timestamps, data sources and reporting periods. Date filters select samples and overlapping sessions; session totals keep their full original windows. Imports validate the complete file before committing, skip identical records, reject conflicts, and never resume imported sessions. Files are limited to 64 MiB and imports cannot exceed 100,000 stored samples or 10,000 sessions.

Clearing history stops monitoring and deletes battery samples, sessions and stored app statistics. It does not reset Android system battery statistics, preferences or alarm rules. Export first to retain a copy. Automatic cloud/device transfer includes only preferences; battery history requires explicit export. Reports stay local until you choose a destination or share them.

## Build and install

See the [phone build and signing guide](docs/BUILD_AND_INSTALL.md) for the manual APK workflow, Preview installation and update compatibility. No workflow has been published or run for this development work.

The [measurement guide](docs/MEASUREMENTS.md) explains sources, units, observation periods and estimates. See [actual validation](docs/VALIDATION.md) for completed checks and hardware limitations, and [localization](docs/LOCALIZATION.md) for supported translations and English fallback.

## Development

Use JDK 21 and Android SDK 37 for compilation; target API 36, minimum API 26. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Android tests require a connected device/emulator (`:app:connectedDebugAndroidTest`). Contributor/workflow rules are in [AGENTS.md](AGENTS.md).

## Contributing and license

Issues and PRs should include Android version, access mode, reporting period and reproducible behavior. Avoid sharing private app/activity data unnecessarily. See [LICENSE](LICENSE).
