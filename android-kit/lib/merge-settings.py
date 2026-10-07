#!/usr/bin/env python3
"""Merge local settings without removing unrelated hooks or permissions."""
import copy
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlparse

LEGACY = Path(__file__).with_name('legacy-settings-v1.json')
KIT_SCRIPT = re.compile(r'\.claude/kit/[\w.-]+')


def load(p):
    p = Path(p)
    if not p.exists():
        return {}
    value = json.loads(p.read_text())
    if not isinstance(value, dict):
        raise ValueError(f'expected settings object: {p}')
    return value


def save(p, value):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=p.name + '.', dir=p.parent)
    try:
        with os.fdopen(fd, 'w') as f:
            json.dump(value, f, indent=2, sort_keys=True); f.write('\n')
        os.replace(name, p)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def remove_hooks(cur, known):
    """Remove only exact known handlers; a user's handler in the same group survives."""
    hooks = cur.get('hooks', {})
    for event in list(hooks):
        kept = []
        for entry in hooks[event]:
            e = copy.deepcopy(entry)
            e['hooks'] = [h for h in e.get('hooks', []) if not any(
                e.get('matcher', '') == old.get('matcher', '') and h in old.get('hooks', [])
                for old in known.get('hooks', {}).get(event, []))]
            if e['hooks']:
                kept.append(e)
        if kept:
            hooks[event] = kept
        else:
            del hooks[event]
    if 'hooks' in cur and not hooks:
        del cur['hooks']


def combine(kit, cur):
    for k, vals in kit.get('permissions', {}).items():
        perm = cur.setdefault('permissions', {})
        if isinstance(vals, list):
            have = perm.setdefault(k, [])
            have += [v for v in vals if v not in have]
        else:
            perm.setdefault(k, vals)
    for event, entries in kit.get('hooks', {}).items():
        have = cur.setdefault('hooks', {}).setdefault(event, [])
        for entry in entries:
            if entry not in have:
                have.append(copy.deepcopy(entry))
    for k, v in kit.items():
        if k not in ('hooks', 'permissions'):
            cur.setdefault(k, v)
    return cur


def strip(path, kit_path=None, move_to=None):
    p = Path(path)
    if not p.exists():
        return
    cur = load(p); before = copy.deepcopy(cur)
    # With no template supplied, only remove empty plugin bookkeeping.
    if kit_path:
        kit = load(kit_path); legacy = load(LEGACY)
        remove_hooks(cur, legacy)
        remove_hooks(cur, kit)
        moved = {}
        for key, values in list(cur.get('permissions', {}).items()):
            if not isinstance(values, list):
                continue
            known = kit.get('permissions', {}).get(key, []) + legacy.get('permissions', {}).get(key, [])
            gone = [v for v in values if v in known]
            if gone:
                moved[key] = gone
                cur['permissions'][key] = [v for v in values if v not in gone]
                if not cur['permissions'][key]:
                    del cur['permissions'][key]
        if move_to and moved:
            save(move_to, combine({'permissions': moved}, load(move_to)))
    for key in ('permissions', 'enabledPlugins'):
        if key in cur and not cur[key]:
            del cur[key]
    if cur != before:
        if cur:
            save(p, cur)
        else:
            p.unlink()
        print('stripped')


def unvetted_hooks(kit, tpl_root, root):
    """Kit hook groups whose project script is missing, a symlink or not byte-identical to the template's."""
    found = {}
    for event, entries in kit.get('hooks', {}).items():
        for entry in entries:
            for h in entry.get('hooks', []):
                for rel in KIT_SCRIPT.findall(h.get('command', '')):
                    p, t = root / rel, tpl_root / rel
                    if not (p.is_file() and p.resolve() == root / rel and t.is_file() and p.read_bytes() == t.read_bytes()):
                        found.setdefault(event, []).append(entry)
                        print(f'warn hook not wired: {rel} is missing, a symlink or differs from the kit template; '
                              f'review it, then copy the template over it and rerun setup.sh')
    return {'hooks': found}


def merge(src, dst):
    cur = load(dst)
    kit = load(src)
    retired = load(LEGACY)
    # A hook runs the project's copy of its script: never wire one that differs from the kit's.
    unvetted = unvetted_hooks(kit, Path(src).resolve().parent.parent, Path(dst).resolve().parent.parent)
    remove_hooks(kit, unvetted)
    remove_hooks(cur, unvetted)
    # Unchanged handlers retain their position; only obsolete exact handlers are replaced.
    remove_hooks(retired, kit)
    remove_hooks(cur, retired)
    save(dst, combine(kit, cur))


def no_gateway(path):
    cur = load(path)
    cur.setdefault('enabledPlugins', {})['model-gateway@eigenwise-toolshed'] = False
    env = cur.get('env', {})
    inherited = load(Path.home() / '.claude/settings.json').get('env', {})
    shared = load(Path(path).with_name('settings.json')).get('env', {})
    url = urlparse(env.get('ANTHROPIC_BASE_URL', ''))
    if url.hostname in ('localhost', '127.0.0.1', '::1') and url.port == 18764:
        env.pop('ANTHROPIC_BASE_URL', None)
        print('ok removed project gateway URL')
    inherited_url = urlparse(shared.get('ANTHROPIC_BASE_URL', inherited.get('ANTHROPIC_BASE_URL', '')))
    if inherited_url.hostname in ('localhost', '127.0.0.1', '::1') and inherited_url.port == 18764:
        cur.setdefault('env', {}).setdefault('ANTHROPIC_BASE_URL', 'https://api.anthropic.com')
        print('warn inherited gateway URL overridden locally; shell environment still takes precedence')
    if 'env' in cur and not cur['env']:
        del cur['env']
    save(path, cur)


if __name__ == '__main__':
    try:
        if sys.argv[1] == '--strip-kit':
            strip(*sys.argv[2:])
        elif sys.argv[1] == '--no-gateway':
            no_gateway(sys.argv[2])
        else:
            merge(*sys.argv[1:])
    except (ValueError, OSError) as e:
        sys.exit(f'android-kit settings: {e}')
