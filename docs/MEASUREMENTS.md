# Understanding BatStats readings

BatStats reports Android and vendor data. It is not an independent electrical meter. Open **Sources and diagnostics** to inspect the source, units, capture time, coverage and failures behind a reading.

## Ordinary readings

| Value | Basis and limitation |
| --- | --- |
| Battery percentage | Android's level divided by its scale, rounded to a whole percentage. It is not a measured fraction of design capacity. |
| Current | BatteryManager µA, optionally displayed as mA. Android defines positive as flowing into the battery and negative as flowing out. Contradictions with charging state are flagged; the app does not guess a vendor multiplier or flip the sign. |
| Average current | A separate Android property whose averaging period depends on the device. It is not substituted for an unavailable instantaneous reading. |
| Voltage and temperature | Android mV and tenths Celsius; temperature can be displayed in Fahrenheit. These are battery readings, not charger output or CPU temperature. |
| Net power | Current × voltage, converted to mW. Derived from reported inputs; it is not an independent power measurement. |
| Charge and remaining energy | Android charge counter in µAh and energy counter in nWh, where supported. Unsupported sentinels and rejected values are unavailable. A supported zero remains zero. |
| Charging estimate | Android's approximate time to full, labeled as an estimate. |
| Discharge estimate | Remaining reported charge divided by an observed drain trend. Requires at least five points over ten minutes, sufficient counter movement and comparable rates in both halves. Gaps, counter resets and power transitions invalidate the trend. Future use may change it. |

## Observed periods

Live totals start when BatStats begins observing or when **Reset observation** is selected. Starting midway through an unplugged period does not claim earlier consumption. The dashboard, details and monitoring notification use the same observation model and identify the last observed endpoint; notification refresh may lag live readings by its update cadence.

Durations use monotonic clocks. Screen on means interactive; screen off includes noninteractive Always On Display. Locking the device alone does not establish screen-off or sleep. Screen-on/off drain buckets contain discharging intervals only. CPU suspend is elapsed time minus uptime; Android Doze is separately observed. Neither screen-off time nor Doze proves CPU deep sleep.

Charge totals use valid counter differences. Average drain uses only intervals covered by those counters, with at least one minute required. Estimated interval energy uses charge change and mean endpoint voltage. Missing intervals, interrupted collection, clock discontinuities and incompatible counters are excluded; coverage stays visible. No observed screen-off period means no screen-off charge or rate. History sessions identify their own source and period; old records without continuity evidence are marked as legacy.

## Advanced access

Shizuku is preferred. Availability and authorization are separate. Root and explicit ADB grants remain alternatives; failed reads are not silently replaced by a different backend. Ordinary battery readings continue without advanced access.

Android batterystats has its own since-charge/reset window, distinct from BatStats history. App charge values are Android estimates. A UID can include multiple packages or system services; consumption cannot be reliably split among them. Package mappings may include removed apps or profiles. Wakelocks, jobs, alarms, network bytes and CPU activity indicate work, not proof of excessive energy use. Comparisons use one report window. The proportional attributed total is an alternative total and must not be added to the UID total.

Kernel readings require supported files and permissions, generally Root. Full-charge/design capacity ratio is a fuel-gauge estimate dependent on calibration and vendor units. Cycle count alone does not determine battery health. Unsupported fields stay unavailable.

## Monitoring cost and storage

Ordinary sampling defaults to 30 seconds; advanced collection defaults to 5 minutes. Shorter intervals improve responsiveness and perform more work. Polling does not acquire a wake lock or schedule an alarm to wake the CPU. State events also trigger readings. Notification updates are quiet and normally capped to a 30-second cadence, with important state changes surfaced sooner.

History is bounded to 100,000 samples and 10,000 sessions; the selected retention period also applies. Diagnostics retain up to 60 grouped local events in 32 KiB, with coalesced writes. Recent diagnostic events can be lost when the process stops. Export includes sources, units, UTC timestamps and reporting periods; sharing is deliberate.

Samsung current calibration, AOD event behavior, kernel permissions, One UI background management and real monitoring energy use remain unverified on physical hardware. Emulator values and injected test readings cannot validate those properties. See [platform contracts](PLATFORM_NOTES.md) and [actual validation](VALIDATION.md).
