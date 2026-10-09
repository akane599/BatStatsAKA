import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { resolveScope } from '../skills/code-audit/scripts/scope.mjs';

function fixture(t) {
  const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'audit-scope-'));
  t.after(() => fs.rmSync(repo, { recursive: true, force: true }));
  const env = { ...process.env, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: os.platform() === 'win32' ? 'NUL' : '/dev/null', GIT_AUTHOR_NAME: 'Fixture', GIT_AUTHOR_EMAIL: 'fixture@example.invalid', GIT_COMMITTER_NAME: 'Fixture', GIT_COMMITTER_EMAIL: 'fixture@example.invalid' };
  function git(...args) {
    const result = spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8', env });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  }
  git('init', '-b', 'main');
  fs.writeFileSync(path.join(repo, 'service.js'), 'export const value = 1;\n');
  fs.writeFileSync(path.join(repo, 'caller.js'), 'import { value } from "./service.js";\n');
  git('add', '.'); git('commit', '-m', 'baseline');
  const baseline = git('rev-parse', 'HEAD');
  fs.writeFileSync(path.join(repo, 'service.js'), 'export const value = null;\n');
  git('commit', '-am', 'regression');
  const head = git('rev-parse', 'HEAD');
  return { repo, git, baseline, head };
}

test('commit review pins HEAD while dirty fixes and callers remain separate', t => {
  const { repo, git, baseline, head } = fixture(t);
  fs.writeFileSync(path.join(repo, 'service.js'), 'export const value = 1; // dirty fix\n');
  fs.writeFileSync(path.join(repo, 'caller.js'), '// dirty caller masks the failure\n');
  const before = git('status', '--porcelain=v1');
  const scope = resolveScope({ repo, base: baseline });
  assert.equal(scope.headCommit, head);
  assert.equal(scope.mergeBase, baseline);
  assert.equal(scope.dirty, true);
  assert.equal(scope.source, 'git-objects');
  assert.deepEqual(scope.changedFiles, ['service.js']);
  assert.match(git('show', `${scope.headCommit}:service.js`), /null/);
  assert.match(git('show', `${scope.headCommit}:caller.js`), /import/);
  assert.equal(git('status', '--porcelain=v1'), before);
  assert.match(fs.readFileSync(path.join(repo, 'service.js'), 'utf8'), /dirty fix/);
});
test('diverged base uses the common ancestor and explicit PR head ignores local HEAD', t => {
  const { repo, git, baseline, head } = fixture(t);
  git('checkout', '-b', 'other', baseline);
  fs.writeFileSync(path.join(repo, 'other.txt'), 'other branch\n');
  git('add', '.'); git('commit', '-m', 'other');
  const other = git('rev-parse', 'HEAD');
  const scope = resolveScope({ repo, base: other, head });
  assert.equal(scope.baseCommit, other);
  assert.equal(scope.mergeBase, baseline);
  assert.equal(scope.headCommit, head);
  assert.equal(scope.workingHead, other);
  assert.deepEqual(scope.changedFiles, ['service.js']);
  assert.equal(git('rev-parse', 'HEAD'), other);
});
test('invalid and injection-shaped refs fail as Git arguments without executing code', t => {
  const { repo } = fixture(t);
  assert.throws(() => resolveScope({ repo, base: 'missing-ref' }), /Git scope inspection failed/);
  assert.throws(() => resolveScope({ repo, base: '$(touch marker)' }), /Git scope inspection failed/);
  assert.throws(() => resolveScope({ repo, base: '--help' }), /Git scope inspection failed/);
  assert.ok(!fs.existsSync(path.join(repo, 'marker')));
});
test('empty comparisons and non-ignored untracked files are accurately reported', t => {
  const { repo, head } = fixture(t);
  fs.writeFileSync(path.join(repo, 'untracked.js'), 'new source');
  const scope = resolveScope({ repo, base: head });
  assert.deepEqual(scope.changedFiles, []);
  assert.equal(scope.dirty, true);
});
