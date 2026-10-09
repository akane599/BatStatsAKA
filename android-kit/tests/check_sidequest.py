#!/usr/bin/env python3
"""Optional real-CLI integration check: python3 tests/check_sidequest.py /path/to/sidequest.js"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

kit = Path(__file__).resolve().parents[1]
cli = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='sidequest-check-',dir=kit.parent) as tmp:
    root=Path(tmp); project=root/'project';project.mkdir()
    env=dict(os.environ,SIDEQUEST_HOME=str(root/'state'),SIDEQUEST_CLAUDE_HOME=str(root/'claude'),XDG_STATE_HOME=str(root/'xdg'),PYTHONDONTWRITEBYTECODE='1')
    def run(args):
        r=subprocess.run([str(a) for a in args],cwd=project,env=env,capture_output=True,text=True,timeout=90)
        if r.returncode: raise RuntimeError(r.stdout+'\n'+r.stderr)
        return r.stdout
    def sq(*args):return run(['node',cli,*args])
    def rp(*args):return run([sys.executable,kit/'lib/routing-profile.py',cli,kit/'lib/routing-profile.json',*args])
    run(['git','init','-q','-b','feature/kit-review'])
    print(rp('apply').strip())
    assert 'up to date' in rp('apply')
    sq('board-config','--integration-branch=feature/kit-review')
    print(rp('use',project).strip())
    profile=json.loads(sq('profile','show','android-kit','--json'))['profile']
    rows={x['id']:x for x in profile['categories']}
    assert rows['coding.hard.frontier']['route']['model']=='codex-gpt-6-astra'
    assert rows['coding.hard']['enabled'] is False
    assert rows['behavior-verification']['route']=={'model':'codex-gpt-6-1-sol','effort':'high'}
    assert rows['test-execution']['readonly'] is True
    models=json.loads(sq('models','--full','--json'))
    routes={x['id']:x for x in models['categories']}
    assert routes['coding.normal']['resolved']['model']=='sonnet',routes['coding.normal']
    sq('category','edit','coding.normal','--profile=android-kit','--route-model=sonnet','--route-effort=medium')
    assert 'left as is' in rp('apply')
    assert 'reset' in rp('apply','--reset')
    sq('profile','create','personal','--from=coding','--name=Personal')
    sq('profile','use','personal',f'--project={project}')
    assert "your profile 'personal', left as is" in rp('use',project)
    print('PASS: create, idempotence, board binding, Astra persistence, disabled stock tier, test split, Claude fallback, preserve user edit, explicit reset, preserve custom board profile')
