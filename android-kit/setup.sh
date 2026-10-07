#!/usr/bin/env bash
# android-kit: first-thing Claude Code setup for an Android project, built on the Eigenwise toolshed.
#
#   Empty folder, fresh fork/clone, or an existing project → from the project root:
#     bash android-kit/setup.sh          (kit folder dragged into the project)
#     android-kit                        (after the first run)
#
#   Base: sidequest (orchestration), model-gateway (GPT routes), live-rules, codebase-mapper,
#   quartermaster. Android layer on top. Everything installs at LOCAL scope (.claude/settings.local.json).
#   Re-runnable: kit files refresh; CLAUDE.md, PROGRESS.md and your settings are never overwritten.
set -euo pipefail

KIT_VERSION="2026.10.03.3"
SELF="$(readlink -f "${BASH_SOURCE[0]}")"
KIT_SRC="$(dirname "$SELF")"
KIT_HOME="${ANDROID_KIT_HOME:-$HOME/.local/share/android-kit}"
MP_DIR="${CLAUDE_LOCAL_MARKETPLACE:-$HOME/.claude/local-marketplace}"
MP_NAME="android-local"
TOOLSHED="eigenwise-toolshed"
SCOPE="local"
CLAUDE_BIN="${ANDROID_KIT_CLAUDE_BIN:-$(type -P claude || true)}"
ORIG_PATH="$PATH"; export ORIG_PATH                       # what new terminals see, before this run adds ~/.local/bin
export PATH="$HOME/.local/bin:$PATH"

EXTRAS=0; WITH=""; NO_PLUGINS=0; NO_GATEWAY=0; MACHINE_ONLY=0; MIGRATE=""; YES=0; TARGET=""; RESET_ROUTING=0; NO_LSP=0; LSP_REINSTALL=0; DRY_RUN=0; LOCAL_ONLY=0
usage() { sed -n '2,11p' "$SELF" | sed 's/^# \{0,1\}//'; cat <<'EOF'

Options:
  [DIR]            project root (default: current directory)
  --dry-run        show actions without writing or calling Claude/network
  --local-only     exclude generated kit files locally; do not change .gitignore
  --extras         also enable opt-in plugins (plugins.conf, tier "extra")
  --with a,b       enable specific opt-in plugins by name
  --no-gateway     disable project gateway wiring; review inherited shell/user wiring
  --no-plugins     copy kit files only
  --machine        only (re)do the one-time machine setup, e.g. to refresh plugin clones
  --migrate        switch kit plugins off at user scope without asking
  --no-migrate     leave user-scope plugins alone
  --reset-routing  restore kit-defined category mappings in the shared android-kit profile; board overrides stay
  --no-lsp-install don't offer to install kotlin-lsp / jdtls when they're missing
  --reinstall-lsp  download the latest kotlin-lsp (and jdtls, for Java projects) again, replacing the installed ones
  -y, --yes        don't ask questions (ChatGPT sign-in is then left for later)
EOF
}
while [ $# -gt 0 ]; do
  case "$1" in
    --extras) EXTRAS=1 ;; --with) [ $# -ge 2 ] && [[ "$2" != -* ]] || { echo "--with needs comma-separated names" >&2; exit 2; }; WITH="$2"; shift ;; --no-plugins) NO_PLUGINS=1 ;; --no-gateway) NO_GATEWAY=1 ;;
    --dry-run) DRY_RUN=1 ;; --local-only) LOCAL_ONLY=1 ;; --machine) MACHINE_ONLY=1 ;; --migrate) MIGRATE=yes ;; --no-migrate) MIGRATE=no ;; --reset-routing) RESET_ROUTING=1 ;; --no-lsp-install) NO_LSP=1 ;; --reinstall-lsp) LSP_REINSTALL=1 ;;
    -y|--yes) YES=1 ;; -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1"; usage; exit 1 ;; *) [ -z "$TARGET" ] || { echo "only one target directory is accepted" >&2; exit 2; }; TARGET="$1" ;;
  esac; shift
done

b() { printf '\033[1m%s\033[0m\n' "$*"; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
# Capture the caller's executable before adding ~/.local/bin; do not accidentally select a stale launcher.
claude() { "$CLAUDE_BIN" "$@"; }
cli_report() {
  local label="$1" output rc=0; shift
  output=$(claude "$@" 2>&1) || rc=$?
  if [ "$rc" -ne 0 ]; then
    warn "$label failed (exit $rc): $CLAUDE_BIN $*"
    if [ -n "$output" ]; then printf '%s\n' "$output" | tail -60; else warn "CLI returned no diagnostic output"; fi
  fi
  return "$rc"
}
report() { local k m; while read -r k m; do case "$k" in ok) ok "$m";; warn) warn "$m";; info) echo "    $m";; *) [ -n "$k" ] && warn "$k $m";; esac; done; }
ask() { [ "$YES" = 1 ] && return 0; [ -t 0 ] || return 1; read -r -p "  $1 [Y/n] " r; [[ -z "$r" || "$r" =~ ^[Yy] ]]; }
kit() { echo "$KIT_SRC/$1"; }

# plugins.conf → "id tier source sha" (comments stripped)
conf_lines() { sed -e 's/#.*//' "$(kit plugins.conf)" | awk 'NF>=2 {print $1, $2, ($3?$3:"-"), ($4?$4:"-")}'; }
ids_of() { conf_lines | awk -v t="$1" '$2==t {print $1}'; }
install_path() {   # installPath of an installed plugin (any scope)
  claude plugin list --json 2>/dev/null | python3 -c "
import json,sys
for p in json.load(sys.stdin):
    if p.get('id')==sys.argv[1] and p.get('installPath'): print(p['installPath']); break" "$1" 2>/dev/null || true
}

# ─────────────────────────────────────────────────────────── preflight
preflight() {
  b "Checking tools"
  local required="git python3"
  [ "$NO_PLUGINS" = 1 ] || required="$required node"
  required="$required flock"
  for t in $required; do command -v "$t" >/dev/null || die "$t not found on PATH"; done
  if [ "$NO_PLUGINS" = 0 ]; then
    node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 18 ? 0 : 1)' || die "Node.js 18+ required"
    [ -n "$CLAUDE_BIN" ] && [ -f "$CLAUDE_BIN" ] && [ -x "$CLAUDE_BIN" ] || die "Claude executable not found; set ANDROID_KIT_CLAUDE_BIN to an executable path if using a custom wrapper"
    python3 "$(kit lib/check-claude.py)" "$CLAUDE_BIN" "$(kit lib/kotlin-lsp-android)" || die "Claude CLI preflight failed; machine/plugin/project installation has not started"
    ok "Claude CLI verified · node $(node --version)"
    if [ "$(node -p 'process.platform')" = android ]; then
      warn "Native Android/Termux detected: automatic desktop Linux LSP downloads are disabled"
      warn "Use compatible preinstalled language servers; this does not establish Android build/gateway compatibility"
      NO_LSP=1
    fi
  fi
  local jv; jv=$(java -version 2>&1 | grep -m1 -oE 'version "[0-9]+' | grep -oE '[0-9]+$' || true)
  if [ -n "$jv" ] && [ "$jv" -ge 17 ]; then ok "JDK $jv"; else warn "JDK 17+ not found (java -version); Gradle/AGP need it"; fi
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms" ]; then ok "ANDROID_HOME=$ANDROID_HOME"
  else warn "ANDROID_HOME is not exported. Sidequest executors build in worktrees without local.properties,"
       warn "  so they find the SDK only through ANDROID_HOME. Add to ~/.profile: export ANDROID_HOME=\$HOME/Android/Sdk"; fi
  command -v adb >/dev/null && ok "adb" || warn "adb not on PATH (add \$ANDROID_HOME/platform-tools); device QA won't work"
}

lsp_ensure() {   # <kotlin|java> <what> <command>: report it when present, otherwise offer to install it into ~/.local
  local flag=""; [ "$LSP_REINSTALL" = 1 ] && flag=--reinstall
  if [ -z "$flag" ] && { command -v "$3" >/dev/null || { [ "$1" = kotlin ] && [ -x "$HOME/.local/bin/kotlin-lsp.sh" ]; }; }; then
    bash "$(kit lib/install-lsp.sh)" "$1" 2>&1 | report || true; return 0
  fi
  if [ -n "$flag" ] || ask "$3 isn't installed. Download $2 into ~/.local and put it on PATH?"; then
    bash "$(kit lib/install-lsp.sh)" "$1" $flag 2>&1 | report || true
  else warn "$3 not installed: its LSP plugin stays idle until $3 is on PATH (the next android-kit run offers it again)"; fi
}

# ─────────────────────────────────────────────────────────── once per machine
machine_setup() {
  b "Machine setup (once per machine; kit $KIT_VERSION)"
  command -v flock >/dev/null || die "flock required (Ubuntu util-linux)"
  mkdir -p "$(dirname "$KIT_HOME")"
  exec 9>"$KIT_HOME.lock"
  flock -n 9 || die "another android-kit machine setup is running"
  if [ -e "$KIT_HOME" ] && [ "$KIT_SRC" != "$KIT_HOME" ] && [ ! -f "$KIT_HOME/setup.sh" ]; then
    die "KIT_HOME already exists and is not an android-kit installation"
  fi
  if [ "$KIT_SRC" != "$KIT_HOME" ]; then
    local staged; staged=$(mktemp -d "$KIT_HOME.stage.XXXXXXXX")
    cp -a "$KIT_SRC/." "$staged/"
    if [ -e "$KIT_HOME" ]; then mv "$KIT_HOME" "$KIT_HOME.previous.$(date +%s%N)"; fi
    mv "$staged" "$KIT_HOME"
  fi
  mkdir -p "$HOME/.local/bin"; ln -sf "$KIT_HOME/setup.sh" "$HOME/.local/bin/android-kit"; chmod +x "$KIT_HOME/setup.sh"
  ok "kit at $KIT_HOME; command: android-kit"
  local oldcfg="${XDG_CONFIG_HOME:-$HOME/.config}/android-kit"   # global GPT defaults from an earlier kit; setup is per project now
  if [ -d "$oldcfg" ]; then
    local bak="$HOME/.local/share/android-kit-retired/config-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$(dirname "$bak")"; mv "$oldcfg" "$bak"; ok "old global GPT config moved to $bak"
  fi

  cli_report "official marketplace registration" plugin marketplace add anthropics/claude-plugins-official || \
    cli_report "official marketplace refresh" plugin marketplace update claude-plugins-official || warn "official marketplace unavailable; check the error above"
  cli_report "toolshed marketplace registration" plugin marketplace add Eigenwise/eigenwise-toolshed || \
    cli_report "toolshed marketplace refresh" plugin marketplace update "$TOOLSHED" || warn "toolshed marketplace unavailable; check the error above"

  # local marketplace: pinned community clones (manifests repaired + validated) and kotlin-lsp-android
  [ ! -L "$MP_DIR/plugins" ] && [ ! -L "$MP_DIR/.claude-plugin" ] || die "marketplace directories must not be symlinks"
  mkdir -p "$MP_DIR/plugins" "$MP_DIR/.claude-plugin"
  local entries=() id tier src sha name dest pj real
  while read -r id tier src sha; do
    [[ "$id" == *"@$MP_NAME" && "$tier" != retired ]] || continue
    name="${id%@*}"; dest="$MP_DIR/plugins/$name"
    if [ "$tier" = extra ] && [ "$EXTRAS" = 0 ] && [[ ",$WITH," != *",$name,"* ]] && [ ! -d "$dest" ]; then continue; fi
    [ ! -L "$dest" ] || { warn "$name: cache destination is a symlink; skipped"; continue; }
    if [[ "$src" == local:* ]]; then
      rm -rf "$dest"; cp -a "$(kit "lib/${src#local:}")" "$dest"
    else
      [ -d "$dest/.git" ] || git clone -q "$src" "$dest" || { warn "$name: clone failed"; continue; }
      git -C "$dest" fetch -q origin 2>/dev/null || true
      if [ "$sha" != "-" ] && [ "$sha" != "main" ]; then
        if ! git -C "$dest" cat-file -e "$sha^{commit}" 2>/dev/null; then
          warn "$name: pinned commit $sha unavailable; skipped (no branch fallback)"; continue
        fi
        git -C "$dest" checkout -q -f "$sha" || { warn "$name: checkout failed"; continue; }
      else
        local def; def=$(git -C "$dest" symbolic-ref -q --short refs/remotes/origin/HEAD 2>/dev/null | sed 's|origin/||'); def=${def:-main}
        git -C "$dest" checkout -q -f "$def" && git -C "$dest" reset -q --hard "origin/$def" 2>/dev/null || true
      fi
      python3 "$(kit lib/normalize-plugin-manifest.py)" "$dest" "$name" >/dev/null   # also renames reserved claude-* names
    fi
    pj="$dest/.claude-plugin/plugin.json"
    [ -f "$pj" ] || { warn "$name: no plugin.json, skipped"; continue; }
    cli_report "$name validation" plugin validate "$dest" || { warn "$name: skipped; see the CLI error above"; continue; }
    real=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['name'])" "$pj")
    [ "$real" = "$name" ] || { warn "$name: manifest name is '$real'; fix plugins.conf"; continue; }
    entries+=("$(python3 -c "import json,sys;d=json.load(open(sys.argv[1]));print(json.dumps({'name':d['name'],'description':d.get('description','')[:200],'source':'./plugins/'+sys.argv[2]}))" "$pj" "$name")")
  done <<< "$(conf_lines)"
  for id in $(ids_of retired); do if [[ "$id" == *"@$MP_NAME" ]]; then rm -rf "$MP_DIR/plugins/${id%@*}"; fi; done   # retired clones
  [ ${#entries[@]} -gt 0 ] || die "no local plugin passed validation; existing marketplace index left unchanged"
  local index="$MP_DIR/.claude-plugin/marketplace.json" index_backup=""
  if [ -f "$index" ]; then index_backup=$(mktemp "$MP_DIR/.claude-plugin/index-backup.XXXXXXXX"); cp -p "$index" "$index_backup"; fi
  python3 - "$MP_DIR/.claude-plugin/marketplace.json" "$MP_NAME" "${entries[@]}" <<'PY'
import json, sys
out, name, *e = sys.argv[1:]
json.dump({"name": name, "owner": {"name": "android-kit"}, "plugins": [json.loads(x) for x in e]}, open(out, "w"), indent=2)
PY
  if ! cli_report "generated marketplace validation" plugin validate "$MP_DIR"; then
    if [ -n "$index_backup" ]; then mv "$index_backup" "$index"; else rm -f "$index"; fi
    die "marketplace validation failed; previous index restored (if present). See the actual CLI error above"
  fi
  [ -z "$index_backup" ] || rm -f "$index_backup"
  if claude plugin marketplace list 2>/dev/null | grep -q "$MP_NAME"; then cli_report "local marketplace refresh" plugin marketplace update "$MP_NAME" || die "local marketplace refresh failed"
  else cli_report "local marketplace registration" plugin marketplace add "$MP_DIR" || die "local marketplace registration failed"; fi
  ok "marketplace $MP_NAME: ${#entries[@]} plugins ($MP_DIR)"

  [ "$MIGRATE" = yes ] || [ "$YES" != 1 ] || MIGRATE=no
  migrate_user_scope
  echo "$KIT_VERSION" > "$KIT_HOME/.machine-done"
  flock -u 9
}

migrate_user_scope() {   # plugins enabled for every project at user scope → off there (projects enable them locally)
  local us="$HOME/.claude/settings.json"; [ -f "$us" ] || return 0
  local all retired on gone
  all=$(conf_lines | awk '$2!="retired"{print $1}'); retired=$(ids_of retired)
  on=$(python3 -c "import json,sys;s=json.load(open(sys.argv[1])).get('enabledPlugins',{});print(' '.join(i for i in sys.argv[2:] if s.get(i) is True))" "$us" $all)
  # Gateway wired machine-wide (env.ANTHROPIC_BASE_URL → local router in user settings): every project's Claude
  # traffic depends on the router, which only starts where the plugin is enabled. Leave it on at user scope.
  if grep -q '"model-gateway@'"$TOOLSHED"'"' <<<"$(printf '"%s"\n' $on)" && \
     python3 -c "import json,sys;sys.exit(0 if '18764' in json.load(open(sys.argv[1])).get('env',{}).get('ANTHROPIC_BASE_URL','') else 1)" "$us" 2>/dev/null; then
    on=$(tr ' ' '\n' <<<"$on" | grep -vx "model-gateway@$TOOLSHED" | tr '\n' ' ' || true)
    warn "Model Gateway is wired for every project (user settings); keeping it enabled at user scope so they keep working."
  fi
  gone=$(python3 -c "import json,sys;s=json.load(open(sys.argv[1])).get('enabledPlugins',{});print(' '.join(i for i in sys.argv[2:] if i in s))" "$us" $retired)
  [ -n "$on$gone" ] || return 0
  [ -n "$on" ] && echo "  $(echo $on | wc -w) kit plugins are enabled for ALL projects (user scope); android-kit enables them per project."
  [ -n "$gone" ] && echo "  Retired (replaced by the toolshed base or kotlin-lsp-android): $gone"
  if [ "$MIGRATE" = yes ] || { [ "$MIGRATE" != no ] && ask "Switch them off at user scope (retired ones uninstalled)?"; }; then
    local p
    for p in $on; do claude plugin disable "$p" --scope user >/dev/null 2>&1 && ok "user scope off: $p" || warn "could not disable $p"; done
    for p in $gone; do claude plugin uninstall "$p" --scope user >/dev/null 2>&1 && ok "uninstalled: $p" || warn "could not uninstall $p"; done
  else warn "left as is (later: android-kit --machine --migrate)"; fi
}

# ─────────────────────────────────────────────────────────── Model Gateway
base_url_conflicts() {   # a process export of ANTHROPIC_BASE_URL beats the gateway's local wiring
  local gw="127.0.0.1:18764"
  if [ -n "${ANTHROPIC_BASE_URL:-}" ] && [[ "$ANTHROPIC_BASE_URL" != *"$gw"* ]]; then
    if [[ "$ANTHROPIC_BASE_URL" == *"api.anthropic.com"* ]]; then
      warn "your shell exports ANTHROPIC_BASE_URL=$ANTHROPIC_BASE_URL; it overrides the gateway, so GPT routes won't work."
    else
      warn "your shell exports ANTHROPIC_BASE_URL=$ANTHROPIC_BASE_URL (CLIProxyAPI?): it overrides the gateway and"
      warn "  sends ALL Claude traffic through that proxy."
    fi
    warn "  Remove the export (~/.bashrc, ~/.profile) and open a new shell."
  fi
  local us="$HOME/.claude/settings.json" u
  [ -f "$us" ] || return 0
  u=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('env',{}).get('ANTHROPIC_BASE_URL',''))" "$us" 2>/dev/null || true)
  if [ -n "$u" ] && [[ "$u" != *"$gw"* ]]; then
    warn "~/.claude/settings.json sets ANTHROPIC_BASE_URL=$u for every project; this project's gateway wiring wins here,"
    warn "  other projects still route Claude through it. Remove env.ANTHROPIC_BASE_URL there unless you mean that."
  fi
}

gateway_setup() {   # in the project dir; ChatGPT sign-in happens here in your terminal
  local ip; ip=$(install_path "model-gateway@$TOOLSHED"); local cli="$ip/bin/model-gateway.js"
  [ -n "$ip" ] && [ -f "$cli" ] || { warn "Model Gateway CLI not found; in Claude say: Set up Model Gateway for me"; return 0; }
  local out rc=0; out=$(node "$cli" setup 2>&1 < /dev/null) || rc=$?
  if grep -q ' login ' <<<"$out"; then
    if [ -t 0 ] && [ "$YES" != 1 ]; then
      echo "  ChatGPT sign-in for the GPT routes (opens your browser; Ctrl-C to skip, Sidequest then uses Claude only)."
      node "$cli" login || warn "sign-in didn't finish"
      rc=0; out=$(node "$cli" setup 2>&1 < /dev/null) || rc=$?
    else
      warn "ChatGPT sign-in needed for GPT routes. From this project: node \"$cli\" login && node \"$cli\" setup"
    fi
  fi
  if [ "$rc" != 0 ] && ! grep -q ' login ' <<<"$out"; then
    warn "Model Gateway setup failed:"; tail -3 <<<"$out" | sed 's/^/      /'
    warn "  retry: android-kit   (GitHub API rate limits clear within an hour)"
  fi
  if grep -q 'ANTHROPIC_BASE_URL' .claude/settings.local.json 2>/dev/null; then ok "Model Gateway wired (.claude/settings.local.json)"
  else warn "Model Gateway not wired yet; until then Sidequest routes every ticket to Claude"; fi
}

# ─────────────────────────────────────────────────────────── per project
project_setup() {
  local T; T="$(cd "${TARGET:-.}" && pwd)"
  [ "$T" != "$HOME" ] && [ "$T" != "/" ] || die "refusing to set up $T; cd into a project folder first"
  [ "$T" != "$KIT_SRC" ] && [ "$T" != "$KIT_HOME" ] || die "run this from the project root, not from inside the kit"
  cd "$T"
  local TPL; TPL=$(kit template)
  local kitdir_rel="__none__"; case "$KIT_SRC/" in "$T"/*) kitdir_rel="${KIT_SRC#$T/}";; esac

  local mode state=".claude/kit/state.json" first_run=0 upgraded=0
  local others; others=$(find . -mindepth 1 -maxdepth 1 ! -name .git ! -name .idea ! -name '.gitignore' \
      ! -iname 'README*' ! -iname 'LICENSE*' ! -name "${kitdir_rel%%/*}" | wc -l)
  if [ -f "$state" ]; then mode=$(python3 -c "import json;print(json.load(open('$state'))['mode'])")
  else first_run=1
    if [ "$others" -eq 0 ]; then mode=new
    elif git rev-parse --verify -q HEAD >/dev/null 2>&1 && [ -n "$(git remote 2>/dev/null)" ]; then mode=fork
    else mode=existing; fi
  fi
  if [ -f "$state" ] && python3 -c 'import json,sys;sys.exit(0 if json.load(open(sys.argv[1])).get("local_only") else 1)' "$state"; then LOCAL_ONLY=1; fi
  b "Project: $T  (mode: $mode)"

  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then git init -q -b main 2>/dev/null || git init -q; ok "git init"; fi
  command -v flock >/dev/null || die "flock required (Ubuntu util-linux)"
  local lock; lock=$(git rev-parse --git-path android-kit.lock)
  exec 8>"$lock"
  flock -n 8 || die "another android-kit setup is running in this checkout"
  local exclude; exclude=$(git rev-parse --git-path info/exclude)
  mkdir -p "$(dirname "$exclude")"
  touch "$exclude"
  [ ! -s "$exclude" ] || [ -z "$(tail -c1 "$exclude")" ] || echo >> "$exclude"
  if [ "$kitdir_rel" != "__none__" ]; then
    grep -qxF "/$kitdir_rel/" "$exclude" || echo "/$kitdir_rel/" >> "$exclude"
  fi
  # Backups and local state must never be accidentally staged, even on interrupted runs.
  for private in '/.claude/settings.local.json' '/.claude/kit/backups/' '/.claude/kit/incoming/' '/.claude/kit/retired/' '/.claude/kit/manifest.json' '/.claude/kit/manifest.txt' '/.claude/kit/state.json' '/.claude/kit/conflicts.json' '/.claude/kit/logs/' '/.claude/compact-checkpoints/'; do
    grep -qxF "$private" "$exclude" || echo "$private" >> "$exclude"
  done
  python3 "$(kit lib/project-files.py)" snapshot "$T" "$TPL" | report

  # CLAUDE.md / PROGRESS.md: created once, never overwritten
  if [ ! -f CLAUDE.md ]; then cp "$TPL/CLAUDE.md" CLAUDE.md; ok "CLAUDE.md"
  elif ! head -1 CLAUDE.md | grep -q 'android-kit'; then
    if [ ! -f CLAUDE.upstream.md ]; then mv CLAUDE.md CLAUDE.upstream.md
    else warn "CLAUDE.upstream.md already exists; current CLAUDE.md preserved in this run's snapshot"; fi
    cp "$TPL/CLAUDE.md" CLAUDE.md; ok "CLAUDE.md (existing one kept as CLAUDE.upstream.md for /bootstrap to fold in)"
  elif grep -qE '^## (Session protocol|Working with agents|Subagent orchestration)' CLAUDE.md && ! grep -q '^## How work flows here' CLAUDE.md; then
    mkdir -p .claude/kit/retired; [ -e .claude/kit/retired/CLAUDE.v1.md ] || cp CLAUDE.md .claude/kit/retired/CLAUDE.v1.md; cp "$TPL/CLAUDE.md" CLAUDE.md; upgraded=1
    ok "CLAUDE.md: previous kit version → .claude/kit/retired/CLAUDE.v1.md (/bootstrap folds in what still applies)"
  else ok "CLAUDE.md kept"; fi
  # lines an earlier kit version wrote word for word get the current wording; anything you edited is left alone
  python3 - "$TPL/CLAUDE.md" <<'PY' && ok "CLAUDE.md: refreshed kit lines (routing, bugs) from an earlier kit version" || true
import sys, pathlib
OLD = {
    "- **Routing** (Sidequest profile `android-kit`; `sidequest models` shows it live): GPT implements (GPT-6 Luna mechanical, GPT-6.1 Sol default, GPT-6 Astra hard); UI, review and escalation stay on Claude (Opus, Fable only after Opus stalls); research on Sonnet. UI is never routed to GPT.": "- **Routing**",
    "- **Routing** (Sidequest profile `android-kit`; `sidequest models` shows it live): GPT implements (GPT-6 Luna mechanical, GPT-6.1 Sol default and hard, GPT-6 Astra on high-stakes hard tickets); UI, review and escalation stay on Claude (Opus, Fable only after Opus stalls); research on Sonnet. UI is never routed to GPT.": "- **Routing**",
    "- **Bugs:** every normal/hard/debugging ticket gets an Opus review before it's integrated. `/bug-hunt [area]` hunts in existing code; `/code-review` checks a diff before you push.": "- **Bugs:**",
}
tpl = pathlib.Path(sys.argv[1]).read_text().splitlines()
cur_path = pathlib.Path("CLAUDE.md"); lines = cur_path.read_text().splitlines()
changed = False
for i, line in enumerate(lines):
    prefix = OLD.get(line)
    new = next((t for t in tpl if prefix and t.startswith(prefix)), None)
    if new and new != line:
        lines[i] = new; changed = True
if changed:
    cur_path.write_text("\n".join(lines) + "\n")
sys.exit(0 if changed else 1)
PY
  if [ ! -f PROGRESS.md ]; then cp "$TPL/PROGRESS.md" PROGRESS.md; ok "PROGRESS.md"
  elif grep -qE '^## (Now \(this session|Next \(ordered\))' PROGRESS.md; then
    mkdir -p .claude/kit/retired; [ -e .claude/kit/retired/PROGRESS.v1.md ] || cp PROGRESS.md .claude/kit/retired/PROGRESS.v1.md; upgraded=1
    python3 - "$TPL/PROGRESS.md" <<'PY'
import re, sys, pathlib
old = pathlib.Path("PROGRESS.md").read_text(); new = pathlib.Path(sys.argv[1]).read_text()
def body(text, head):   # section body without placeholder lines
    m = re.search(r"^## " + re.escape(head) + r"[^\n]*\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    lines = [l for l in (m.group(1).splitlines() if m else [])
             if l.strip() and not l.startswith(("- YYYY-MM-DD", "- <", "_"))]
    return "\n".join(lines)
title = re.search(r"^# .*$", old, re.M)
if title: new = re.sub(r"^# .*$", title.group(0), new, count=1, flags=re.M)
for head in ("Decision log", "Environment notes"):
    carried = body(old, head)
    if carried:
        new = re.sub(r"(^## " + re.escape(head) + r"[^\n]*\n)(?:- <[^\n]*\n)?", lambda m: m.group(1) + carried + "\n", new, count=1, flags=re.M)
pathlib.Path("PROGRESS.md").write_text(new)
PY
    ok "PROGRESS.md: slimmed (decision log + environment notes kept); Now/Next/Blockers → .claude/kit/retired/PROGRESS.v1.md for /bootstrap to turn into tickets"
  else ok "PROGRESS.md kept"; fi

  if [ -L AGENTS.md ] && [ "$(readlink AGENTS.md)" = CLAUDE.md ] && grep -q '"gpt": true' "$state" 2>/dev/null; then
    rm AGENTS.md; ok "removed AGENTS.md → CLAUDE.md link (previous GPT layer's Codex bridge)"
  fi

  # Hash-based ownership: preserve personal edits and stage conflicting updates for review.
  python3 "$(kit lib/project-files.py)" sync "$T" "$TPL" | report
  chmod +x .claude/kit/*.sh .claude/kit/*.py 2>/dev/null || true

  # settings: the kit's hooks/permissions are local; an earlier kit's entries leave the committed settings.json
  [ -n "$(python3 "$(kit lib/merge-settings.py)" --strip-kit .claude/settings.json "$TPL/.claude/settings.json" .claude/settings.local.json)" ] && ok "old kit hooks/permissions moved out of committed .claude/settings.json"
  python3 "$(kit lib/merge-settings.py)" "$TPL/.claude/settings.json" .claude/settings.local.json | report
  ok ".claude/settings.local.json (hooks, permissions)"
  local g dst src line
  if [ "$LOCAL_ONLY" = 1 ]; then
    while IFS= read -r line; do
      [ -z "$line" ] || [[ "$line" == \#* ]] || grep -qxF "$line" "$exclude" || echo "$line" >> "$exclude"
    done < "$TPL/gitignore.append"
    for line in '/CLAUDE.md' '/PROGRESS.md' '/CLAUDE.upstream.md' '/.claude/live-rules/' '/.claude/.codebase-info/' '/.claude/kit/' '/.claude/commands/' '/.claude/skills/'; do
      grep -qxF "$line" "$exclude" || echo "$line" >> "$exclude"
    done
    ok "kit paths excluded locally (tracked files remain tracked)"
  else
    for g in ".claude/.gitignore:$TPL/.claude/.gitignore" ".gitignore:$TPL/gitignore.append"; do
      dst="${g%%:*}"; src="${g#*:}"; touch "$dst"
      [ ! -s "$dst" ] || [ -z "$(tail -c1 "$dst")" ] || echo >> "$dst"
      while IFS= read -r line; do [ -z "$line" ] || grep -qxF -- "$line" "$dst" || echo "$line" >> "$dst"; done < "$src"
    done
    ok ".gitignore entries"
  fi
  [ "$mode" = new ] && touch .claude/kit/format-on-edit

  local enabled=() failed=()
  if [ "$NO_PLUGINS" = 0 ]; then
    b "Plugins (local scope)"
    # an earlier kit enabled plugins in the committed .claude/settings.json: move them out of it
    local proj known id tier _s _h n
    proj=$(python3 -c "import json;print(' '.join(json.load(open('.claude/settings.json')).get('enabledPlugins',{})))" 2>/dev/null || true)
    known=$(conf_lines | awk '{print $1}')
    for id in $proj; do
      grep -qxF "$id" <<<"$known" || continue
      # uninstall fails when the plugin isn't in this machine's cache; then just drop the entry
      if claude plugin uninstall "$id" --scope project >/dev/null 2>&1 || python3 -c "
import json, sys
p = '.claude/settings.json'; d = json.load(open(p)); e = d.get('enabledPlugins', {})
if sys.argv[1] not in e: sys.exit(1)
del e[sys.argv[1]]; open(p, 'w').write(json.dumps(d, indent=2) + '\n')" "$id" 2>/dev/null; then
        ok "moved out of committed settings: ${id%@*}"
      fi
    done
    for id in $(ids_of retired); do
      python3 -c "import json,sys;sys.exit(0 if sys.argv[1] in json.load(open('.claude/settings.local.json')).get('enabledPlugins',{}) else 1)" "$id" 2>/dev/null || continue
      # uninstall can fail once the plugin is gone from its marketplace; then just drop the entry
      claude plugin uninstall "$id" --scope local >/dev/null 2>&1 || python3 -c "
import json, sys
p = '.claude/settings.local.json'; d = json.load(open(p)); d.get('enabledPlugins', {}).pop(sys.argv[1], None)
open(p, 'w').write(json.dumps(d, indent=2) + '\n')" "$id"
      ok "removed retired ${id%@*}"
    done
    local has_java=0; [ -n "$(find . -name '*.java' -not -path '*/build/*' -print -quit 2>/dev/null)" ] && has_java=1
    # language servers for the LSP plugins: kotlin-lsp always, jdtls when the project has Java sources
    if [ "$NO_LSP" = 0 ]; then
      lsp_ensure kotlin "kotlin-lsp from JetBrains (~370 MB, bundles its own Java)" kotlin-lsp
      if [ "$has_java" = 1 ]; then lsp_ensure java "Eclipse JDT LS (~50 MB, runs on Java 21+)" jdtls; fi
      bash "$(kit lib/install-lsp.sh)" path 2>&1 | report || true
    fi
    while read -r id tier _s _h; do
      n="${id%@*}"
      case "$tier" in
        base) [ "$n" = model-gateway ] && [ "$NO_GATEWAY" = 1 ] && continue ;;
        android) ;;
        java) [ "$has_java" = 1 ] || continue ;;
        extra) [ "$EXTRAS" = 1 ] || [[ ",$WITH," == *",$n,"* ]] || continue ;;
        *) continue ;;
      esac
      if cli_report "$n installation" plugin install "$id" --scope "$SCOPE"; then enabled+=("$n"); ok "$n$( [ "$tier" = base ] && echo '  (base)')"
      else failed+=("$id"); warn "$n: install failed (claude plugin install $id --scope $SCOPE)$( [[ "$id" == *"@$MP_NAME" ]] && echo "; android-kit --machine rebuilds $MP_DIR")"; fi
    done <<< "$(conf_lines)"

    python3 "$(kit lib/merge-settings.py)" --strip-kit .claude/settings.json >/dev/null   # drop an emptied enabledPlugins
    if git ls-files --error-unmatch .claude/settings.local.json >/dev/null 2>&1; then
      warn ".claude/settings.local.json is tracked by git, so it isn't private. Untrack it: git rm --cached .claude/settings.local.json"
    fi

    # live-rules: build the manifest for the kit's rule files
    local lr; lr=$(install_path "live-rules@$TOOLSHED")
    if [ -n "$lr" ] && node "$lr/scripts/sync-atomic-rules.js" --project "$T" >/dev/null 2>&1; then ok "live-rules synced ($(ls .claude/live-rules/rules/*.md 2>/dev/null | wc -l) rules)"
    else warn "live-rules sync didn't run; in Claude: \"sync live rules\""; fi

    # sidequest: integration branch = the branch you're on now (first run only)
    local sq; sq=$(install_path "sidequest@$TOOLSHED")
    if [ "$first_run" = 1 ] && [ -n "$sq" ]; then
      local br; br=$(git symbolic-ref -q --short HEAD 2>/dev/null || echo main)
      node "$sq/bin/sidequest.js" board-config --integration-branch "$br" >/dev/null 2>&1 \
        && ok "Sidequest board: integration branch $br" || warn "Sidequest board config not set; /bootstrap will do it"
    fi

    # routing: the kit's Sidequest profile (machine-wide, yours once you edit it) and this board on it
    local rp=()
    if [ -n "$sq" ]; then
      rp=(python3 "$(kit lib/routing-profile.py)" "$sq/bin/sidequest.js" "$(kit lib/routing-profile.json)")
      { "${rp[@]}" apply $( [ "$RESET_ROUTING" = 1 ] && echo --reset ); "${rp[@]}" use "$T"; } 2>&1 | report || true
    fi

    if [ "$NO_GATEWAY" = 0 ]; then b "Model Gateway"; base_url_conflicts; gateway_setup
    else
      local gip; gip=$(install_path "model-gateway@$TOOLSHED")
      if [ -n "$gip" ] && [ -f "$gip/bin/model-gateway.js" ]; then
        node "$gip/bin/model-gateway.js" env --remove >/dev/null 2>&1 || warn "gateway native unwire failed; removing only known local URL"
      fi
      python3 "$(kit lib/merge-settings.py)" --no-gateway .claude/settings.local.json
      warn "Gateway disabled locally; remove any shell-exported ANTHROPIC_BASE_URL before starting Claude"
    fi
    [ ${#rp[@]} -gt 0 ] && { "${rp[@]}" check 2>&1 | report || true; }
  fi

  AK_FAILED="${failed[*]}" AK_PLUGINS_RUN=$((1-NO_PLUGINS)) AK_LOCAL_ONLY=$LOCAL_ONLY AK_UPGRADED=$upgraded python3 - "$state" "$mode" "$KIT_VERSION" "${enabled[@]}" <<'PY'
import json, sys, datetime, os
p, mode, ver, *pl = sys.argv[1:]
old = json.load(open(p)) if os.path.exists(p) else {}
old.pop("gpt", None)
if os.environ.get("AK_UPGRADED") == "1": old["upgrade_pending"] = True
old.update({"mode": mode, "kit_version": ver, "scope": "local", "updated": datetime.date.today().isoformat()})
old["local_only"] = os.environ.get("AK_LOCAL_ONLY") == "1"
old.setdefault("created", old["updated"])
if os.environ.get("AK_PLUGINS_RUN") == "1":
    old["plugins"] = pl
    old["failed_plugins"] = os.environ.get("AK_FAILED", "").split()
json.dump(old, open(p, "w"), indent=1)
PY

  echo
  if [ -d .claude/kit/retired ] && grep -q '"upgrade_pending": true' "$state"; then
    b "Done. Next:"
    echo "  1. claude            (a FULL start, not /reload-plugins)"
    echo "  2. /bootstrap        (finishes the upgrade: refills CLAUDE.md from your previous one, turns old"
    echo "                        Now/Next items and custom agents into Sidequest tickets/categories if you want them)"
  elif grep -q '<APP NAME>\|<kotlin | java' CLAUDE.md; then
    b "Done. Next:"
    echo "  1. claude            (in $T; a FULL start, not /reload-plugins, so the gateway wiring and plugins load)"
    echo "  2. /bootstrap        ($( [ "$mode" = new ] && echo 'asks a few questions, scaffolds a Compose app, maps it' || echo 'learns the project, records a baseline, maps it'))"
  else
    b "Done. Restart Claude Code. Review any .claude/kit/incoming/ conflicts before /bootstrap."
  fi
  [ "$NO_PLUGINS" = 0 ] && [ "$kitdir_rel" != "__none__" ] && echo "  You can delete ./$kitdir_rel now (git-excluded meanwhile); next time just run: android-kit"
  if [ ${#failed[@]} -gt 0 ]; then
    warn "Incomplete plugin setup: ${failed[*]}. Files were saved; fix the reported installs and rerun android-kit."
    return 3
  fi
  return 0
}

# ─────────────────────────────────────────────────────────── main
[ "$NO_PLUGINS" = 0 ] || [ "$MACHINE_ONLY" = 0 ] || die "--machine and --no-plugins cannot be combined"
if [ "$MACHINE_ONLY" = 0 ]; then
  TARGET=$(cd "${TARGET:-.}" && pwd -P) || die "target directory not found"
  [ "$TARGET" != "$HOME" ] && [ "$TARGET" != / ] && [ "$TARGET" != "$KIT_SRC" ] && [ "$TARGET" != "$KIT_HOME" ] || die "choose a project root, not home or the kit"
  top=$(git -C "$TARGET" rev-parse --show-toplevel 2>/dev/null || true)
  [ -z "$top" ] || [ "$top" = "$TARGET" ] || die "target is inside $top; run setup at that repository root"
  python3 "$(kit lib/project-files.py)" check "$TARGET" "$(kit template)"
fi
python3 "$(kit lib/validate-config.py)" "$(kit plugins.conf)"
for managed in "$KIT_HOME" "$MP_DIR"; do
  resolved=$(realpath -m "$managed")
  case "$resolved" in /|"$HOME"|"$HOME/.claude"|"$HOME/.local"|"$HOME/.local/share"|"${TARGET:-/}") die "unsafe installation directory: $managed";; esac
  [ ! -L "$managed" ] || die "installation directory must not be a symlink: $managed"
  if [ "$resolved" != "$KIT_SRC" ]; then
    case "$resolved/" in "$KIT_SRC/"*) die "installation directory cannot be nested inside kit source";; esac
    case "$KIT_SRC/" in "$resolved/"*) die "installation directory cannot contain kit source";; esac
  fi
done
if [ -n "$WITH" ]; then
  IFS=',' read -ra selected <<< "$WITH"
  for name in "${selected[@]}"; do
    conf_lines | awk '$2=="extra"{sub(/@.*/,"",$1); print $1}' | grep -qxF "$name" || die "unknown opt-in plugin: $name"
  done
fi
if [ "$DRY_RUN" = 1 ]; then
  echo "Project: ${TARGET:-machine only}; local-only: $LOCAL_ONLY; plugins: $((1-NO_PLUGINS)); gateway: $((1-NO_GATEWAY))"
  echo "Will snapshot existing setup, merge settings, and stage modified-file conflicts in .claude/kit/incoming/."
  echo "Machine data (when plugins enabled): $KIT_HOME; $MP_DIR. No changes made."
  exit 0
fi
preflight
kit_changed() { [ "$KIT_SRC" != "$KIT_HOME" ] && ! diff -rq -x .machine-done "$KIT_SRC" "$KIT_HOME" >/dev/null 2>&1; }
local_mp_ok() {   # the local marketplace is registered and every plugin it lists is still on disk
  local mj="$MP_DIR/.claude-plugin/marketplace.json"
  [ -f "$mj" ] || return 1
  python3 -c "
import json, os, sys
d = json.load(open(sys.argv[1]))
sys.exit(0 if d.get('plugins') and all(os.path.isfile(os.path.join(sys.argv[2], p['source'], '.claude-plugin', 'plugin.json')) for p in d['plugins']) else 1)" "$mj" "$MP_DIR" 2>/dev/null || return 1
  claude plugin marketplace list 2>/dev/null | grep -q "$MP_NAME"
}
if [ "$NO_PLUGINS" = 1 ]; then
  : # genuinely offline/project-only; no Claude CLI or user-directory changes
elif [ "$MACHINE_ONLY" = 1 ] || [ "$EXTRAS" = 1 ] || [ -n "$WITH" ] || [ ! -f "$KIT_HOME/.machine-done" ] || [ "$(cat "$KIT_HOME/.machine-done")" != "$KIT_VERSION" ] || kit_changed; then
  machine_setup
elif ! local_mp_ok; then
  warn "local marketplace $MP_DIR is missing or incomplete; rebuilding it (clones the community plugins again)"
  machine_setup
fi
[ "$MACHINE_ONLY" = 1 ] && exit 0
project_setup
