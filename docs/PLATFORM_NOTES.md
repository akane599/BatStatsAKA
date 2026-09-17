# Platform and measurement constraints

## Android 16 and background monitoring

The runtime target is Android 16/API 36. Compile SDK 37 is required by existing AndroidX dependencies; compiling against it does not change the runtime target or minimum supported API 26. The baseline alpha Compose BOM required 37.1 despite declaring 37.0; the stable BOM avoids that mismatch.

Continuous, user-visible battery monitoring uses `specialUse`, with its use case declared in the manifest. Battery monitoring is not human health tracking. `dataSync` has a six-hour background limit on Android 15+ and is unsuitable for indefinite monitoring. Google Play submission still requires review of the declared special-use purpose; local installation does not prove Play approval.

Android 16 enforces edge-to-edge layouts and enables predictive back. Screens must respect system insets and remain usable after rotation, at large font sizes and on larger windows.

Sources: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), [Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-16).

## Shizuku and other access modes

Shizuku user services run as shell or root, depending on how Shizuku was started. Shell access does not grant every root-only capability. A running service and authorization are separate states. Reads must carry their actual source and failure state; incomplete output is not a successful sample. Reconnection starts a new baseline for cumulative differences. Root and ADB-granted modes remain supported, with their respective permission limits.

Source: [Shizuku API and UserService lifecycle](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md).

## Sensor contracts and screen state

Android reports current in microamperes (positive into the battery), charge in microampere-hours and remaining energy in nanowatt-hours. Unsupported long properties return `Long.MIN_VALUE`. Average-current hardware windows vary. `computeChargeTimeRemaining()` is a system approximation, not a guaranteed completion time. Vendor violations cannot be corrected by assuming every small value uses different units.

`PowerManager.isInteractive()` reports readiness for interaction, not whether every display pixel is lit. Noninteractive ambient/Always On Display belongs to the screen-off category in this app's observation model. Screen-off, CPU suspend and Android Doze are distinct. Executing app code cannot establish that the CPU is asleep at that instant; elapsed/uptime differences describe past intervals.

Sources: [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager), [PowerManager](https://developer.android.com/reference/android/os/PowerManager#isInteractive()), [Android 16 checkin producer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/os/BatteryStats.java).

## Samsung verification boundary

Samsung documents that deep-sleeping apps only run when opened. If monitoring stops, inspect Settings → Battery → Background usage limits for BatStats and Shizuku; menu wording varies by One UI version. Never-sleeping settings may help but are not proof that a service cannot be stopped. Do not disable device-wide protections as a generic workaround.

No physical Samsung device is available. Emulated sensor inputs cannot validate Samsung current polarity/scaling, capacity calibration, actual AOD events, OEM process management, or physical battery overhead. Functional tests and screenshots must identify their emulator/API and distinguish injected inputs from real readings.

Source: [Samsung background usage limits](https://www.samsung.com/us/support/answer/ANS10003442/).
