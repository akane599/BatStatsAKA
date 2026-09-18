# Android16 battery monitoring: accurate observations, resilient Shizuku and clear reporting

The baseline could display unsupported readings as zero, label accumulated uptime differences as current deep sleep, attribute earlier consumption to newly observed apps, and mix unrelated reporting periods. Privileged reads could silently change backend or accept partial output. History imports and destructive migration fallback could lose or duplicate records.

This change validates nullable readings and units, uses monotonic observed intervals with explicit coverage, shares the same totals across the dashboard and rich monitoring notification, and distinguishes Android estimates from measured counter changes. Screen-off includes noninteractive AOD; CPU suspend and Doze remain separate. Restarted or interrupted observation does not manufacture earlier consumption.

Shizuku remains primary, with bounded cancellable helper reads, explicit authorization/access states and ordinary readings available during privilege loss. Root and ADB paths remain supported. Android16 batterystats parsing follows the producer's units and window; shared UID and estimated-energy limitations are visible. Unsupported per-app heuristics and cycle-derived health percentages were removed because their inputs cannot support those claims.

History now has nondestructive migrations, transactional bounded import/deduplication, predictable reset/delete behavior, pagination and session-linked charts. The UI adds readable source/freshness states, diagnostics and error recovery, accessible layouts, validated settings import and improved contrast. The useful expanded notification remains quiet with a stable identity and timestamp. Polling, retention, storage and notification work are bounded; no measured battery-saving percentage is claimed.

Build preparation includes separate debug and optimized nondebug Preview packages, signing/update guidance, and a read-only APK workflow usable from a phone and reused by push/PR CI, with test/lint reports and checksums. CI requires Android16 ordinary and Shizuku integration phases and passes no stable signing secrets. The publishing workflow now honors its publication input. Baseline locale files were English duplicates; these are consolidated into English fallback with complete Spanish and Turkish resource translations.

## Validation at this draft checkpoint

The user requested stopping testing and publishing the current source. The final notification/reset-layout and test synchronization changes are **unverified**: local validation was interrupted before completion. No passing result is claimed for these edits, and no further testing or CI monitoring is planned for this handoff. Push/PR events automatically run CI.

The last completed checkpoint is e52daf9:

- Local build passed with105 JVM cases in each Debug/Preview configuration, no failures/skips; full lint had zero errors and201/200 warnings. Debug, optimized Preview and Android test APKs assembled.29 device methods compile.
- Both hosted API36 runs built successfully:27/28 ordinary methods passed, with one navigation failure after cancelling the large-font reset dialog. Both real Shizuku integration phases passed1/1, including authorization, shellUID2000, command validation, helper restart, server loss/reconnection and ordinary-data survival.
- Lifecycle/screen-state sequencing, notification tap/stop/restart, all5 storage recovery cases, widgets and native loading on4KiB passed.16KiB phases did not run because standard validation failed; landscape checks were not reached.
- 62 nonempty screenshots were retained. Review confirmed overlapping reset actions at200% font and notification error text crowding out readings. The final edits stack reset controls and compact the notification while preserving its measurements and interval. Some captured frames were stale; a capture-idle barrier is included but unverified.
- Fourteen host runner regressions and actionlint passed. APK ZIP/native LOAD alignment passed; graphics-path RELRO-end warning remains. Resource completeness/format checks passed for496 Spanish/Turkish keys, but the final application build did not complete.

Existing collected APKs are from e52daf9 and exclude the final UI changes. Simulated test inputs and emulator results do not establish physical battery accuracy. The inherited Dependabot workflow separately fails permission validation; no repository privileges were expanded.

## Limits and delivery

No physical Samsung device is available. Current polarity/calibration, capacity accuracy, One UI background/AOD behavior, vendor kernel paths and real energy overhead remain unverified. Android estimates are not exact measurements. Details: [MEASUREMENTS.md](https://github.com/akane599/BatStatsAKA/blob/codex/android16-reliability/docs/MEASUREMENTS.md), [VALIDATION.md](https://github.com/akane599/BatStatsAKA/blob/codex/android16-reliability/docs/VALIDATION.md), [AUDIT_REPORT.md](https://github.com/akane599/BatStatsAKA/blob/codex/android16-reliability/AUDIT_REPORT.md).

Debug (`org.mlm.batstats.debug`) and Preview (`org.mlm.batstats.preview`) install alongside the supplied package. The original release signing key is unavailable, so these cannot update that package in place. Preview updates need a stable matching certificate; ephemeral GitHub runner keys may differ between runs. See [BUILD_AND_INSTALL.md](https://github.com/akane599/BatStatsAKA/blob/codex/android16-reliability/docs/BUILD_AND_INSTALL.md).

This PR remains a draft with the unresolved validation items above; the user requested ending testing and publishing the work as it stands. Push/PR CI performs the same build and device checks as the phone workflow. The manual workflow must reach the default branch before its Run workflow control appears.
