#!/usr/bin/env python3
"""
Rewrite a cloned plugin's .claude-plugin/plugin.json so it passes Claude Code's manifest schema.
Used by setup.sh on the local-marketplace clones only (never on your projects).

Schema rules enforced (code.claude.com/docs/en/plugins-reference):
  - every component path starts with "./"
  - agents:   path or array of agent .md FILES (directories not accepted)   -> directories expanded
  - commands: path/array of .md files or directories                        -> {name,file} objects flattened
  - skills:   path/array of directories (a dir of <name>/SKILL.md, or a dir holding SKILL.md)
              -> ".../SKILL.md" file paths become their folder
  - hooks:    path, inline object, or mixed array; invalid shapes are preserved for validation
  - name:     set to <name> when given (Claude Code reserves names starting with "claude-" and similar for
              Anthropic's own plugins, so a third-party plugin named that way is renamed for what it does)
Usage: normalize-plugin-manifest.py <plugin_dir> [name]   (prints what changed; exit 0 even if nothing did)
"""
import json, sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
mf = root / ".claude-plugin" / "plugin.json"
if not mf.exists():
    sys.exit(0)
data = json.loads(mf.read_text())
orig = json.dumps(data, sort_keys=True)
notes = []
if len(sys.argv) > 2 and data.get("name") != sys.argv[2]:
    notes.append(f"name: {data.get('name')} -> {sys.argv[2]}")
    data["name"] = sys.argv[2]


def rel(p):
    """normalize to './x/y' relative to plugin root; None if it doesn't exist or escapes root"""
    if isinstance(p, dict):
        p = p.get("file") or p.get("path") or p.get("source")
    if not isinstance(p, str) or not p.strip():
        return None
    q = p.strip()
    if q in (".", "./"):
        return "./"
    q = q[2:] if q.startswith("./") else q.lstrip("/")
    full = (root / q).resolve()
    if root not in full.parents and full != root:
        return None
    if not full.exists():
        return None
    return "./" + q.rstrip("/") if full.is_file() else "./" + q.rstrip("/") + "/"


def as_list(v):
    return v if isinstance(v, list) else [v]


# agents: files only
if "agents" in data:
    out = []
    for p in as_list(data["agents"]):
        r = rel(p)
        if not r:
            continue
        full = (root / r[2:]).resolve()
        if full.is_dir():
            out += ["./" + str(f.relative_to(root)) for f in sorted(full.glob("*.md"))]
        elif full.suffix == ".md":
            out.append(r)
    if out:
        data["agents"] = out
    else:
        data.pop("agents"); notes.append("agents: dropped (nothing valid)")

# commands: files or dirs
if "commands" in data and not (isinstance(data["commands"], dict) and all(isinstance(v, dict) and ("source" in v or "content" in v) for v in data["commands"].values())):
    out = [r for r in (rel(p) for p in as_list(data["commands"])) if r]
    if out:
        data["commands"] = out
    else:
        data.pop("commands"); notes.append("commands: dropped (nothing valid)")

# skills: directories only
if "skills" in data:
    out = []
    for p in as_list(data["skills"]):
        r = rel(p)
        if not r:
            continue
        full = (root / r[2:]).resolve()
        if full.is_file():
            full = full.parent
        d = "./" if full == root else "./" + str(full.relative_to(root)) + "/"
        if d not in out:
            out.append(d)
    if out:
        data["skills"] = out
    else:
        data.pop("skills"); notes.append("skills: dropped (nothing valid)")

# Current schema accepts paths, inline event maps and mixed arrays.
# Preserve invalid definitions for `claude plugin validate` to report; never silently remove hooks.
if "hooks" in data:
    def normalize_hook(h):
        if isinstance(h, str):
            normalized = rel(h)
            return normalized if normalized and normalized.endswith(".json") else h
        if isinstance(h, dict) and set(h) == {"hooks"} and isinstance(h["hooks"], dict):
            return h["hooks"]  # legacy file-wrapper shape used inline
        return h
    h = data["hooks"]
    data["hooks"] = [normalize_hook(item) for item in h] if isinstance(h, list) else normalize_hook(h)

if json.dumps(data, sort_keys=True) != orig:
    mf.write_text(json.dumps(data, indent=2) + "\n")
    print(f"     normalized {mf.relative_to(root.parent)}" + (": " + "; ".join(notes) if notes else ""))
