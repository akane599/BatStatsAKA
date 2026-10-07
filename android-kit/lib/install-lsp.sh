#!/usr/bin/env bash
# android-kit: install the language servers the kit's LSP plugins run, into ~/.local, and put them on PATH.
#
#   install-lsp.sh kotlin [--reinstall]   kotlin-lsp (JetBrains, bundles its own Java)  → ~/.local/bin/kotlin-lsp
#   install-lsp.sh java   [--reinstall]   Eclipse JDT LS (jdtls, needs Java 21+)        → ~/.local/bin/jdtls
#   install-lsp.sh path                   make sure ~/.local/bin is on PATH in new shells
#
# Latest stable versions are looked up live; the pinned ones below are the fallback when that fails.
# Prints "ok …" / "warn …" / "info …" lines (setup.sh shows them); exits non-zero only when an install failed.
set -euo pipefail

KOTLIN_LSP_PIN="263.4702.0"
JDTLS_PIN="1.61.0"; JDTLS_PIN_FILE="jdt-language-server-1.61.0-202609031315.tar.gz"
BIN="$HOME/.local/bin"; SHARE="$HOME/.local/share"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/android-kit"
REINSTALL=0; [ "${2:-}" = "--reinstall" ] && REINSTALL=1
say() { echo "$1 ${*:2}"; }
mkdir -p "$BIN" "$SHARE" "$CACHE"
export PATH="$BIN:$PATH"

fetch() {   # url → file; quiet unless it fails
  curl -fsSL --retry 2 --connect-timeout 20 --max-time 300 -o "$2" "$1"
}

java_major() { "$1" -version 2>&1 | grep -m1 -oE 'version "[0-9]+' | grep -oE '[0-9]+$' || echo 0; }

java21() {   # first Java 21+ runtime: JDTLS_JAVA, JAVA_HOME, java on PATH, kotlin-lsp's bundled runtime
  local j
  for j in "${JDTLS_JAVA:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null || true)" "$SHARE/kotlin-lsp/current/jbr/bin/java"; do
    [ -n "$j" ] && [ -x "$j" ] && [ "$(java_major "$j")" -ge 21 ] && { echo "$j"; return 0; }
  done
  return 1
}

install_kotlin() {
  if [ "$REINSTALL" = 0 ] && command -v kotlin-lsp >/dev/null; then say ok "kotlin-lsp → $(command -v kotlin-lsp)"; return 0; fi
  if [ "$REINSTALL" = 0 ] && [ -x "$BIN/kotlin-lsp.sh" ]; then   # an earlier manual install
    ln -sf "$BIN/kotlin-lsp.sh" "$BIN/kotlin-lsp"; say ok "kotlin-lsp: linked $BIN/kotlin-lsp → kotlin-lsp.sh"; return 0
  fi
  local v suffix=""
  v=$(git ls-remote --tags https://github.com/Kotlin/kotlin-lsp 2>/dev/null | grep -oE 'refs/tags/kotlin-lsp/v[0-9]+\.[0-9]+\.[0-9]+$' | sed 's|.*/v||' | sort -V | tail -1 || true)
  v=${v:-$KOTLIN_LSP_PIN}
  case "$(uname -m)" in aarch64|arm64) suffix="-aarch64";; x86_64|amd64) ;; *) say warn "unsupported architecture: $(uname -m)"; return 1;; esac
  local url="https://download-cdn.jetbrains.com/language-server/kotlin-server/$v/kotlin-server-$v$suffix.tar.gz"
  local tgz="$CACHE/kotlin-server-$v$suffix.tar.gz" dest="$SHARE/kotlin-lsp/$v"
  say info "downloading kotlin-lsp $v (~370 MB)…"
  fetch "$url" "$tgz" || { say warn "kotlin-lsp download failed: $url"; return 1; }
  rm -rf "$dest.tmp"; mkdir -p "$dest.tmp"
  tar -xzf "$tgz" -C "$dest.tmp" --strip-components=1 && rm -f "$tgz"
  [ -x "$dest.tmp/bin/intellij-server" ] || { say warn "kotlin-lsp $v: bin/intellij-server missing in the archive"; rm -rf "$dest.tmp"; return 1; }
  rm -rf "$dest"; mv "$dest.tmp" "$dest"
  ln -sfn "$v" "$SHARE/kotlin-lsp/current"
  ln -sf "$SHARE/kotlin-lsp/current/bin/intellij-server" "$BIN/kotlin-lsp"
  find "$SHARE/kotlin-lsp" -mindepth 1 -maxdepth 1 -type d ! -name "$v" -exec rm -rf {} + 2>/dev/null || true
  say ok "kotlin-lsp $v installed ($SHARE/kotlin-lsp/current, on PATH as $BIN/kotlin-lsp)"
}

install_java() {
  if [ "$REINSTALL" = 0 ] && command -v jdtls >/dev/null; then say ok "jdtls → $(command -v jdtls)"; return 0; fi
  local v file
  v=$(curl -fsSL --connect-timeout 20 https://download.eclipse.org/jdtls/milestones/ 2>/dev/null | grep -oE 'milestones/[0-9]+\.[0-9]+\.[0-9]+' | sed 's|milestones/||' | sort -uV | tail -1 || true)
  file=$( [ -n "$v" ] && curl -fsSL --connect-timeout 20 "https://download.eclipse.org/jdtls/milestones/$v/latest.txt" 2>/dev/null | tr -d '[:space:]' || true)
  if [ -z "$v" ] || [ -z "$file" ]; then v=$JDTLS_PIN; file=$JDTLS_PIN_FILE; fi
  [[ "$file" =~ ^jdt-language-server-[0-9A-Za-z.-]+\.tar\.gz$ ]] || { say warn "invalid JDT LS archive filename"; return 1; }
  local tgz="$CACHE/$file" dest="$SHARE/jdtls/$v"
  say info "downloading Eclipse JDT LS $v (~50 MB)…"
  fetch "https://download.eclipse.org/jdtls/milestones/$v/$file" "$tgz" || { say warn "jdtls download failed"; return 1; }
  rm -rf "$dest.tmp"; mkdir -p "$dest.tmp"
  tar -xzf "$tgz" -C "$dest.tmp" && rm -f "$tgz"
  [ -f "$dest.tmp/bin/jdtls" ] || { say warn "jdtls $v: bin/jdtls missing in the archive"; rm -rf "$dest.tmp"; return 1; }
  rm -rf "$dest"; mv "$dest.tmp" "$dest"
  ln -sfn "$v" "$SHARE/jdtls/current"
  find "$SHARE/jdtls" -mindepth 1 -maxdepth 1 -type d ! -name "$v" -exec rm -rf {} + 2>/dev/null || true
  # launcher: jdtls needs Java 21+ to run (your projects can still target 17); pick one at every start
  cat > "$BIN/jdtls" <<'SH'
#!/usr/bin/env bash
# android-kit: Eclipse JDT LS launcher. Runs on the first Java 21+ it finds:
# JDTLS_JAVA, JAVA_HOME, java on PATH, then kotlin-lsp's bundled runtime.
major() { "$1" -version 2>&1 | grep -m1 -oE 'version "[0-9]+' | grep -oE '[0-9]+$' || echo 0; }
for j in "${JDTLS_JAVA:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)" "$HOME/.local/share/kotlin-lsp/current/jbr/bin/java"; do
  if [ -n "$j" ] && [ -x "$j" ] && [ "$(major "$j")" -ge 21 ]; then
    exec python3 "$HOME/.local/share/jdtls/current/bin/jdtls" --java-executable "$j" "$@"
  fi
done
echo "jdtls: no Java 21+ runtime found (set JDTLS_JAVA, or install JDK 21)" >&2; exit 1
SH
  chmod +x "$BIN/jdtls"
  say ok "jdtls $v installed ($SHARE/jdtls/current, on PATH as $BIN/jdtls)"
  java21 >/dev/null || say warn "jdtls needs a Java 21+ runtime to start and none was found: install JDK 21 or kotlin-lsp (it bundles one), or set JDTLS_JAVA"
}

ensure_path() {   # ~/.local/bin on PATH in new terminals (Ubuntu's ~/.profile adds it at login only if the folder existed then)
  case ":${ORIG_PATH:-$PATH}:" in *":$BIN:"*) return 0 ;; esac
  local line='case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) export PATH="$HOME/.local/bin:$PATH" ;; esac   # added by android-kit'
  local rc added=()
  for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
    [ -f "$rc" ] || continue
    grep -qF 'added by android-kit' "$rc" && continue
    printf '\n%s\n' "$line" >> "$rc"; added+=("${rc/#$HOME/\~}")
  done
  [ ${#added[@]} -gt 0 ] && say ok "~/.local/bin added to PATH in ${added[*]}"
  say warn "this terminal doesn't have ~/.local/bin on PATH yet: open a new terminal (or run: source ~/.bashrc) before starting claude"
}

case "${1:-}" in
  kotlin) install_kotlin ;;
  java) install_java ;;
  path) ensure_path ;;
  *) sed -n '2,8p' "$0"; exit 2 ;;
esac
