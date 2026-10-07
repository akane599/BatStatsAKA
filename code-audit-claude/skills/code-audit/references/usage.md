# Using code-audit in Claude Code

## Install or upgrade

Requires Node.js 18+, Git for commit comparisons, and a current Claude Code release
supporting reviewer effort. Extract the complete bundle and run:

```sh
node install.mjs --upgrade
```

This targets CLAUDE_CONFIG_DIR if set, otherwise ~/.claude. For a project installation
use the same project as before:

```sh
node install.mjs --project /absolute/path/to/repository --upgrade
```

Or choose another configuration directory explicitly:

```sh
node install.mjs --config-dir /absolute/path/to/claude-config --upgrade
```

Fully exit and restart Claude Code after upgrading. Version 3 installs eight
specialists with six fixed effort profiles each (48 agent definitions). The inherited
profiles retain the familiar names, such as code-audit-bugs. Explicit effort selects
code-audit-bugs-low, -medium, -high, -xhigh, or -max. Registration happens at session
startup; review-time helper calls never create, edit, or delete these definitions.

The installer recognizes the original bundle by hashes and later installations by
an ownership manifest. Recognized, unmodified older files are backed up under
code-audit-backups before replacement. Customized/unknown files cause an error
before changes; compare them and move them aside yourself to use the packaged copy.
Settings, permission rules, and unrelated agents remain untouched. Stop current
reviews before upgrading. Keep install.mjs and legacy-v1-hashes.json with the bundle.

Install inside the environment/account where Claude Code runs (Ubuntu/proot,
container, SSH host, or native shell). Copying only SKILL.md is insufficient; retain
its scripts/references and install the companion agents.

## Native slash command

Run Claude Code inside your repository after installation/restart:

```text
/code-audit
/code-audit review --base main -all opus --effort high
/code-audit review --pr 123 -all fable --effort high
/code-audit audit src/ --model sonnet --effort medium --agent-model bugs=opus --agent-effort bugs=high
/code-audit bug-hunt src/payments --only bugs,errors,security -all opus --effort high
/code-audit debug src/auth --symptom "Login fails after refreshing an expired token" --only bugs,errors,tests -all opus --effort high
/code-audit --help
```

Default: all eight specialists, inherited session model/effort, parallel limit 4.
Roles: comments, tests, errors, types, quality, simplify, bugs, security. --only needs
at least two distinct roles. --parallel accepts 1–8; 1 still uses separate agents.

-all MODEL (also --all MODEL) overrides every selected reviewer's model, including
validation calls, regardless of option order. --model is a default that individual
--agent-model ROLE=MODEL selections can override. --effort sets default effort;
--agent-effort ROLE=LEVEL can override it for one reviewer. -all does not expand
--only or override effort. The coordinator keeps its session settings.

Models are aliases or full identifiers accepted by your Claude/provider. The helper
preserves model identifiers verbatim. Effort: inherit, low, medium, high, xhigh, max.
With native invocation the helper selects an existing effort profile and supplies
an explicit per-invocation model override. If your exposed Agent tool schema cannot
accept the requested model identifier, use the startup launcher below. Do not turn
a rejected gateway model into a different model without the user's instruction.
Provider policy can still substitute models or cap effort; report actual metadata
or /tasks when available, and say when effective settings cannot be confirmed.

Positional arguments are paths; quote spaces. Use --symptom for debug context,
including multiline traces. Use -- before paths beginning with a hyphen. --base
and --pr cannot be combined. Paths alongside either filter the changed-file scope.
Unknown/duplicate options, unknown roles, and overrides for unselected roles fail.
Debug performs diagnosis; request fixes explicitly to authorize implementation.
Reports return in chat; PR comments are published only when requested.

## Startup launcher for gateway models

From the reviewed repository, run the installed launcher instead of starting Claude
normally. This registers the selected model/effort for each reviewer through --agents
JSON before the session begins:

```sh
node ~/.claude/skills/code-audit/scripts/launch.mjs review --base main -all MODEL_ID --effort high
```

Replace MODEL_ID with the identifier your gateway accepts, for example Astra if that
is its actual model identifier. Display names alone do not establish an API ID.
For a project installation, use .claude/skills/code-audit/scripts/launch.mjs. For a
custom configuration directory, both installation and launching must use that scope:

```sh
CLAUDE_CONFIG_DIR=/absolute/path/to/claude-config node /absolute/path/to/claude-config/skills/code-audit/scripts/launch.mjs review -all MODEL_ID --effort high
```

Invoking a script in that directory alone does not select Claude's configuration. The same native options,
including individual models and effort, are supported:

```sh
node ~/.claude/skills/code-audit/scripts/launch.mjs audit src/ --model sonnet --effort medium --agent-model bugs=MODEL_ID --agent-effort bugs=high
```

The launcher starts an interactive Claude session with /code-audit as its initial
request. It inherits existing gateway/auth environment and permissions. It does not
change settings, skip permissions, spawn a nested process inside a running review,
or write reviewer definitions to disk. CLI startup definitions are session-local,
so simultaneous sessions can use different models without shared profile edits.
The skill must be installed in a scope Claude loads. Run the launcher from your
terminal, after exiting any review using the old implementation.

Matching startup profiles are invoked by name without a per-call model override.
Further slash requests may use supported per-call overrides; an inherited request
cannot silently select a previously fixed startup model. Start a new launcher
session when you want new startup settings. The provider must support the chosen
identifier and Claude Code must support the model/effort fields; startup registration
does not bypass those checks.

Preview startup registrations without launching Claude or printing auth/environment:

```sh
node ~/.claude/skills/code-audit/scripts/launch.mjs --dry-run review -all MODEL_ID --effort high
```

## Agent-not-found troubleshooting

An error such as code-audit-3260ef7c30022200-bugs not found identifies the v1/v2
review-time registration bug. It happens before model inference, so changing models
does not fix it. Version 3 never emits such names.

1. Upgrade the bundle in the same personal/project config scope.
2. Fully restart Claude Code.
3. Rerun /code-audit with your original options. An explicit high effort now uses
   code-audit-bugs-high; inherited effort uses code-audit-bugs.

If a fixed name is still missing, inspect the session's available agent list and
installation scope. File existence is not runtime registration. Use the startup
launcher to register the chosen definitions at launch when needed. Do not repeatedly
retry missing random types, wait for live reload, or pretend a solo review is multiagent.

## Permission and evidence checks

Reviewers have Read, Glob, Grep, Bash and inherit the parent permission mode. Bash
supports Git reads and assigned tests/type checks/lint, and can create ordinary test
artifacts. This is a diagnostic workflow, not a filesystem read-only sandbox.
The coordinator serializes commands sharing caches, coverage outputs, databases,
or ports. Every reviewer checks source/tool access and reports denials as blocked
evidence. The helper needs only read access to installed profiles. No reviewer
profile writes or cleanup are needed during a version 3 review.

Session/organization policy and sandbox limits still apply. Normal Claude permission
prompts may appear, including background-reviewer prompts on current releases.
The skill does not switch permission modes or modify permission rules. A helper's
successful filesystem read does not prove Claude's runtime authorization or discovery.
CLAUDE_CODE_SUBAGENT_MODEL_FORCE and CLAUDE_CODE_EFFORT_LEVEL can override requests;
settings are reported as requested until runtime metadata confirms them.

Committed/PR reviews pin immutable revisions:

```sh
node skills/code-audit/scripts/scope.mjs --repo /path/to/repo --base COMMIT
node skills/code-audit/scripts/scope.mjs --repo /path/to/repo --base BASE_SHA --head HEAD_SHA
```

The helper returns the merge base, reviewed head, dirty state, and changed files.
Read all after-side code, callers, imports, and configuration from objects at that
head, or from a clean checkout of it. Tests require that checkout. The helper does
not fetch, check out, install dependencies, or modify the user's tree. --base still
means merge-base..HEAD, excluding uncommitted edits. Missing objects/context and
ambiguous merge bases are errors rather than guessed evidence.

## Local validation and legacy cleanup

From the extracted bundle:

```sh
node skills/code-audit/scripts/agents.mjs plan --model sonnet --agent-model bugs=opus --effort high
node skills/code-audit/scripts/agents.mjs validate
node --test tests/*.test.mjs
```

The coordinator writes request data safely, for example:

```json
{"args":["review","--base","main","-all","opus","--effort","high"]}
```

Only abandoned v1/v2 runs have temporary agent files. After those reviewers stop,
remove that old run's definitions with its recorded 16-character ID:

```sh
node ~/.claude/skills/code-audit/scripts/agents.mjs cleanup --run 3260ef7c30022200
```

This legacy cleanup leaves permanent reviewers and other runs untouched. Version 3
reviews produce no run ID or temporary agent files and never call this cleanup.

## Official compatibility references

Checked against official documentation on 2026-10-07:

- [Custom subagents](https://code.claude.com/docs/en/sub-agents): startup --agents JSON, effort profiles, model overrides, tool/permission behavior.
- [CLI reference](https://code.claude.com/docs/en/cli-reference): --agents startup registration.
- [Skills](https://code.claude.com/docs/en/skills): invocation arguments and bundled resources.
- [Model configuration](https://code.claude.com/docs/en/model-config): provider support, overrides, effort caps.
