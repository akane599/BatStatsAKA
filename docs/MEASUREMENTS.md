# Understanding BatStats readings

BatStats reports Android and vendor data. It is not an independent electrical meter. Open **Sources and diagnostics** to inspect the source, units, capture time, coverage and failures behind a reading.

## Ordinary readings

| Value | Basis and limitation |
| --- | --- |
| Battery percentage | Android's level divided by its scale, rounded to a whole percentage. It is not a measured fraction of design capacity. |
| Current | BatteryManager µA, optionally displayed as mA. Android defines positive as flowing into the battery and negative as flowing out. The database always keeps the raw value; the app can automatically detect and apply a per-device unit/sign correction — see **Automatic calibration** below — but it never guesses without evidence, and it never rescales the charge counter. |
| Average current | A separate Android property whose averaging period depends on the device. It is not substituted for an unavailable instantaneous reading. |
| Voltage and temperature | Android mV and tenths Celsius; temperature can be displayed in Fahrenheit. These are battery readings, not charger output or CPU temperature. |
| Net power | Current × voltage, converted to mW. Derived from reported inputs; it is not an independent power measurement. |
| Charge and remaining energy | Android charge counter in µAh and energy counter in nWh, where supported. Unsupported sentinels and rejected values are unavailable. A supported zero remains zero. |
| Charging estimate | Android's own `computeChargeTimeRemaining()` (API 28+) whenever the sampler has one. Otherwise a time-weighted average (45-minute exponential time constant) of the observed charge rate up to 80%, then the 80→100% taper learned for the current charger; before a taper is learned, half the live rate is assumed for that stretch. The rate restarts on every new charger connection. |
| Discharge estimate | The remaining charge counter divided by a time-weighted average (45-minute exponential time constant) of the observed discharge rate. Sleep-through intervals count (their charge drop is real idle drain); observation gaps, counter resets and non-discharging intervals do not. Before enough live data accrues (10 minutes and 5 mAh), the estimate leans on the app's own 7-day typical discharge rate, calculated from closed, non-imported discharge sessions ending from the start of six local days ago through now; the shown basis says which one dominates. |

## Automatic calibration

Some devices report `CURRENT_NOW` in the wrong unit (mA instead of µA) or with the sign inverted. BatStats can detect this by comparing the integral of raw current against the charge counter's own change over matching stretches of time (a "window": at least 20 mAh of counter movement over at least 10 minutes, on one charging or discharging direction, with the CPU awake for at least 90% of it). A correction is only applied when the evidence is consistent — 3 of the last 4 windows agree on the same unit and sign, and none of the kept windows contradicts it — never from a single window and never scaled to fit; a value that fits neither unit is treated as inconclusive.

A faster, narrower check runs alongside it: 20 or more consecutive unplugged readings that are all positive (with a falling counter, when one is available) mark the sign as inverted, without deciding the unit. This fast path only fires while no window evidence says the sign is already normal.

A detected correction that changes what the app actually uses shows a notice with an **Undo**; undoing restores the previous detection and will not re-apply the same rejected result until calibration is reset. Settings can also override the unit and/or sign directly, and an override always wins over detection; these user overrides are included in Settings export, while detected calibration is stored separately and is not exported. The database itself always keeps the raw, uncorrected values, and the charge counter itself is only ever sanity-checked, never rescaled. The calibrated value is what Now, alerts, charts, the notification and the Quick Settings tile show.

## Observed periods

Live totals start when BatStats begins observing or when **Reset observation** is selected. Starting midway through an unplugged period does not claim earlier consumption. The dashboard, details and monitoring notification use the same observation model and identify the last observed endpoint; notification refresh may lag live readings by its update cadence.

Durations use monotonic clocks. Screen on means interactive; screen off includes noninteractive Always On Display. Locking the device alone does not establish screen-off or sleep. Screen-on/off drain buckets contain discharging intervals only. CPU suspend is derived from the difference between elapsed-time and uptime changes over observed intervals; Android Doze is separately observed. The two clocks are read together after battery-property reads to avoid counting collection latency as sleep. Neither screen-off time nor Doze proves CPU deep sleep.

A poll or activity refresh can arrive just before its screen/power/Doze broadcast. One real capture may wait for a matching event within two seconds; confirmation uses that first actual endpoint, not a fabricated earlier sample. Missing, conflicting or delayed events leave a gap. The wait is checked at the next capture and schedules no timer or wakeup.

Charge totals use valid counter differences. Average drain uses only intervals covered by those counters, with at least one minute required. The historical discharge ETA seed divides the closed sessions' total screen-on/off discharge charge by their total counter-covered time, requiring at least one covered hour and positive discharge charge; daily summary durations are not its denominator. Estimated interval energy uses charge change and mean endpoint voltage. Missing intervals, interrupted collection, clock discontinuities and incompatible counters are excluded; coverage stays visible. No observed screen-off period means no screen-off charge or rate. History sessions identify their own source and period; old records without continuity evidence are marked as legacy.

## Advanced access

Shizuku is used when running and authorized. Otherwise, BatStats probes root and then ADB; a failed read does not silently fall through to another backend. A persistent ADB grant is not a capability guarantee: on Android 16/API 36, cross-user refusal prevents ADB-only access to per-app batterystats, which requires Shizuku or root. Ordinary battery readings continue without advanced access.

Android batterystats has its own since-charge/reset window, distinct from BatStats history. App charge values are Android estimates. A UID can include multiple packages or system services; consumption cannot be reliably split among them. Package mappings may include removed apps or profiles. Wakelocks, jobs, alarms, network bytes and CPU activity indicate work, not proof of excessive energy use. Comparisons use one report window. The proportional attributed total is an alternative total and must not be added to the UID total.

Kernel readings require supported files and permissions, generally Root. Full-charge/design capacity ratio is a fuel-gauge estimate dependent on calibration and vendor units (see **Capacity and health** below). Cycle count alone does not determine battery health. Unsupported fields stay unavailable.

## Capacity and health

The Health card (Now) and the Health screen show a full-charge capacity estimate and, against a design capacity, a health percentage. The current production estimate is based on a charge or discharge session — the counter's charge change divided by its percentage span, projected to 100%. Although a sysfs `charge_full` estimator exists in `CapacityEstimator`, it has no production caller and does not currently contribute to the displayed estimate. A session-based estimate is produced only when counter coverage is complete across the session's observed time and the level span is at least 10 percentage points. Confidence is LOW below a 20-point span, MEDIUM from 20, and HIGH from a 40-point span. The current value combines estimates using confidence weights (LOW/MEDIUM/HIGH = 1/2/3), which reduces the influence of low-confidence outliers but does not guarantee outlier rejection: for example, two 4 Ah LOW estimates and one 6 Ah HIGH estimate combine to 6 Ah/HIGH.

Design capacity is the Settings override (mAh; 0 = automatic) when set, otherwise the fuel gauge's sysfs `charge_full_design` read through root when Health (or Status › Check again) asks, cached for the process; Now never starts the read, and an unknown result is read again after Check again. A full-charge value outside a plausible range (roughly 0.3–50 Ah, to reject unit mistakes such as mAh landing in a µAh field) is rejected outright rather than scaled. Health is unavailable without a resolved design capacity, and it may read above 100% for a battery that is still newer than its design figure.

## Monitoring cost and storage

Sampling runs at 2 seconds while something needs a live reading (the Now screen, an open session's details, or the Quick Settings tile while the shade is open), 30 seconds with the screen on otherwise, and 300 seconds with the screen off; none of this is user-configurable. Delays are scheduled on the uptime clock, so a pending poll never wakes a sleeping CPU, but broadcasts (screen on/off, Doze, a battery status change) still capture while it sleeps. A capture is written to history when it crosses a screen, Doze or gap boundary, when status/plugged or the level changes, or — for an ordinary poll — when at least 30 seconds have passed since the last saved row; everything else only updates realtime values and alerts. With monitoring off, only an active demand (e.g. the tile) polls, and those captures are realtime-only and never saved.

Per-app breakdowns are not polled: a privileged dump only happens when a screen asks for one, or automatically at the start and end of a discharge session (debounced 30 seconds after an unplug and 10 seconds after a plug-in, so a rapid plug cycle does not trigger a wasted dump). The last successful dump is cached for 60 seconds and reused across callers and screens within that window.

History is bounded to 100,000 samples and 10,000 sessions; the selected retention period also applies. Diagnostics retain up to 60 grouped local events in 32 KiB, with coalesced writes. Recent diagnostic events can be lost when the process stops. Export includes sources, units, UTC timestamps and reporting periods; sharing is deliberate.

Samsung current calibration, AOD event behavior, kernel permissions, One UI background management and real monitoring energy use remain unverified on physical hardware. Emulator values and injected test readings cannot validate those properties. See [platform contracts](PLATFORM_NOTES.md) and [actual validation](VALIDATION.md).
