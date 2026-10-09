#!/usr/bin/env python3
"""Read-only CLI preflight: reject broken launchers before touching machine state."""
import re
import subprocess
import sys


def check(binary, *args):
    try:
        r = subprocess.run([binary, *args], capture_output=True, text=True, timeout=45)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise RuntimeError(f"Claude command failed to start/finish: {binary} {' '.join(args)}\n{e}") from e
    output = '\n'.join(s.strip() for s in (r.stdout, r.stderr) if s.strip())
    if r.returncode:
        raise RuntimeError(f"Claude command failed (exit {r.returncode}): {binary} {' '.join(args)}\n{output or '(no output)'}")
    return output


def main():
    binary, fixture = sys.argv[1:]
    version = check(binary, '--version')
    if not re.search(r'\b\d+\.\d+\.\d+\b', version):
        raise RuntimeError(f"Claude --version returned no recognizable version: {binary}\n{version or '(empty output)'}\nCheck the executable or wrapper; a command existing on PATH does not prove it works.")
    print(f'Claude executable: {binary}', flush=True)
    print(version, flush=True)
    # Bundled local plugin: no marketplace, network or account needed for schema validation.
    validation = check(binary, 'plugin', 'validate', fixture)
    if not validation.strip():
        raise RuntimeError('Claude plugin validate returned empty output. Check that the launcher forwards its arguments.')
    print('Bundled plugin validation: passed', flush=True)


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
