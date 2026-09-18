# Platform and measurement constraints

## Android 16 and background monitoring

The runtime target is Android 16/API 36. Compile SDK 37 is required by existing AndroidX dependencies; compiling against it does not change the runtime target or minimum supported API 26. The baseline alpha Compose BOM required 37.1 despite declaring 37.0; the stable BOM avoids that mismatch.

Continuous, user-visible battery monitoring uses `specialUse`, with its use case declared in the manifest. Battery monitoring is not human health tracking. `dataSync` has a six-hour background limit on Android 15+ and is unsuitable for indefinite monitoring. Google Play submission still requires review of the declared special-use purpose; local installation does not prove Play approval.

Android 16 enforces edge-to-edge layouts and enables predictive back. Screens must respect system insets and remain usable after rotation, at large font sizes and on larger windows.

Sources: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), [Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-16).

## Shizuku and other access modes

Shizuku user services run as shell or root, depending on how Shizuku was started. Shell access does not grant every root-only capability. A running service and authorization are separate states. Reads must carry their actual source and failure state; incomplete output is not a successful sample. Reconnection starts a new baseline for cumulative differences. Root and ADB-granted modes remain supported, with their respective permission limits.

For ADB-granted reads, Android16 `BatteryStatsService` checks DUMP plus PACKAGE_USAGE_STATS permission and an allowed/default usage app-op. BATTERY_STATS alone does not authorize dumps. `-c --charged` requests the current checkin-format window; `--checkin` can instead consume a saved completed report. Included history is discarded by the parser and output remains bounded; oversized or interrupted responses are failures.

Sources: [Android16 BatteryStatsService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/BatteryStatsService.java), [DumpUtils](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/com/android/internal/util/DumpUtils.java).

Source: [Shizuku API and UserService lifecycle](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md).

## Sensor contracts and screen state

Android reports current in microamperes (positive into the battery), charge in microampere-hours and remaining energy in nanowatt-hours. Unsupported long properties return `Long.MIN_VALUE`. Average-current hardware windows vary. `computeChargeTimeRemaining()` is a system approximation, not a guaranteed completion time. Vendor violations cannot be corrected by assuming every small value uses different units.

`PowerManager.isInteractive()` reports readiness for interaction, not whether every display pixel is lit. Noninteractive ambient/Always On Display belongs to the screen-off category in this app's observation model. Screen-off, CPU suspend and Android Doze are distinct. Executing app code cannot establish that the CPU is asleep at that instant; elapsed/uptime differences describe past intervals.

Sources: [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager), [PowerManager](https://developer.android.com/reference/android/os/PowerManager#isInteractive()), [Android 16 checkin producer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/os/BatteryStats.java).

## Samsung verification boundary

Samsung documents that deep-sleeping apps only run when opened. If monitoring stops, inspect Settings → Battery → Background usage limits for BatStats and Shizuku; menu wording varies by One UI version. Never-sleeping settings may help but are not proof that a service cannot be stopped. Do not disable device-wide protections as a generic workaround.

No physical Samsung device is available. Emulated sensor inputs cannot validate Samsung current polarity/scaling, capacity calibration, actual AOD events, OEM process management, or physical battery overhead. Functional tests and screenshots must identify their emulator/API and distinguish injected inputs from real readings.

Source: [Samsung background usage limits](https://www.samsung.com/us/support/answer/ANS10003442/).

## Advanced statistics contracts

Android checkin v9 supplies UID total, screen and proportional charge estimates, not every per-UID component. The proportional value is an alternative total, not an extra amount to add. Other activity counters cannot establish energy or causation. Job/sync records place milliseconds before count; mobile-active UID time is microseconds; global Bluetooth uses `gble` rather than per-UID `ble`. Idling counters are not Doze maintenance windows. Package maps use appId, so raw UID/user identity is retained and shared/removed/profile limitations remain visible.

Android16 also documents `dumpsys batterystats --usage` and `--usage --proto`; these are possible component-report sources, but have different schemas/windows and cannot be silently merged into the checkin snapshot. Existing UI component fields were never populated. They now show only fields this source actually reports; device component estimates and per-UID activity remain available. A separate validated component transport would be required before presenting additional per-UID estimates.

Kernel attributes use µV, µA, µAh, µWh, seconds and tenths Celsius by ABI. Full-charge capacity is a remembered gauge threshold, not a laboratory capacity measurement. CPU `time_in_state` uses10ms usertime units and covers time since driver load/reset, which can differ from time since boot.

Sources: [Linux power supply class](https://cdn.kernel.org/doc/html/latest/power/power_supply_class.html), [CPU frequency statistics](https://kernel.org/doc/html/latest/cpu-freq/cpufreq-stats.html).

## Backup and alert preferences

Android backs up databases and app files by default. Both the pre-Android12 backup rules and Android12+ cloud/device-transfer rules explicitly include only the settings DataStore directory. Battery databases and diagnostic files are not included; future diagnostics belong in noBackupFilesDir. Explicit exports remain the user-controlled transfer path. See [Auto Backup rules](https://developer.android.com/identity/data/autobackup).

Android notification-channel sound and vibration are controlled by the user after channel creation. A working settings screen must open the channel settings rather than imply that unrelated preference switches override Android. See [notification channels](https://developer.android.com/develop/ui/compose/notifications/channels).

## Local diagnostics and sharing

Sources and diagnostics shows raw, validated Android battery units and UTC capture times, together with the app observation window and Android's separate batterystats window. These reports do not infer a battery-health percentage or attribute drain from activity duration alone. Unsupported readings remain unavailable.

The local diagnostic log accepts fixed event codes only, keeps the latest 60 entries, groups adjacent repeated conditions and bounds the file to 32 KiB. An AtomicFile in noBackupFilesDir survives normal app restarts without entering Android cloud or device-transfer backups. Writes are coalesced to at most once per minute without a wake lock or alarm; a process interruption can lose recent events. Persistence is not guaranteed within a minute while the CPU is asleep. Storage failure is shown in the screen.

Share report opens Android's chooser only after a user tap. The report contains app/API version, reading units/timestamps, observation coverage, privilege availability and fixed event codes. It excludes app/UID lists, raw system dumps, internal observation/session IDs and device identifiers. Source read failures are represented by status/codes rather than copied command output or exception messages. History clearing does not clear diagnostic events or change Android system statistics.

Implementation references: [AtomicFile integrity and caller-owned synchronization](https://developer.android.com/reference/android/util/AtomicFile), [Room observable query invalidation](https://developer.android.com/training/data-storage/room/async-queries). The diagnostic store has one writer after its initial read; AtomicFile itself supplies no lock.

## Delivery workflow review (preparation only)

The existing reusable [Android release workflow](https://raw.githubusercontent.com/mlm-games/ci/v1/.github/workflows/android-release.yml) has `contents: write`, commits/pushes version or changelog changes, and publishes artifacts/releases. Its `prerelease` input is supported; it has no `upload_releases` input. At baseline, BatStats' wrapper failed to forward its similarly named toggles and defaulted to the production Play track. The local fixes below add a separate read-permission manual APK/check/report workflow; merely disabling Play upload does not make the shared release workflow a build-only operation. No workflow was triggered during this inspection.

GitHub's [manual workflow documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow) requires the dispatch-enabled workflow to exist on the default branch for the Run workflow control to appear; a branch can then be selected. Document that requirement in the eventual phone build instructions. Publishing/merging/triggers still require explicit user approval.

## Local workflow preparation

The new build-only workflow pins reviewed action release tags to commits and requests contents:read, with checkout credentials not persisted. actionlint1.7.12 validates its syntax. Optional signing files stay in runner temporary storage; configuration caching is disabled for CI commands that may use signing secrets. The existing release workflow now gates its reusable publishing job on upload_releases, forwards prerelease, uses org.mlm.batstats, and defaults Play/version bump to none. It still needs explicit approval before any remote run. New manual dispatch workflows must be present on the default branch before GitHub displays Run workflow.

Action versions were checked against the official repositories: [checkout](https://github.com/actions/checkout), [setup-java](https://github.com/actions/setup-java), [upload-artifact](https://github.com/actions/upload-artifact), [Gradle setup](https://github.com/gradle/actions), [Android setup](https://github.com/android-actions/setup-android) and [emulator runner](https://github.com/ReactiveCircus/android-emulator-runner). Local syntax validation is not a hosted workflow execution.

APK-dist0.5.2 bytecode and generated output-metadata.json confirm ABI version-code offsets: x86 −3, x86_64 −2, armeabi-v7a −1, arm64-v8a unchanged, universal +1. Thus baseline source734 and universal APK735 are compatible with the same build; this difference is not evidence of different source revisions. Artifact collection reads actual output metadata. The Gradle wrapper now checks the [official9.7.1 distribution checksum](https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256).

## Compose runtime alignment

Hosted API36 navigation exposed a Material3 `CustomStyle.applyStyle` ABI mismatch: settings UI0.8.3 transitively selected Material3 1.5.0-alpha17 while the stable BOM selected Foundation1.12.1. A normal [Compose BOM platform permits version overrides](https://developer.android.com/develop/ui/compose/bom); merely selecting a stable BOM did not make every resolved component stable. The app/test dependency declarations now enforce the stable BOM together. Inspection of the published settings AAR confirms all45 referenced Material3 members exist in stable1.4.0; runtime revalidation remains required. The earlier teardown exception was secondary to this text-field crash.

Widget validation uses the public [AppWidgetHost](https://developer.android.com/reference/android/appwidget/AppWidgetHost) and [widget binding API](https://developer.android.com/reference/android/appwidget/AppWidgetManager#bindAppWidgetIdIfAllowed(int,%20android.content.ComponentName)) in a disposable test host. It verifies system delivery of production RemoteViews with scripted values; it does not establish Samsung launcher behavior.

Room2.8.5's published sources (`RoomDatabase.android.kt`, `DBUtil.android.kt`) show that close cancels the database query scope and suspend queries use that context. A query CancellationException must be distinguished from cancellation of the repository owner; otherwise one unavailable database can silently stop subsequent lifecycle handling.
