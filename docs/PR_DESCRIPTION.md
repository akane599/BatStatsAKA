# Android16 battery monitoring: accurate observations, resilient Shizuku and clear reporting

The baseline could display unsupported readings as zero, label accumulated uptime differences as current deep sleep, attribute earlier consumption to newly observed apps, and mix unrelated reporting periods. Privileged reads could silently change backend or accept partial output. History imports and destructive migration fallback could lose or duplicate records.

This change validates nullable readings and units, uses monotonic observed intervals with explicit coverage, shares the same totals across the dashboard and rich monitoring notification, and distinguishes Android estimates from measured counter changes. Screen-off includes noninteractive AOD; CPU suspend and Doze remain separate. Restarted or interrupted observation does not manufacture earlier consumption.

Shizuku remains primary, with bounded cancellable helper reads, explicit authorization/access states and ordinary readings available during privilege loss. Root and ADB paths remain supported. Android16 batterystats parsing follows the producer's units and window; shared UID and estimated-energy limitations are visible. Unsupported per-app heuristics and cycle-derived health percentages were removed because their inputs cannot support those claims.

History now has nondestructive migrations, transactional bounded import/deduplication, predictable reset/delete behavior, pagination and session-linked charts. The UI adds readable source/freshness states, diagnostics and error recovery, accessible layouts, validated settings import and improved contrast. The useful expanded notification remains quiet with a stable identity and timestamp. Polling, retention, storage and notification work are bounded; no measured battery-saving percentage is claimed.

Build preparation includes separate debug and optimized nondebug Preview packages, signing/update guidance, and a read-only manually started APK workflow with test/lint reports and checksums. The publishing workflow now honors its publication input. Baseline locale files were English duplicates; these are consolidated into English fallback with complete Spanish and Turkish resource translations.

## Validation at this draft checkpoint

- 94 JVM regression cases pass in each Debug and Preview configuration; Android test sources compile. These are synthetic/host checks, not physical measurements.
- Host SQLite checks pass migrations, identity/deduplication, history paging and bounded chart queries.
- Debug, nondebuggable/minified Preview and Android test APKs assembled; signatures checked. Newer resource/recovery-screen changes still need final assemblies.
- Resource completeness/format checks and direct AAPT2 compilation pass for493 Spanish and493 Turkish strings. Workflow actionlint passes.
- Full lint rerun, actual Android16 UI/screenshots and Shizuku execution remain pending. Do not publish this description as final until VALIDATION.md and this section contain the completed results.

## Limits and delivery

No physical Samsung device is available. Current polarity/calibration, capacity accuracy, One UI background/AOD behavior, vendor kernel paths and real energy overhead remain unverified. Android estimates are not exact measurements. Details: [MEASUREMENTS.md](MEASUREMENTS.md), [VALIDATION.md](VALIDATION.md), [AUDIT_REPORT.md](../AUDIT_REPORT.md).

Debug (`org.mlm.batstats.debug`) and Preview (`org.mlm.batstats.preview`) install alongside the supplied package. The original release signing key is unavailable, so these cannot update that package in place. Preview updates need a stable matching certificate; ephemeral GitHub runner keys may differ between runs. See [BUILD_AND_INSTALL.md](BUILD_AND_INSTALL.md).

Prepared locally only. No push, pull request, release or workflow run has been authorized or performed. Publishing requires explicit approval, including acknowledgment that existing push/PR CI runs automatically and the manual workflow must reach the default branch before its Run workflow control appears.
