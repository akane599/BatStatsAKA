#!/usr/bin/env bash
# One-shot Claude Code plugin setup for Android dev on Ubuntu.
# Run from any terminal (not inside Claude). Re-runnable.
set -euo pipefail

echo "==> Adding marketplaces"
claude plugin marketplace add anthropics/claude-plugins-official  || true   # usually pre-registered
claude plugin marketplace add anthropics/claude-plugins-community || true

install() { echo "  -> $1"; claude plugin install "$1" || echo "     (skipped: $1)"; }

echo "==> Tier 1: core workflow (official)"
install feature-dev@claude-plugins-official          # /feature-dev  – explore -> architect -> implement -> review, all via subagents
install pr-review-toolkit@claude-plugins-official    # /review-pr    – 6 review agents on the local branch (no GitHub needed)
install code-review@claude-plugins-official          # /code-review  – multi-agent PR review w/ confidence filter (needs `gh`)
install code-simplifier@claude-plugins-official      # agent: cleans recently touched code
install claude-md-management@claude-plugins-official # /revise-claude-md – folds session learnings into CLAUDE.md
install claude-code-setup@claude-plugins-official    # scans repo, recommends hooks/agents specific to it
install session-report@claude-plugins-official       # /session-report – where the tokens went
install context7@claude-plugins-official             # live docs lookup (AndroidX, Compose, Retrofit, Room...)

echo "==> Tier 1: security / audits (official)"
install claude-security@claude-plugins-official      # deep, verified vuln scan + patch proposals
install security-guidance@claude-plugins-official    # always-on hooks: warns on risky edits, reviews diff on stop

echo "==> Tier 1: memory (official marketplace, community-authored)"
install remember@claude-plugins-official             # auto session memory, compressed daily logs, loaded on start

echo "==> Tier 1: Android-specific (community)"
install android-emulator-qa-plugin@claude-community  # adb-driven emulator QA: launch, UI tree, tap, screenshot, logcat

echo "==> LSP (both are safe to install; each only activates for its own file types)"
install kotlin-lsp@claude-plugins-official          # .kt/.kts code intelligence via JetBrains kotlin-lsp; needs `kotlin-lsp` on PATH (github.com/Kotlin/kotlin-lsp releases)
install jdtls-lsp@claude-plugins-official            # .java code intelligence; needs `jdtls` on PATH -> bash scripts/install-jdtls.sh

echo "==> Tier 2: optional"
install superpowers@claude-plugins-official          # subagent-driven-development, TDD, systematic-debugging, brainstorming
install security-sweep@claude-community              # secrets/injection scan incl. mobile-security checks
install designwithclaude@claude-community            # 29 design-specialist commands (hierarchy, color, motion, dark mode, mobile)
install hookify@claude-plugins-official              # write your own guardrail hooks from plain-English rules

cat <<'EOF'

==> Done. Per project:
   1. Copy CLAUDE.md, PROGRESS.md, .claude/ and scripts/ into the repo root.
   2. Open Claude Code there and run:
        /bootstrap                # detects stack, fills every placeholder, verifies the build
        # if Kotlin: put JetBrains kotlin-lsp on PATH (github.com/Kotlin/kotlin-lsp releases)
        bash scripts/install-jdtls.sh   # if Java (run in a normal terminal)
        /claude-code-setup        # optional: repo-specific hooks/agents
   3. Daily:  /feature-dev "<feature>"  ->  /review-pr  ->  /revise-claude-md
EOF
