#!/usr/bin/env python3
"""Preflight, snapshots and hash-based template updates. Python 3.9+, stdlib only."""
import argparse
import datetime
import hashlib
import json
import shutil
from pathlib import Path

SKIP = {'CLAUDE.md', 'PROGRESS.md', 'gitignore.append', '.claude/settings.json', '.claude/.gitignore'}


def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def safe(root, rel):
    p = Path(rel)
    if p.is_absolute() or '..' in p.parts or not p.parts:
        raise ValueError(f'unsafe managed path: {rel}')
    dest = root / p
    for part in (dest, *dest.parents):
        if part == root:
            break
        if part.is_symlink():
            raise ValueError(f'refusing symlink in managed path: {part}')
    if dest.exists() and not dest.is_file():
        raise ValueError(f'expected file, found directory: {dest}')
    return dest


def inventory(root, tpl):
    rows = {str(p.relative_to(tpl)) for p in tpl.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
    rows -= {'gitignore.append'}
    rows |= {'.gitignore', 'CLAUDE.upstream.md', '.claude/settings.local.json',
             '.claude/kit/state.json', '.claude/kit/manifest.txt', '.claude/kit/manifest.json'}
    old = root / '.claude/kit/manifest.txt'
    safe(root, '.claude/kit/manifest.txt')
    if old.exists():
        for rel in old.read_text().splitlines():
            if rel and not rel.startswith('.claude/'):
                raise ValueError(f'old manifest contains non-kit path: {rel}')
            if rel:
                rows.add(rel)
    for rel in rows:
        safe(root, rel)
    for rel in ('.claude/settings.json', '.claude/settings.local.json', '.claude/kit/state.json', '.claude/kit/manifest.json'):
        p = root / rel
        if p.exists():
            data = json.loads(p.read_text())
            if not isinstance(data, dict):
                raise ValueError(f'expected JSON object: {rel}')
            if 'settings' in p.name:
                for section in ('hooks', 'permissions', 'enabledPlugins', 'env'):
                    if section in data and not isinstance(data[section], dict):
                        raise ValueError(f'{rel}: {section} must be an object')
                for event, entries in data.get('hooks', {}).items():
                    if not isinstance(entries, list) or any(not isinstance(e, dict) or not isinstance(e.get('hooks'), list) for e in entries):
                        raise ValueError(f'{rel}: malformed hook group {event}')
                for key in ('allow', 'deny', 'ask'):
                    entries = data.get('permissions', {}).get(key, [])
                    if not isinstance(entries, list) or any(not isinstance(e, str) for e in entries):
                        raise ValueError(f'{rel}: permissions.{key} must be a string array')
    return rows


def snapshot(root, tpl):
    rows = inventory(root, tpl)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    folder = root / '.claude/kit/backups' / stamp
    # Do not follow a project's backup directory symlink.
    safe(root, str(folder.relative_to(root) / 'snapshot.json'))
    folder.mkdir(parents=True, mode=0o700)
    existing, absent = [], []
    for rel in sorted(rows):
        src = root / rel
        if src.is_file():
            dst = folder / 'files' / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            existing.append(rel)
        else:
            absent.append(rel)
    (folder / 'snapshot.json').write_text(json.dumps({'existing': existing, 'absent': absent}, indent=2) + '\n')
    print('ok snapshot: ' + str(folder.relative_to(root)))


def sync(root, tpl):
    inventory(root, tpl)
    mp = root / '.claude/kit/manifest.json'
    previous = json.loads(mp.read_text()) if mp.exists() else {}
    legacy = root / '.claude/kit/manifest.txt'
    owned = set(legacy.read_text().splitlines()) if legacy.exists() else set()
    hashes, conflicts = {}, []
    incoming = root / '.claude/kit/incoming'
    current = {}
    for src in sorted(tpl.rglob('*')):
        if not src.is_file() or "__pycache__" in src.parts:
            continue
        rel = str(src.relative_to(tpl))
        if rel in SKIP:
            continue
        current[rel] = src
        dst = safe(root, rel)
        if dst.exists() and digest(dst) != digest(src) and digest(dst) != previous.get(rel):
            candidate = safe(root, str((incoming / rel).relative_to(root)))
            candidate.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, candidate)
            conflicts.append(rel)
            if rel in previous:
                hashes[rel] = previous[rel]
            print(f'warn kept modified/project file: {rel}; new version: .claude/kit/incoming/{rel}')
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        hashes[rel] = digest(src)
        candidate = safe(root, str((incoming / rel).relative_to(root)))
        if candidate.is_file():
            candidate.unlink()
    for rel in sorted(owned - set(current)):
        if not rel:
            continue
        dst = safe(root, rel)
        if not dst.exists():
            continue
        if previous.get(rel) != digest(dst):
            print(f'warn kept old customized file: {rel}; review manually')
            continue
        retired = root / '.claude/kit/retired' / (rel + '.' + digest(dst)[:12])
        safe(root, str(retired.relative_to(root)))
        retired.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(dst), str(retired))
    mp.parent.mkdir(parents=True, exist_ok=True)
    mp.write_text(json.dumps(hashes, indent=2, sort_keys=True) + '\n')
    legacy.write_text('\n'.join(sorted(hashes)) + '\n')
    conflict_file = root / '.claude/kit/conflicts.json'
    safe(root, str(conflict_file.relative_to(root)))
    conflict_file.write_text(json.dumps(conflicts, indent=2) + '\n')
    print(f'ok template sync: {len(hashes)} tracked, {len(conflicts)} manual merges')


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('action', choices=['check', 'snapshot', 'sync'])
    ap.add_argument('root', type=Path)
    ap.add_argument('template', type=Path)
    args = ap.parse_args()
    try:
        if args.action == 'check':
            inventory(args.root.resolve(), args.template.resolve())
        else:
            globals()[args.action](args.root.resolve(), args.template.resolve())
    except (ValueError, OSError) as e:
        ap.exit(1, f'android-kit: {e}\n')
