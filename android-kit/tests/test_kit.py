#!/usr/bin/env python3
"""Offline regression suite. Never installs plugins or modifies user settings."""
import ast
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

KIT = Path(__file__).resolve().parents[1]
SCRIPTS = KIT / 'template/.claude/kit'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name,path)
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

merge = load('merge', KIT/'lib/merge-settings.py')
ui = load('ui', SCRIPTS/'ui-guard.py')


class KitTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='kit-test-', dir=KIT.parent)
        self.root = Path(self.temp.name)
        self.project = self.root/'project space'; self.project.mkdir()
        self.env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(self.root))

    def tearDown(self):
        self.temp.cleanup()

    def run_cmd(self, argv, input=None, cwd=None, ok=True, env=None):
        r = subprocess.run([str(x) for x in argv], input=input, cwd=cwd or self.project,
                           env=env or self.env, capture_output=True,text=True,timeout=50)
        if ok: self.assertEqual(r.returncode,0,r.stdout+'\n'+r.stderr)
        return r

    def setup(self,*args,ok=True):
        return self.run_cmd(['bash',KIT/'setup.sh','--no-plugins',*args,str(self.project)],ok=ok)

    def test_syntax(self):
        for p in KIT.rglob('*.py'): ast.parse(p.read_text(),filename=str(p))
        for p in KIT.rglob('*.sh'): self.run_cmd(['bash','-n',p])
        for p in KIT.rglob('*.json'): json.loads(p.read_text())

    def test_offline_setup_and_idempotence(self):
        self.setup()
        p=self.project/'.claude/settings.local.json'; first=p.read_text()
        self.setup(); self.assertEqual(first,p.read_text())
        self.assertEqual(json.loads((self.project/'.claude/kit/state.json').read_text())['mode'],'new')

    def test_dry_run_writes_nothing(self):
        self.run_cmd(['bash',KIT/'setup.sh','--dry-run',str(self.project)])
        self.assertEqual(list(self.project.iterdir()),[])

    def test_local_only_persists(self):
        self.setup('--local-only'); self.assertFalse((self.project/'.gitignore').exists())
        self.setup(); self.assertFalse((self.project/'.gitignore').exists())
        status=self.run_cmd(['git','status','--porcelain']).stdout
        self.assertEqual(status,'')

    def test_existing_upstream_no_data_loss(self):
        (self.project/'CLAUDE.md').write_text('valuable current instructions')
        (self.project/'CLAUDE.upstream.md').write_text('older upstream')
        self.setup()
        backups=list((self.project/'.claude/kit/backups').glob('*/files/CLAUDE.md'))
        self.assertEqual(backups[0].read_text(),'valuable current instructions')
        self.assertEqual((self.project/'CLAUDE.upstream.md').read_text(),'older upstream')

    def test_modified_template_preserved(self):
        self.setup(); p=self.project/'.claude/commands/bug-hunt.md'; p.write_text('my custom behavior')
        self.setup(); self.assertEqual(p.read_text(),'my custom behavior')
        self.assertTrue((self.project/'.claude/kit/incoming/.claude/commands/bug-hunt.md').is_file())
        self.assertIn('.claude/commands/bug-hunt.md',json.loads((self.project/'.claude/kit/conflicts.json').read_text()))

    def test_legacy_manifest_without_hash_preserves_edits(self):
        self.setup(); (self.project/'.claude/kit/manifest.json').unlink()
        p=self.project/'.claude/commands/bug-hunt.md'; p.write_text('legacy custom')
        self.setup(); self.assertEqual(p.read_text(),'legacy custom')

    def test_bad_json_fails_before_changes(self):
        (self.project/'.claude').mkdir(); (self.project/'.claude/settings.local.json').write_text('{broken')
        r=self.setup(ok=False); self.assertNotEqual(r.returncode,0)
        self.assertFalse((self.project/'CLAUDE.md').exists()); self.assertFalse((self.project/'.git').exists())

    def test_manifest_traversal_rejected(self):
        (self.project/'.claude/kit').mkdir(parents=True)
        (self.project/'.claude/kit/manifest.txt').write_text('.claude/../../victim\n')
        self.assertNotEqual(self.setup(ok=False).returncode,0)

    def test_symlink_rejected(self):
        victim=self.root/'victim'; victim.write_text('keep')
        (self.project/'CLAUDE.md').symlink_to(victim)
        self.assertNotEqual(self.setup(ok=False).returncode,0)
        self.assertEqual(victim.read_text(),'keep')

    def test_subdirectory_rejected(self):
        self.run_cmd(['git','init','-q'])
        child=self.project/'nested'; child.mkdir()
        r=self.run_cmd(['bash',KIT/'setup.sh','--no-plugins',child],ok=False)
        self.assertNotEqual(r.returncode,0); self.assertFalse((child/'CLAUDE.md').exists())

    def test_worktree_git_file(self):
        self.run_cmd(['git','init','-q'])
        self.run_cmd(['git','-c','user.name=Kit Test','-c','user.email=kit@example.invalid','commit','--allow-empty','-m','fixture'])
        wt=self.root/'work tree'
        self.run_cmd(['git','worktree','add','-q','-b','test-wt',wt])
        self.assertTrue((wt/'.git').is_file())
        local_kit=wt/'android-kit'; shutil.copytree(KIT,local_kit,ignore=shutil.ignore_patterns('__pycache__'))
        self.run_cmd(['bash',local_kit/'setup.sh','--no-plugins','--local-only',wt],cwd=wt)
        self.assertEqual(self.run_cmd(['git','status','--porcelain'],cwd=wt).stdout,'')

    def test_missing_with_argument(self):
        self.assertNotEqual(self.run_cmd(['bash',KIT/'setup.sh','--with'],ok=False).returncode,0)

    def test_unknown_extra_fails(self):
        self.assertNotEqual(self.setup('--with','made-up',ok=False).returncode,0)

    def test_settings_mixed_hook_survives(self):
        kit=merge.load(KIT/'template/.claude/settings.json')
        old=merge.load(KIT/'lib/legacy-settings-v1.json')
        custom={'type':'command','command':'echo custom PROGRESS.md'}
        old['hooks']['PreCompact'][0]['hooks'].append(custom)
        p=self.project/'settings.json'; p.write_text(json.dumps(old)); local=self.project/'local.json'
        merge.strip(p,KIT/'template/.claude/settings.json',local)
        saved=merge.load(p)
        self.assertEqual(saved['hooks']['PreCompact'][0]['hooks'],[custom])
        merge.merge(KIT/'template/.claude/settings.json',local)
        self.assertEqual(merge.load(local)['model'],'opus')

    def test_settings_preserve_model_and_other_matcher(self):
        p=self.project/'settings.json'
        p.write_text(json.dumps({'model':'sonnet','effortLevel':'medium','permissions':{'allow':['Bash(echo:*)']},'hooks':{'PostToolUse':[{'matcher':'Bash','hooks':[{'type':'command','command':'echo custom'}]}]}}))
        merge.merge(KIT/'template/.claude/settings.json',p); first=p.read_text()
        merge.merge(KIT/'template/.claude/settings.json',p)
        self.assertEqual(p.read_text(),first); self.assertEqual(merge.load(p)['model'],'sonnet')
        self.assertIn('Bash(echo:*)',merge.load(p)['permissions']['allow'])

    def test_detection_empty(self):
        r=self.run_cmd(['bash',SCRIPTS/'detect-stack.sh']); values=dict(x.split('=',1) for x in r.stdout.splitlines())
        for key in ('di','db','network','async','screenshot_testing'): self.assertEqual(values[key],'none')
        self.assertEqual(values['primary_language'],'unknown')

    def test_detection_real_vs_build_cache(self):
        (self.project/'settings.gradle').write_text("rootProject.name = 'Legacy'\ninclude ':mobile', ':core'\n")
        (self.project/'build.gradle').write_text("implementation 'io.insert-koin:koin-android:1'\nimplementation 'app.cash.paparazzi:paparazzi:1'\n")
        (self.project/'build').mkdir();(self.project/'build/cache.gradle').write_text('hilt-android androidx.room com.android.compose.screenshot')
        r=self.run_cmd(['bash',SCRIPTS/'detect-stack.sh']); values=dict(x.split('=',1) for x in r.stdout.splitlines())
        self.assertEqual(values['di'],'koin'); self.assertEqual(values['db'],'none'); self.assertEqual(values['screenshot_testing'],'paparazzi')
        self.assertEqual(values['modules'],':mobile :core')

    def test_gradle_fail_preserves_code(self):
        (self.project/'gradlew').write_text('#!/bin/sh\necho actual failure\nexit 23\n')
        r=self.run_cmd(['bash',SCRIPTS/'gradle-check.sh',':app:test'],ok=False)
        self.assertEqual(r.returncode,23); self.assertIn('actual failure',r.stdout)
        self.assertEqual(len(list((self.project/'.claude/kit/logs').glob('*.log'))),1)

    def test_gradle_success(self):
        (self.project/'gradlew').write_text('#!/bin/sh\nexit 0\n')
        self.assertIn('PASS',self.run_cmd(['bash',SCRIPTS/'gradle-check.sh',':app:test']).stdout)

    def hook(self,agent,path,content='',tool='Write',command=''):
        payload={'agent_type':agent,'cwd':str(self.project),'tool_name':tool,'tool_input':{'file_path':path,'content':content,'command':command}}
        r=self.run_cmd([sys.executable,SCRIPTS/'ui-guard.py'],input=json.dumps(payload))
        return json.loads(r.stdout) if r.stdout.strip() else {}

    def test_ui_namespace_and_resources(self):
        d=self.hook('sidequest:sidequest-exec-dispatch','app/src/main/res/layout/test.xml')
        self.assertEqual(d['hookSpecificOutput']['permissionDecision'],'deny')
        self.assertEqual(self.hook('sidequest:sidequest-exec-high','app/src/main/res/layout/test.xml'),{})

    def test_ui_relative_existing_file(self):
        (self.project/'Screen.kt').write_text('@Composable fun Test() {}')
        self.assertTrue(self.hook('sidequest:sidequest-exec-dispatch','Screen.kt','// changed'))
        self.assertEqual(self.hook('sidequest:sidequest-exec-dispatch','Repository.kt','val a = 1'),{})

    def test_ui_screenshot_update(self):
        self.assertTrue(self.hook('sidequest-exec-model-codex-gpt-6-luna-medium','',tool='Bash',command='./gradlew updateDemoDebugScreenshotTest'))
        self.assertEqual(self.hook('sidequest-exec-dispatch','',tool='Bash',command='./gradlew validateDebugScreenshotTest'),{})

    def scaffold(self,*flags,ok=True):
        return self.run_cmd([sys.executable,SCRIPTS/'scaffold.py','--name','R&D "Test"','--package','com.example.demo','--offline',*flags],ok=ok)

    def test_scaffold_offline(self):
        r=self.scaffold(); self.assertIn('not generated',json.loads(r.stdout)['wrapper'])
        ET.parse(self.project/'app/src/main/res/values/strings.xml')
        self.assertFalse((self.project/'gradlew').exists())
        self.assertTrue((self.project/'app/src/main/kotlin/com/example/demo/MainActivity.kt').is_file())

    def test_scaffold_dry_run(self):
        self.scaffold('--dry-run'); self.assertEqual(list(self.project.iterdir()),[])

    def test_scaffold_reject_sdk(self):
        self.assertNotEqual(self.scaffold('--min-sdk','99',ok=False).returncode,0)
        self.assertEqual(list(self.project.iterdir()),[])

    def test_scaffold_collision_atomic(self):
        (self.project/'build.gradle.kts').write_text('// preserve')
        self.assertNotEqual(self.scaffold(ok=False).returncode,0)
        self.assertEqual((self.project/'build.gradle.kts').read_text(),'// preserve')
        self.assertFalse((self.project/'settings.gradle.kts').exists())

    def test_checkpoint_session_isolation(self):
        transcript=self.root/'transcript.jsonl'
        transcript.write_text(json.dumps({'type':'user','message':{'content':'keep this instruction'}})+'\n')
        payload={'session_id':'session-a','cwd':str(self.project),'transcript_path':str(transcript)}
        self.run_cmd([sys.executable,SCRIPTS/'compact-checkpoint.py'],input=json.dumps(payload))
        r=self.run_cmd([sys.executable,SCRIPTS/'compact-checkpoint.py','--restore'],input=json.dumps(payload))
        self.assertIn('keep this instruction',r.stdout)
        payload['session_id']='session-b'
        self.assertEqual(self.run_cmd([sys.executable,SCRIPTS/'compact-checkpoint.py','--restore'],input=json.dumps(payload)).stdout,'')

    def test_manifest_hook_array_preserved(self):
        p=self.project/'.claude-plugin';p.mkdir()
        manifest={'name':'test','hooks':[{'PreToolUse':[{'hooks':[{'type':'command','command':'echo safe'}]}]}]}
        (p/'plugin.json').write_text(json.dumps(manifest))
        self.run_cmd([sys.executable,KIT/'lib/normalize-plugin-manifest.py',self.project])
        self.assertEqual(json.loads((p/'plugin.json').read_text())['hooks'],manifest['hooks'])


    def test_plugin_failures_are_reported_and_scope_is_local(self):
        installed=self.root/'installed-kit'
        shutil.copytree(KIT,installed,ignore=shutil.ignore_patterns('__pycache__'))
        (installed/'.machine-done').write_text('2026.10.03.3\n')
        mp=self.root/'marketplace'; (mp/'.claude-plugin').mkdir(parents=True)
        plugin=mp/'plugins/fixture/.claude-plugin'; plugin.mkdir(parents=True)
        (plugin/'plugin.json').write_text('{"name":"fixture"}')
        (mp/'.claude-plugin/marketplace.json').write_text(json.dumps({'plugins':[{'source':'./plugins/fixture'}]}))
        bindir=self.root/'bin';bindir.mkdir()
        fake=bindir/'claude'
        fake.write_text('#!/usr/bin/env python3\nimport sys,os,json\na=sys.argv[1:]\nwith open(os.environ["KIT_TEST_LOG"],"a") as f: f.write(json.dumps(a)+"\\n")\nif a==["--version"]: print("2.1.300 (Claude Code fixture)")\nelif a[:2]==["plugin","validate"]: print("Validation passed")\nelif a==["plugin","marketplace","list"]: print("android-local")\nelif a==["plugin","list","--json"]: print("[]")\nelif a[:2]==["plugin","install"] and a[2].startswith("sidequest@"): sys.exit(1)\n')
        fake.chmod(0o755)
        log=self.root/'calls.jsonl'
        env=dict(self.env,PATH=str(bindir)+os.pathsep+self.env['PATH'],ANDROID_KIT_HOME=str(installed),CLAUDE_LOCAL_MARKETPLACE=str(mp),KIT_TEST_LOG=str(log))
        r=self.run_cmd(['bash',KIT/'setup.sh','--no-lsp-install','--no-migrate',self.project],env=env,ok=False)
        self.assertEqual(r.returncode,3,r.stdout+r.stderr)
        state=json.loads((self.project/'.claude/kit/state.json').read_text())
        self.assertEqual(state['failed_plugins'],['sidequest@eigenwise-toolshed'])
        calls=[json.loads(x) for x in log.read_text().splitlines()]
        for call in calls:
            if call[:2]==['plugin','install']: self.assertEqual(call[-2:],['--scope','local'])
        self.assertNotIn(['plugin','marketplace','add'],[x[:3] for x in calls])

    def test_map_guard_matches_only_map_root(self):
        payload={'tool_name':'Agent','tool_input':{'subagent_type':'sidequest:sidequest-exec-dispatch-readonly','prompt':'Declared files:\n- .claude/.codebase-information/file.md\n'}}
        script=SCRIPTS/'map-guard.py'
        self.assertEqual(self.run_cmd([sys.executable,script],input=json.dumps(payload)).stdout,'')
        payload['tool_input']['prompt']='Declared files:\n- .claude/.codebase-info/\n'
        out=self.run_cmd([sys.executable,script],input=json.dumps(payload)).stdout
        self.assertEqual(json.loads(out)['hookSpecificOutput']['permissionDecision'],'deny')

    def test_config_rejects_path_injection(self):
        conf=self.root/'plugins.conf';conf.write_text('../escape@android-local android local:bundle\n')
        self.assertNotEqual(self.run_cmd([sys.executable,KIT/'lib/validate-config.py',conf],ok=False).returncode,0)


    def cli_fixture(self, source):
        fake = self.root / 'claude wrapper'
        fake.write_text('#!/usr/bin/env python3\nimport sys\n' + source)
        fake.chmod(0o755)
        return fake

    def test_cli_empty_version_stops_before_machine_changes(self):
        fake = self.cli_fixture('sys.exit(0)\n')
        installed = self.root/'never-installed'
        env = dict(self.env, ANDROID_KIT_CLAUDE_BIN=str(fake), ANDROID_KIT_HOME=str(installed), CLAUDE_LOCAL_MARKETPLACE=str(self.root/'mp'))
        r = self.run_cmd(['bash',KIT/'setup.sh',self.project],env=env,ok=False)
        self.assertNotEqual(r.returncode,0)
        self.assertIn('no recognizable version',r.stdout+r.stderr)
        self.assertFalse(installed.exists())
        self.assertFalse((self.project/'.git').exists())

    def test_cli_failure_exposes_stderr_and_status(self):
        fake = self.cli_fixture('print("launcher execution failure",file=sys.stderr)\nsys.exit(127)\n')
        r = self.run_cmd([sys.executable,KIT/'lib/check-claude.py',fake,KIT/'lib/kotlin-lsp-android'],ok=False)
        self.assertNotEqual(r.returncode,0)
        self.assertIn('exit 127',r.stderr)
        self.assertIn('launcher execution failure',r.stderr)

    def test_cli_validation_error_not_hidden(self):
        fake = self.cli_fixture('if sys.argv[1:] == ["--version"]: print("2.1.300 (Claude Code)")\nelse:\n print("Unknown command validate",file=sys.stderr)\n sys.exit(2)\n')
        r = self.run_cmd([sys.executable,KIT/'lib/check-claude.py',fake,KIT/'lib/kotlin-lsp-android'],ok=False)
        self.assertNotEqual(r.returncode,0)
        self.assertIn('Unknown command validate',r.stderr)

    def test_cli_version_on_stderr_is_accepted(self):
        fake = self.cli_fixture('if sys.argv[1:] == ["--version"]: print("2.1.300 (Claude Code)",file=sys.stderr)\nelse: print("Validation passed")\n')
        r = self.run_cmd([sys.executable,KIT/'lib/check-claude.py',fake,KIT/'lib/kotlin-lsp-android'])
        self.assertIn('Bundled plugin validation: passed',r.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
