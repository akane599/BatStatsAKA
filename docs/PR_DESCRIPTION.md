# Android16 battery monitoring: accurate observations, resilient Shizuku and clear reporting

The baseline could display unsupported readings as zero, label accumulated uptime differences as current deep sleep, attribute earlier consumption to newly observed apps, and mix unrelated reporting periods. Privileged reads could silently change backend or accept partial output. History imports and destructive migration fallback could lose or duplicate records.

This change validates nullable readings and units, uses monotonic observed intervals with explicit coverage, shares the same totals across the dashboard and rich monitoring notification, and distinguishes Android estimates from measured counter changes. Screen-off includes noninteractive AOD; CPU suspend and Doze remain separate. Restarted or interrupted observation does not manufacture earlier consumption.

Shizuku remains primary, with bounded cancellable helper reads, explicit authorization/access states and ordinary readings available during privilege loss. Root and ADB paths remain supported. Android16 batterystats parsing follows the producer's units and window; shared UID and estimated-energy limitations are visible. Unsupported per-app heuristics and cycle-derived health percentages were removed because their inputs cannot support those claims.

History now has nondestructive migrations, transactional bounded import/deduplication, predictable reset/delete behavior, pagination and session-linked charts. The UI adds readable source/freshness states, diagnostics and error recovery, accessible layouts, validated settings import and improved contrast. The useful expanded notification remains quiet with a stable identity and timestamp. Polling, retention, storage and notification work are bounded; no measured battery-saving percentage is claimed.

Build preparation includes separate debug and optimized nondebug Preview packages, signing/update guidance, and a read-only APK workflow usable from a phone and reused by push/PR CI, with test/lint reports and checksums. CI requires Android16 ordinary and Shizuku integration phases and passes no stable signing secrets. The publishing workflow now honors its publication input. Baseline locale files were English duplicates; these are consolidated into English fallback with complete Spanish and Turkish resource translations.

## Validation at this draft checkpoint

- 94 JVM regression cases pass in each Debug and Preview configuration, with no failures/skips; all25 Android test methods compile. These are synthetic/host checks, not physical measurements.
- Six synthetic instrumentation-result parser regressions pass; prebuilt ADB execution rejects empty, failed, skipped, crashed or incomplete runs.
- Host SQLite checks pass migrations, identity/deduplication, history paging and bounded chart queries.
- Fresh Debug, nondebuggable/minified Preview and Android test APKs assembled from the current source; universal signatures and checksums verified (source commit dd7f2c0).
- APK ZIP/native LOAD alignment passes for16KiB; graphics-path RELRO-end warning remains a runtime-validation limitation (see VALIDATION).
- Resource completeness/format checks and direct AAPT2 compilation pass for493 Spanish and493 Turkish strings. Workflow actionlint passes.
- Full Debug/Preview lint passes with zero errors and201/200 reviewed warnings. Actual Android16 UI/screenshots and Shizuku execution remain pending. This remains a draft while those device checks are pending. Local software emulation did not complete boot during approximately68minutes; both baseline installation attempts were rejected. The only returned screenshot was a blank background, and no app was installed. The task emulator has been stopped. No app compatibility conclusion is drawn from that host limitation.

## Limits and delivery

No physical Samsung device is available. Current polarity/calibration, capacity accuracy, One UI background/AOD behavior, vendor kernel paths and real energy overhead remain unverified. Android estimates are not exact measurements. Details: [MEASUREMENTS.md](https://github.com/akane599/BatStatsAKA/blob/codex/android16-reliability/docs/MEASUREMENTS.md), [VALIDATION.md](https://github.com/akane599/BatStatsAKA/blob/codex/android16-reliability/docs/VALIDATION.md), [AUDIT_REPORT.md](https://github.com/akane599/BatStatsAKA/blob/codex/android16-reliability/AUDIT_REPORT.md).

Debug (`org.mlm.batstats.debug`) and Preview (`org.mlm.batstats.preview`) install alongside the supplied package. The original release signing key is unavailable, so these cannot update that package in place. Preview updates need a stable matching certificate; ephemeral GitHub runner keys may differ between runs. See [BUILD_AND_INSTALL.md](https://github.com/akane599/BatStatsAKA/blob/codex/android16-reliability/docs/BUILD_AND_INSTALL.md).

This PR remains a draft until Android runtime checks and screenshot review are complete. Push/PR CI performs the same build and device checks as the phone workflow. The manual workflow must reach the default branch before its Run workflow control appears.
