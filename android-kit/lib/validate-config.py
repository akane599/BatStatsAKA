#!/usr/bin/env python3
"""Check editable plugin IDs and clone paths before shell/cache operations."""
import re
import sys
from pathlib import Path

seen=set()
for num,line in enumerate(Path(sys.argv[1]).read_text().splitlines(),1):
    cols=line.partition('#')[0].split()
    if not cols: continue
    def fail(msg): sys.exit(f'plugins.conf:{num}: {msg}')
    if not 2 <= len(cols) <= 4: fail('expected id tier [source] [commit]')
    pid,tier=cols[:2]
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]*@[a-z0-9][a-z0-9-]*',pid): fail('invalid plugin ID')
    if pid in seen: fail('duplicate plugin ID')
    seen.add(pid)
    if tier not in ('base','android','java','extra','retired'): fail('invalid tier')
    if len(cols)>2:
        source=cols[2]
        if not (re.fullmatch(r'local:[a-z0-9][a-z0-9-]*',source) or re.fullmatch(r'https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?',source)):
            fail('source must be a local bundle name or HTTPS GitHub repository')
    if len(cols)>3 and cols[3] != 'main' and not re.fullmatch(r'[a-fA-F0-9]{40}',cols[3]): fail('pin must be a full commit SHA or explicit main')
