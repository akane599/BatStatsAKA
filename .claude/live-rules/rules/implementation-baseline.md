---
description: Implementation baseline for BatStats changes
priority: 95
---
- Trace the flow first (`.claude/.codebase-info/` names the files). Decide whether the change needs to exist, then extend an existing seam (`HistoryPolicy`, `ShellRunner`, a `Default<X>Repository`, `battery/measurement/`) and catalog dependencies before adding new ones.
- Smallest fix at the shared root. Prefer deletion and direct calls over new wrappers, flags, guards or layers.
- Keep the safety floors: import validation, history caps, Room migrations, the `ShellUserService.COMMANDS` allow-list, fixed-code diagnostics, accessibility and permissions.
- Mark a deliberate simplification `whittle:` with its limit and upgrade trigger.
- Non-trivial logic leaves a JVM test with fakes; never claim a fake ran on a device.
