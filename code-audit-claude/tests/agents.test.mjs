import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { parseArgs, installAgents, prepare, cleanup, validateBundle, renderAgent } from '../skills/code-audit/scripts/agents.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
function temp(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'code-audit-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}
function settings(file) {
  const [_, block] = fs.readFileSync(file, 'utf8').split('---');
  return Object.fromEntries(block.trim().split('\n').map(line => {
    const colon = line.indexOf(':');
    const value = line.slice(colon + 1).trim();
    return [line.slice(0, colon), value.startsWith('"') ? JSON.parse(value) : value];
  }));
}

test('default plan includes all eight distinct specialists with inherited settings', () => {
  const p = parseArgs();
  assert.equal(p.agents.length, 8);
  assert.equal(new Set(p.agents.map(a => a.role)).size, 8);
  assert.ok(p.agents.every(a => a.model === 'inherit' && a.effort === 'inherit'));
  assert.equal(p.parallel, 4);
});
test('individual model and effort overrides are independent', () => {
  const p = parseArgs(['--model', 'sonnet', '--effort', 'medium', '--agent-model', 'bugs=opus', '--agent-effort', 'tests=high']);
  assert.deepEqual(p.agents.find(a => a.role === 'bugs'), { role: 'bugs', model: 'opus', effort: 'medium' });
  assert.deepEqual(p.agents.find(a => a.role === 'tests'), { role: 'tests', model: 'sonnet', effort: 'high' });
});
test('-all wins in either order and preserves individual effort', () => {
  const override = ['--agent-model', 'comments=haiku', '--agent-effort', 'comments=low'];
  for (const args of [['-all', 'opus', ...override], [...override, '--all=opus']]) {
    const p = parseArgs(args);
    assert.ok(p.agents.every(a => a.model === 'opus'));
    assert.equal(p.agents.find(a => a.role === 'comments').effort, 'low');
    assert.equal(p.notices.length, 1);
  }
});
test('subset, sequential scheduling, paths and symptoms survive literal parsing', () => {
  const p = parseArgs(['debug', 'src/two words', '--symptom', 'refresh fails', '--only', 'bugs,errors', '--parallel', '1', '--', '-unusual-file']);
  assert.equal(p.mode, 'debug');
  assert.deepEqual(p.targets, ['src/two words', '-unusual-file']);
  assert.equal(p.symptom, 'refresh fails');
  assert.equal(p.agents.length, 2);
  assert.equal(p.parallel, 1);
});
test('provider IDs and context suffixes survive', () => {
  for (const value of ['claude-opus-4-6[1m]', 'arn:aws:bedrock:us-east-1:123:inference-profile/test']) {
    assert.ok(parseArgs(['-all', value]).agents.every(a => a.model === value));
  }
});
test('invalid requests fail instead of silently dropping requirements', () => {
  for (const args of [
    ['--only', 'bugs'], ['--only', 'bugs,bugs'], ['--only', 'bugs,unknown'],
    ['--effort', 'ultra'], ['--parallel', '0'], ['--parallel', '2.5'],
    ['--parallel', '9'], ['--agent-model', 'typo=opus'], ['--agent-model', 'bugs='],
    ['--only', 'bugs,tests', '--agent-effort', 'comments=low'], ['--effort'],
    ['--model', '--effort', 'high'], ['--base', 'main', '--pr', '123'],
    ['-all', 'opus', '--all', 'sonnet'], ['--unknown'], ['--model', 'bad model'],
    ['--agent-effort', 'bugs=high', '--agent-effort', 'bugs=low']
  ]) assert.throws(() => parseArgs(args), undefined, JSON.stringify(args));
  assert.throws(() => parseArgs('raw shell text'));
});
test('dispatch uses profiles registered at startup with model overrides on the invocation', t => {
  const dir = temp(t);
  installAgents(dir);
  const registry = new Set(fs.readdirSync(dir).map(file => file.slice(0, -3)));
  const before = Object.fromEntries(fs.readdirSync(dir).map(file => [file, fs.readFileSync(path.join(dir, file), 'utf8')]));
  const p = prepare(['--only', 'bugs,tests', '-all', 'opus', '--effort', 'high', '--agent-effort', 'tests=medium'], dir, {});
  for (const agent of p.agents) {
    assert.ok(registry.has(agent.subagent_type), 'Agent must be in the startup registry');
    const actual = settings(path.join(dir, agent.subagent_type + '.md'));
    assert.equal(actual.name, agent.subagent_type);
    assert.equal(actual.model, 'inherit');
    assert.equal(actual.effort, agent.role === 'tests' ? 'medium' : 'high');
    assert.equal(actual.tools, 'Read, Glob, Grep, Bash');
    assert.ok(!('permissionMode' in actual));
    assert.deepEqual(agent.invocation, { subagent_type: agent.subagent_type, model: 'opus' });
  }
  assert.equal(registry.has('code-audit-3260ef7c30022200-bugs'), false);
  assert.equal(p.runId, undefined);
  assert.deepEqual(Object.fromEntries(fs.readdirSync(dir).map(file => [file, fs.readFileSync(path.join(dir, file), 'utf8')])), before);
});
test('inherit effort uses the permanent base reviewer and omits model overrides', t => {
  const dir = temp(t);
  installAgents(dir);
  const p = prepare(['--only', 'types,quality'], dir, {});
  for (const a of p.agents) {
    assert.equal(a.subagent_type, 'code-audit-' + a.role);
    assert.ok(!('effort' in settings(path.join(dir, a.subagent_type + '.md'))));
    assert.deepEqual(a.invocation, { subagent_type: a.subagent_type });
  }
});
test('simultaneous reviews have independent invocation settings and do not mutate profiles', t => {
  const dir = temp(t);
  installAgents(dir);
  const before = fs.readdirSync(dir);
  const one = prepare(['-all', 'opus', '--effort', 'high'], dir, {});
  const two = prepare(['-all', 'sonnet', '--effort', 'high'], dir, {});
  assert.ok(one.agents.every(a => a.invocation.model === 'opus'));
  assert.ok(two.agents.every(a => a.invocation.model === 'sonnet'));
  assert.deepEqual(one.agents.map(a => a.subagent_type), two.agents.map(a => a.subagent_type));
  assert.deepEqual(fs.readdirSync(dir), before);
  for (const a of one.agents) assert.equal(settings(path.join(dir, a.subagent_type + '.md')).model, 'inherit');
});
function legacyRun(dir, runId = '0123456789abcdef') {
  for (const role of ['bugs', 'tests']) {
    const name = 'code-audit-' + runId + '-' + role;
    fs.writeFileSync(path.join(dir, name + '.md'), renderAgent({ role, model: 'inherit', effort: 'inherit' }, name, runId));
  }
  return runId;
}
test('legacy cleanup is scoped and idempotent and never deletes permanent reviewers', t => {
  const dir = temp(t);
  installAgents(dir);
  const one = legacyRun(dir);
  const two = legacyRun(dir, 'abcdef0123456789');
  assert.equal(cleanup(one, dir).removed, 2);
  assert.equal(cleanup(one, dir).removed, 0);
  assert.ok(fs.existsSync(path.join(dir, 'code-audit-' + two + '-bugs.md')));
  assert.ok(fs.existsSync(path.join(dir, 'code-audit-bugs.md')));
  assert.equal(fs.readdirSync(dir).length, 50);
});
test('legacy cleanup rejects traversal and unrelated content before deleting anything', t => {
  const dir = temp(t);
  assert.throws(() => cleanup('../../other', dir));
  const runId = legacyRun(dir);
  fs.writeFileSync(path.join(dir, 'code-audit-' + runId + '-bugs.md'), 'User-owned content\n');
  assert.throws(() => cleanup(runId, dir), /Refusing/);
  assert.equal(fs.readdirSync(dir).length, 2);
});
test('legacy cleanup refuses symlinks without deleting the target', t => {
  const dir = temp(t);
  const runId = legacyRun(dir);
  const file = path.join(dir, 'code-audit-' + runId + '-bugs.md');
  const target = path.join(dir, 'unrelated.md');
  fs.writeFileSync(target, 'keep');
  fs.unlinkSync(file);
  fs.symlinkSync(target, file);
  assert.throws(() => cleanup(runId, dir), /Refusing/);
  assert.equal(fs.readFileSync(target, 'utf8'), 'keep');
});
test('agent installation preserves different existing files before any writes', t => {
  const dir = temp(t);
  fs.writeFileSync(path.join(dir, 'code-audit-security.md'), 'custom');
  assert.throws(() => installAgents(dir), /Refusing/);
  assert.equal(fs.readdirSync(dir).length, 1);
});
test('help creates no profiles and prepare reports missing installation', t => {
  const dir = path.join(temp(t), 'missing');
  assert.equal(prepare(['--help'], dir, {}).help, true);
  assert.ok(!fs.existsSync(dir));
  assert.throws(() => prepare([], dir, {}), /missing/);
});
test('environment overrides are disclosed', t => {
  const dir = temp(t);
  installAgents(dir);
  const p = prepare([], dir, { CLAUDE_CODE_SUBAGENT_MODEL_FORCE: '1', CLAUDE_CODE_EFFORT_LEVEL: 'low' });
  assert.equal(p.notices.length, 2);
});
test('bundle validates and installed helper works from a path with spaces', t => {
  assert.equal(validateBundle().valid, true);
  const dest = path.join(temp(t), 'config with spaces');
  const install = () => spawnSync(process.execPath, [path.join(root, 'install.mjs'), '--config-dir', dest], { encoding: 'utf8' });
  const first = install();
  assert.equal(first.status, 0, first.stderr);
  assert.equal(JSON.parse(first.stdout).specialists, 8);
  assert.equal(JSON.parse(first.stdout).agents, 48);
  const second = install();
  assert.equal(second.status, 0, second.stderr);
  assert.equal(JSON.parse(second.stdout).filesCreated, 0);
  const request = path.join(dest, 'request.json');
  fs.writeFileSync(request, JSON.stringify({ args: ['--only', 'bugs,tests', '-all', 'opus', '--effort', 'high'] }));
  const script = path.join(dest, 'skills/code-audit/scripts/agents.mjs');
  const run = spawnSync(process.execPath, [script, 'prepare', '--request', request], { encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
  const p = JSON.parse(run.stdout);
  assert.equal(p.agentsDirectory, path.join(dest, 'agents'));
  assert.equal(p.registration, 'preinstalled-or-startup');
  assert.equal(fs.readdirSync(path.join(dest, 'agents')).length, 48);
});
test('installer does not partly overwrite an existing skill', t => {
  const dest = path.join(temp(t), 'config');
  fs.mkdirSync(path.join(dest, 'agents'), { recursive: true });
  fs.writeFileSync(path.join(dest, 'agents/code-audit-types.md'), 'custom');
  const result = spawnSync(process.execPath, [path.join(root, 'install.mjs'), '--config-dir', dest], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.ok(!fs.existsSync(path.join(dest, 'skills')));
  assert.equal(fs.readFileSync(path.join(dest, 'agents/code-audit-types.md'), 'utf8'), 'custom');
});

test('installer rejects a missing or flag-shaped destination without writing', t => {
  const dir = temp(t);
  for (const args of [['--config-dir', '--project'], ['--project', ''], ['--config-dir', '  ']]) {
    const result = spawnSync(process.execPath, [path.join(root, 'install.mjs'), ...args], { cwd: dir, encoding: 'utf8' });
    assert.equal(result.status, 1, result.stdout);
    assert.equal(fs.readdirSync(dir).length, 0);
  }
});

test('multiline stack traces survive debug configuration as literal context', t => {
  const trace = 'TypeError: null\n    at getName (app.js:2:15)\nRequest: user=42';
  const dir = temp(t);
  installAgents(dir);
  const p = prepare(['debug', '--symptom', trace, '--only', 'bugs,errors'], dir, {});
  assert.equal(p.symptom, trace);
  assert.equal(p.agents.length, 2);
  assert.throws(() => parseArgs(['--model', 'opus\nhigh']));
  assert.throws(() => parseArgs(['--symptom', 'text\0bad']));
});

test('helper rejects missing destination flags without creating files', t => {
  const dir = temp(t);
  const script = path.join(root, 'skills/code-audit/scripts/agents.mjs');
  for (const args of [['install', '--agents-dir', '--request'], ['install', '--agents-dir', ''], ['install', '--agents-dir', '  ']]) {
    const result = spawnSync(process.execPath, [script, ...args], { cwd: dir, encoding: 'utf8' });
    assert.equal(result.status, 1, result.stdout);
    assert.equal(fs.readdirSync(dir).length, 0);
  }
});
test('every base and runtime specialist can inspect Git and run assigned checks without overriding permission mode', t => {
  const dir = temp(t);
  installAgents(dir);
  const run = prepare(['-all', 'opus', '--effort', 'high'], dir, {});
  for (const file of fs.readdirSync(dir)) {
    const profile = settings(path.join(dir, file));
    assert.ok(profile.tools.split(', ').includes('Bash'));
    assert.ok(!profile.tools.split(', ').some(tool => ['Write', 'Edit', 'Agent'].includes(tool)));
    assert.ok(!('permissionMode' in profile));
  }
  assert.equal(run.runId, undefined);
});
test('profile setup diagnoses a non-directory without partial writes', t => {
  const file = path.join(temp(t), 'not-a-directory');
  fs.writeFileSync(file, 'keep');
  assert.throws(() => prepare([], file, {}), /Cannot prepare subagents/);
  assert.equal(fs.readFileSync(file, 'utf8'), 'keep');
});
test('upgrade backs up recognized older files and preserves settings and active runs', async t => {
  const { createHash } = await import('node:crypto');
  const dest = path.join(temp(t), 'config');
  const install = (...options) => spawnSync(process.execPath, [path.join(root, 'install.mjs'), '--config-dir', dest, ...options], { encoding: 'utf8' });
  assert.equal(install().status, 0);
  const rel = 'agents/code-audit-bugs.md';
  const current = fs.readFileSync(path.join(dest, rel), 'utf8');
  const old = current.replace('tools: Read, Glob, Grep, Bash', 'tools: Read, Glob, Grep');
  fs.writeFileSync(path.join(dest, rel), old);
  const manifestPath = path.join(dest, '.code-audit-manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  manifest.hashes[rel] = createHash('sha256').update(old).digest('hex');
  fs.writeFileSync(manifestPath, JSON.stringify(manifest));
  fs.writeFileSync(path.join(dest, 'settings.json'), '{"permissions":{"deny":["Edit"]}}');
  fs.writeFileSync(path.join(dest, 'agents/code-audit-live-run.md'), 'keep running');
  assert.equal(install().status, 1);
  assert.equal(fs.readFileSync(path.join(dest, rel), 'utf8'), old);
  const result = install('--upgrade');
  assert.equal(result.status, 0, result.stderr);
  const info = JSON.parse(result.stdout);
  assert.equal(info.filesUpdated, 1);
  assert.equal(fs.readFileSync(path.join(info.backup, rel), 'utf8'), old);
  assert.equal(fs.readFileSync(path.join(dest, rel), 'utf8'), current);
  assert.equal(fs.readFileSync(path.join(dest, 'settings.json'), 'utf8'), '{"permissions":{"deny":["Edit"]}}');
  assert.equal(fs.readFileSync(path.join(dest, 'agents/code-audit-live-run.md'), 'utf8'), 'keep running');
  assert.equal(JSON.parse(install('--upgrade').stdout).filesUpdated, 0);
});
test('upgrade refuses locally modified files before changing any other file', t => {
  const dest = path.join(temp(t), 'config');
  const install = (...options) => spawnSync(process.execPath, [path.join(root, 'install.mjs'), '--config-dir', dest, ...options], { encoding: 'utf8' });
  assert.equal(install().status, 0);
  const file = path.join(dest, 'agents/code-audit-comments.md');
  const originalManifest = fs.readFileSync(path.join(dest, '.code-audit-manifest.json'), 'utf8');
  fs.appendFileSync(file, '\nPersonal review instructions.\n');
  const result = install('--upgrade');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Preserving customized/);
  assert.ok(fs.readFileSync(file, 'utf8').endsWith('Personal review instructions.\n'));
  assert.equal(fs.readFileSync(path.join(dest, '.code-audit-manifest.json'), 'utf8'), originalManifest);
  assert.ok(!fs.existsSync(path.join(dest, 'code-audit-backups')));
});
