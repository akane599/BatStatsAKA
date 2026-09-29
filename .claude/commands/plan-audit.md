---
description: Fresh-context audit of an implementation plan (from plan mode or a file) before approving it — Android-specific failure modes, scope, verification, agent collisions
argument-hint: [plan file path | blank = newest plan in ~/.claude/plans or the plan in this conversation]
allowed-tools: Bash(ls:*), Bash(rg:*), Bash(git:*), Bash(./gradlew:*), Read, Glob, Grep, Agent
---

Audit the plan before it is executed. Target: $ARGUMENTS
Resolve the plan: the given path → else the newest `~/.claude/plans/*.md` → else the plan text most recently presented in this conversation (write it to `design/plan-under-audit.md` first so the auditor can read it).

The point is an *independent* read. Dispatch **one general-purpose agent that has not seen this conversation**, give it only: the plan file path, `CLAUDE.md`, `PROGRESS.md`, and the checklist below. It may read the codebase to verify claims; it must not edit anything. Ask it to return the report format at the bottom. Do not audit in the main context — you wrote the plan; you'll defend it.

## Checklist for the auditor

**A. Grounded in reality (verify against the repo, cite `path:line`)**
1. Every file the plan says it will modify exists — or the plan says it's new. Every function/class it references exists with that name.
2. The plan's assumptions about architecture match `CLAUDE.md`'s STACK block (DI framework, DB, navigation, UI toolkit, min/compile SDK). Flag any step that assumes Compose in an XML app, Hilt in a Koin app, etc.
3. Scope: list anything in the plan that isn't required by the stated goal (scope creep), and anything the goal requires that no step delivers (gap).

**B. Android-specific omissions (mark each: covered / not needed / MISSING)**
- Manifest changes: permissions (+ runtime request flow for dangerous ones), new Activities/Services/Receivers, `exported` flags, deep links.
- API-level gating: any API above `minSdk` guarded (`Build.VERSION.SDK_INT`) or backported.
- Persistence: Room/SQLDelight schema change → migration + migration test; DataStore/SharedPrefs key changes → read-old-write-new.
- Config changes / process death: state survives rotation and restore (`SavedStateHandle`, `rememberSaveable`).
- Threading: no blocking IO on main; coroutine scope is lifecycle-bound; Flow collection uses `repeatOnLifecycle`/`collectAsStateWithLifecycle`.
- Strings/resources: user-visible text goes to `strings.xml`; new drawables have density or vector variants; RTL and large-font tolerance.
- Compose (if applicable): new state classes immutable (`compose-stability`), new screens get previews (light/dark/fontScale), tokens from theme only (`compose-design`).
- Release impact: ProGuard/R8 keep rules for reflection/serialization; `google-services`/Firebase config; version catalog updated rather than inline versions.
- Backward compatibility: existing users upgrading — data, cached files, notification channels, widget/shortcut IDs.
- Security: nothing new writes secrets to logs, prefs, or source; network calls stay on HTTPS; new `exported` components validated.

**C. Executability**
1. Each task has a **verification step with a command** (`./gradlew … testDebugUnitTest`, a screenshot, an adb check). A task ending in "verify it works" with no command is a MISSING.
2. Tasks meant to run in parallel agents don't touch the same files. List collisions.
3. Order: no task depends on a later one. Theme/tokens before screens; schema before DAO before repo before UI.
4. Rollback: can the change be reverted with `git revert` alone, or does it leave migrated data / renamed keys behind? Say which.
5. Size: any single task that touches >8 files or >3 modules should be split. Name them.
6. Token cost: steps that say "read the whole module" or "review all screens" — suggest the grep/Explore-agent substitute.

**D. Contrarian pass**
Pick the 3 decisions in the plan most likely to be wrong and argue the alternative in 2 lines each. The main context decides; the auditor only argues.

## Report format (returned by the agent, relayed verbatim, then a 3-line verdict from main context)
```
PLAN AUDIT — <plan name>            Verdict: APPROVE / APPROVE WITH FIXES / REWORK
Blockers (must fix before execution):
  - <item> — <path:line or reason>
Fixes (add to plan):
  - <item>
Not needed (auditor checked, fine as is):
  - <B-items marked not needed, one line>
Collisions: <none | task X & Y both edit path>
Contrarian: 1) … 2) … 3) …
```
After relaying: apply "Blockers" and "Fixes" to the plan file directly (they're plan edits, not code edits), note `audited: <date>` at the top of the plan, and log the audit in `PROGRESS.md` under the decision log. Then tell the user it's ready to approve — or not.
