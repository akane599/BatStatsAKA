#!/usr/bin/env bash
# Installs Eclipse JDT.LS on Ubuntu for the official `jdtls-lsp` plugin (no Homebrew needed).
# Result: `jdtls` on PATH at ~/.local/bin/jdtls. Requires a JDK 17+ (JDK 21 is fine).
set -euo pipefail

DEST="${JDTLS_HOME:-$HOME/.local/share/jdtls}"
BIN="$HOME/.local/bin"
BASE="https://download.eclipse.org/jdtls/snapshots"

command -v java >/dev/null || { echo "java not found — install a JDK first: sudo apt install openjdk-21-jdk"; exit 1; }
command -v python3 >/dev/null || { echo "python3 required (the jdtls launcher is a Python script)"; exit 1; }

latest=$(curl -fsSL "$BASE/latest.txt")
echo "==> Downloading $latest"
mkdir -p "$DEST" "$BIN"
curl -fsSL "$BASE/$latest" | tar -xz -C "$DEST"

ln -sf "$DEST/bin/jdtls" "$BIN/jdtls"
chmod +x "$DEST/bin/jdtls"

case ":$PATH:" in *":$BIN:"*) ;; *) echo "==> Add to ~/.bashrc:  export PATH=\"\$HOME/.local/bin:\$PATH\"";; esac
echo "==> Installed. Verify: jdtls --help | head -3"
echo "    Then in Claude Code: claude plugin install jdtls-lsp@claude-plugins-official"
