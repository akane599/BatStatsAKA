# Claude Code setup on another machine

The project's Claude Code workspace is committed: `.claude/` (kit scripts, live rules, skills, commands,
codebase map, shared `settings.json`), `CLAUDE.md` and `PROGRESS.md`. What stays per machine, and is
never committed, is `.claude/settings.local.json` (plugin enablement, kit hooks, model/gateway env),
the plugin cache, the local plugin marketplace and the Sidequest board data. The two installers below
recreate those.

## Requirements
Ubuntu (or another Linux) with Git, Bash, `flock`, Python 3.9+, Node.js 18+, the Claude Code CLI,
JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`) and an Android SDK in `ANDROID_HOME`. Don't create
`local.properties`; the build uses `ANDROID_HOME`.

## Steps
From the repository root:

```bash
bash android-kit/setup.sh --dry-run .   # shows what it will do, writes nothing
bash android-kit/setup.sh .             # plugins (local scope), local marketplace, kotlin-lsp, routing profile, hooks
node code-audit-claude/install.mjs --upgrade   # /code-audit reviewer agents into ~/.claude
claude                                  # then /reload-plugins or restart; run /kit-doctor to check
```

- `setup.sh` keeps the committed project files: when a tracked file differs from the kit template,
  the template goes to `.claude/kit/incoming/` instead of overwriting it. Don't run `/bootstrap` again;
  the project is already bootstrapped. Use `/kit-doctor` for a health check.
- GPT routes need Model Gateway signed in to your ChatGPT/Codex account on that machine (the
  `model-gateway` plugin's setup skill). Without it, Sidequest still routes to Claude models.
- The Sidequest board (tickets, stories) lives in `~/.claude/sidequest` on each machine and doesn't
  travel; the decisions it produced are in `PROGRESS.md`.
