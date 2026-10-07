#!/usr/bin/env python3
"""
android-kit's Sidequest routing profile (spec: lib/routing-profile.json).

  routing-profile.py <sidequest.js> <spec.json> apply [--reset]
      create/refresh Sidequest profile 'android-kit' (machine-wide, in ~/.claude/sidequest).
      The kit owns the profile until you edit it: an edit (any revision the kit didn't write) is kept,
      and kit updates to the mapping are skipped until --reset.
  routing-profile.py <sidequest.js> <spec.json> use <project-dir>
      point the project's board at the profile, unless the board already uses a profile you chose.
  routing-profile.py <sidequest.js> <spec.json> check
      report which GPT routes Model Gateway can serve right now (the rest run on their Claude fallbacks).

Prints one status line per outcome, prefixed "ok " / "warn ".
"""
import hashlib, json, os, subprocess, sys
from pathlib import Path

STATE = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local/state") / "android-kit" / "routing-profile.json"


def sq(sq_js, *args, cwd=None):
    r = subprocess.run(["node", sq_js, *args], capture_output=True, text=True, cwd=cwd, timeout=45)
    return r.returncode, r.stdout, r.stderr


def sq_json(sq_js, *args, cwd=None):
    rc, out, err = sq(sq_js, *args, "--json", cwd=cwd)
    if rc != 0:
        return None
    try:
        return json.loads(out)
    except ValueError:
        return None


def say(kind, msg):
    print(f"{kind} {msg}")


def spec_hash(spec):
    return hashlib.sha256(json.dumps(spec, sort_keys=True).encode()).hexdigest()[:16]


def route(pair):
    return None if pair is None else {"model": pair[0], "effort": pair[1]}


def desired_categories(spec, base):
    """Full category rows: the base profile's row patched by the spec, or a new row from the spec alone."""
    by_id = {c["id"]: c for c in base["categories"]}
    out = []
    for cid, p in spec["categories"].items():
        b = dict(by_id.get(p.get("from", cid)) or {})   # "from": start from another base category's text
        if not b and not all(k in p for k in ("name", "description", "contract")):
            raise SystemExit(f"spec: new category {cid} needs name, description and contract (or 'from')")
        row = {
            "id": cid,
            "name": p.get("name", b.get("name", cid)),
            "description": p.get("description", b.get("description", "")),
            "route": route(p["route"]) if "route" in p else b.get("route"),
            "fallback": route(p["fallback"]) if "fallback" in p else b.get("fallback"),
            "contract": p.get("contract", b.get("contract", "")),
            "artifactRoots": p.get("artifactRoots", b.get("artifactRoots", [])),
            "readonly": p.get("readonly", b.get("readonly", False)) is True,
            "enabled": p.get("enabled", True) is not False,
        }
        if p.get("description_append"):
            row["description"] = (row["description"].rstrip() + " " + p["description_append"]).strip()
        if p.get("contract_append"):
            row["contract"] = (row["contract"].rstrip() + " " + p["contract_append"]).strip()
        out.append(row)
    return out


def same(cur, want):
    keys = ("name", "description", "route", "fallback", "contract", "artifactRoots", "readonly", "enabled")
    return cur is not None and all((cur.get(k) or None) == (want.get(k) or None) for k in keys)


def write_category(sq_js, pid, row):
    args = ["category", "add", row["id"], f"--profile={pid}", f"--name={row['name']}",
            f"--desc={row['description']}", f"--route-model={row['route']['model']}",
            f"--route-effort={row['route']['effort']}", f"--contract={row['contract']}",
            f"--readonly={'true' if row['readonly'] else 'false'}",
            f"--artifact-roots={','.join(row['artifactRoots']) or 'none'}"]
    if row["fallback"]:
        args += [f"--fallback-model={row['fallback']['model']}", f"--fallback-effort={row['fallback']['effort']}"]
    else:
        args += ["--no-fallback"]
    if not row["enabled"]:
        args += ["--disabled"]
    rc, out, err = sq(sq_js, *args)
    if rc != 0:
        raise SystemExit(f"sidequest {' '.join(args[:3])}: {(err or out).strip()[:300]}")


def apply(sq_js, spec, reset):
    meta = spec["profile"]
    pid, h = meta["id"], spec_hash(spec)
    state = json.loads(STATE.read_text()) if STATE.exists() else {}
    prof = (sq_json(sq_js, "profile", "show", pid) or {}).get("profile")
    base = (sq_json(sq_js, "profile", "show", meta["base"]) or {}).get("profile")
    if not base:
        return say("warn", f"Sidequest profile '{meta['base']}' not found; routing profile not applied")
    want = desired_categories(spec, base)

    if prof is None:
        rc, out, err = sq(sq_js, "profile", "create", pid, f"--from={meta['base']}", f"--name={meta['name']}",
                          f"--desc={meta['description']}")
        if rc != 0:
            return say("warn", f"could not create Sidequest profile {pid}: {(err or out).strip()[:200]}")
        prof = sq_json(sq_js, "profile", "show", pid)["profile"]
        action = "created"
    else:
        ours = state.get("profile") == pid and state.get("revision") == prof.get("revision")
        if not ours and not reset:
            if all(same(next((c for c in prof["categories"] if c["id"] == w["id"]), None), w) for w in want):
                ours = True   # identical to the kit's mapping (e.g. state file lost): adopt quietly
            else:
                return say("ok", f"routing profile {pid}: yours (edited or ownership unknown, r{prof.get('revision')}), left as is"
                           + ("; the kit's mapping changed, android-kit --reset-routing applies it" if state.get("spec") != h else ""))
        # Sidequest itself can change stored routes on load without bumping the revision (5.5 moves
        # Astra routes to GPT-6.1 Sol), so "ours and unchanged spec" still gets compared row by row.
        drift = [w["id"] for w in want if not same(next((c for c in prof["categories"] if c["id"] == w["id"]), None), w)]
        drift += [c["id"] for c in prof["categories"] if c["id"] in spec.get("retired", [])]
        if ours and state.get("spec") == h and not reset and not drift:
            return say("ok", f"routing profile {pid}: up to date (r{prof.get('revision')})")
        action = "reset" if reset else "updated" if state.get("spec") != h else "repaired"

    current = {c["id"]: c for c in prof["categories"]}
    changed = 0
    for rid in spec.get("retired", []):
        if rid in current:
            rc, out, err = sq(sq_js, "category", "rm", rid, f"--profile={pid}")
            if rc != 0:
                say("warn", f"could not remove retired category {rid}: {(err or out).strip()[:200]}")
    for row in want:
        if not same(current.get(row["id"]), row):
            write_category(sq_js, pid, row)
            changed += 1
    if prof.get("description") != meta["description"] or prof.get("name") != meta["name"]:
        sq(sq_js, "profile", "edit", pid, f"--name={meta['name']}", f"--desc={meta['description']}")
    prof = sq_json(sq_js, "profile", "show", pid)["profile"]   # a fresh process: Sidequest's load-time rewrites apply
    after = {c["id"]: c for c in prof["categories"]}
    stuck = [w for w in want if not same(after.get(w["id"]), w)]
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps({"profile": pid, "revision": prof.get("revision"), "spec": h}, indent=1) + "\n")
    say("ok", f"routing profile {pid}: {action} ({changed - len(stuck)} categories written, r{prof.get('revision')})")
    for w in stuck:
        got = (after.get(w["id"]) or {}).get("route") or {}
        say("warn", f"Sidequest changes {w['id']} to {got.get('model')}·{got.get('effort')} when it loads; the kit's "
                    f"{w['route']['model']}·{w['route']['effort']} can't stick (lib/routing-profile.json needs updating)")


def use(sq_js, spec, project):
    pid, base = spec["profile"]["id"], spec["profile"]["base"]
    cfg = sq_json(sq_js, "board-config", cwd=project)
    if not cfg:
        return say("warn", "Sidequest board not found; /bootstrap points it at the android-kit profile")
    cur = (cfg.get("profile") or {}).get("id")
    if cur == pid:
        return say("ok", f"Sidequest board uses profile {pid}")
    if cur not in (None, base):
        return say("ok", f"Sidequest board uses your profile '{cur}', left as is (switch: sidequest profile use {pid} --project .)")
    if sq_json(sq_js, "profile", "show", pid) is None:
        return say("warn", f"profile {pid} missing; board left on '{cur}'")
    rc, out, err = sq(sq_js, "profile", "use", pid, f"--project={project}", "--by=android-kit", cwd=project)
    if rc != 0:
        return say("warn", f"could not point the board at {pid}: {(err or out).strip()[:200]}")
    local = (cfg.get("overrides") or {}).get("count") or 0
    say("ok", f"Sidequest board → profile {pid}" + (f" (your {local} board-local categories stay on top)" if local else ""))


def check(sq_js, spec):
    models = sq_json(sq_js, "models") or {}
    have = set(models.get("models") or [])
    if not models:
        return say("warn", "Sidequest model catalog could not be read; availability is unknown, not verified")
    gpt = sorted({p[k][0] for p in spec["categories"].values() for k in ("route", "fallback")
                  if p.get(k) and not p[k][0] in ("haiku", "sonnet", "opus", "fable")})
    missing = [s for s in gpt if s not in have]
    if not missing:
        return say("ok", "GPT routes live: " + ", ".join(gpt))
    if len(missing) == len(gpt):
        return say("warn", "GPT routes not live yet (gateway not signed in, or not serving): those tickets run on their Claude fallbacks")
    say("warn", "Model Gateway doesn't list " + ", ".join(missing) + " yet; those tickets run on their Claude fallbacks."
        " Re-run android-kit (gateway setup fetches the latest claude-code-proxy) or /quartermaster:update-toolshed")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    sq_js, spec = sys.argv[1], json.loads(Path(sys.argv[2]).read_text())
    cmd = sys.argv[3]
    if cmd == "apply":
        apply(sq_js, spec, "--reset" in sys.argv[4:])
    elif cmd == "use":
        use(sq_js, spec, sys.argv[4])
    elif cmd == "check":
        check(sq_js, spec)
    else:
        sys.exit(__doc__)
