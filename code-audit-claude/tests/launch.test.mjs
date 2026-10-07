import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { buildLaunch, startReview, quoteArgument } from '../skills/code-audit/scripts/launch.mjs';
import { prepare, installAgents } from '../skills/code-audit/scripts/agents.mjs';

function directory(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'audit-launch-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}
test('gateway models and effort are registered in --agents before Claude starts', () => {
  const launch = buildLaunch(['review', '--base', 'main', '-all', 'Astra', '--effort', 'high'], {});
  const profiles = JSON.parse(launch.argv[1]);
  assert.equal(launch.command, 'claude');
  assert.equal(launch.argv[0], '--agents');
  assert.equal(Object.keys(profiles).length, 8);
  for (const [name, profile] of Object.entries(profiles)) {
    assert.match(name, /^code-audit-[a-z]+-high$/);
    assert.equal(profile.model, 'Astra');
    assert.equal(profile.effort, 'high');
    assert.ok(profile.tools.includes('Bash'));
    assert.ok(!('permissionMode' in profile));
  }
});
test('launcher and prepare agree on existing registry names without per-call model dependence', t => {
  const args = ['audit', 'src', '--model', 'gateway/default', '--effort', 'medium', '--agent-model', 'bugs=gateway/astra', '--agent-effort', 'bugs=high'];
  const launch = buildLaunch(args, {});
  const registry = JSON.parse(launch.argv[1]);
  const result = prepare(args, directory(t), launch.env);
  for (const a of result.agents) {
    assert.ok(Object.hasOwn(registry, a.subagent_type));
    assert.equal(registry[a.subagent_type].model, a.model);
    assert.equal(registry[a.subagent_type].effort, a.effort);
    assert.equal(a.modelSource, 'startup-definition');
    assert.deepEqual(a.invocation, { subagent_type: a.subagent_type });
  }
});
test('startup registrations remain isolated between concurrent gateway sessions', () => {
  const one = buildLaunch(['-all', 'Astra', '--effort', 'high'], {});
  const two = buildLaunch(['-all', 'OtherGatewayModel', '--effort', 'high'], {});
  assert.ok(Object.values(JSON.parse(one.argv[1])).every(p => p.model === 'Astra'));
  assert.ok(Object.values(JSON.parse(two.argv[1])).every(p => p.model === 'OtherGatewayModel'));
});
test('-all still overrides individual startup models and preserves individual effort', () => {
  const launch = buildLaunch(['--only', 'bugs,tests', '--agent-model', 'tests=other', '-all', 'Astra', '--effort', 'high', '--agent-effort', 'tests=low'], {});
  const profiles = JSON.parse(launch.argv[1]);
  assert.equal(profiles['code-audit-bugs-high'].model, 'Astra');
  assert.equal(profiles['code-audit-tests-low'].model, 'Astra');
  assert.equal(profiles['code-audit-tests-low'].effort, 'low');
});
test('literal input and gateway environment are preserved without invoking a shell or changing permissions', async () => {
  const args = ['audit', 'src/path with spaces', '--symptom', 'trace\n$(touch marker)', '--only', 'bugs,tests', '-all', 'Astra'];
  const env = { ANTHROPIC_BASE_URL: 'https://gateway.example.invalid', ANTHROPIC_AUTH_TOKEN: 'test-token', CLAUDE_CODE_EFFORT_LEVEL: 'medium' };
  let observed;
  const status = await startReview(args, { environment: env, spawnProcess: (exe, argv, options) => {
    observed = { exe, argv, options };
    const child = new EventEmitter();
    queueMicrotask(() => child.emit('exit', 7, null));
    return child;
  } });
  assert.equal(status, 7);
  assert.equal(observed.exe, 'claude');
  assert.equal(observed.options.shell, false);
  assert.equal(observed.options.env.ANTHROPIC_BASE_URL, env.ANTHROPIC_BASE_URL);
  assert.equal(observed.options.env.ANTHROPIC_AUTH_TOKEN, env.ANTHROPIC_AUTH_TOKEN);
  assert.ok(!observed.argv.some(a => /skip-permissions|permission-mode/.test(a)));
  assert.ok(observed.argv[2].startsWith('/code-audit '));
});
test('missing Claude executable is reported clearly', async () => {
  await assert.rejects(startReview([], { environment: {}, spawnProcess: () => {
    const child = new EventEmitter();
    queueMicrotask(() => child.emit('error', Object.assign(new Error('missing'), { code: 'ENOENT' })));
    return child;
  } }), /Claude Code executable was not found/);
});
test('prepare supports read-only installed profiles and diagnoses missing effort variants', t => {
  const dir = directory(t);
  installAgents(dir);
  fs.chmodSync(dir, 0o500);
  try {
    assert.equal(prepare(['-all', 'Astra', '--effort', 'high'], dir, {}).agents.length, 8);
  } finally { fs.chmodSync(dir, 0o700); }
  fs.unlinkSync(path.join(dir, 'code-audit-bugs-high.md'));
  assert.throws(() => prepare(['--only', 'bugs,tests', '--effort', 'high'], dir, {}), /Upgrade the bundle and restart/);
});
test('an inherited model request cannot silently use an earlier gateway startup override', t => {
  const launch = buildLaunch(['--only', 'bugs,tests', '-all', 'Astra'], {});
  assert.throws(() => prepare(['--only', 'bugs,tests'], directory(t), launch.env), /Repeat that model selection/);
});

test('launcher prompt tokens round-trip quotes, multiline symptoms and literal shell text', t => {
  const dir = directory(t);
  const args = ['debug', 'src/a\tb', '--symptom', 'TypeError: null\n\tstack "double" and \'single\'\r\n$(touch marker); $TOKEN `touch marker`', '--only', 'bugs,tests', '-all', 'Astra'];
  const launch = buildLaunch(args, {});
  const serialized = launch.argv[2].slice('/code-audit '.length);
  // Interpret only the argument quoting protocol in an isolated shell. The payload
  // must remain literal; successful command substitution would leave a marker file.
  const capture = 'process.stdout.write(JSON.stringify(process.argv.slice(1)))';
  const result = spawnSync('sh', ['-c', quoteArgument(process.execPath) + ' -e ' + quoteArgument(capture) + ' -- ' + serialized], { cwd: dir, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), args);
  assert.ok(!fs.existsSync(path.join(dir, 'marker')));
});
