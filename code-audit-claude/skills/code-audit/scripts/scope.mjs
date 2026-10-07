import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Read-only Git operations. Resolve references once; reviewers use the returned SHAs.
export function resolveScope({ repo = '.', base, head = 'HEAD' }) {
  if (typeof repo !== 'string' || !repo || typeof base !== 'string' || !base || typeof head !== 'string' || !head) throw new Error('Scope needs --base REF and optional --repo DIR / --head REF.');
  function git(...args) {
    const result = spawnSync('git', ['--no-optional-locks', '-C', repo, '-c', 'core.fsmonitor=false', ...args], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, env: { ...process.env, GIT_TERMINAL_PROMPT: '0' } });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`Git scope inspection failed: ${result.stderr.trim() || 'git exited ' + result.status}`);
    return result.stdout;
  }
  const commit = ref => git('rev-parse', '--verify', '--end-of-options', `${ref}^{commit}`).trim();
  const root = git('rev-parse', '--show-toplevel').replace(/\r?\n$/, '');
  const baseCommit = commit(base);
  const headCommit = commit(head);
  const workingHead = commit('HEAD');
  const mergeBases = git('merge-base', '--all', baseCommit, headCommit).trim().split('\n').filter(Boolean);
  if (mergeBases.length !== 1) throw new Error('Comparison has multiple merge bases; resolve the intended baseline explicitly before reviewing.');
  const mergeBase = mergeBases[0];
  const files = git('diff', '--no-ext-diff', '--no-textconv', '--name-only', '-z', mergeBase, headCommit, '--').split('\0').filter(Boolean);
  const status = git('status', '--porcelain=v1', '-z', '--untracked-files=all');
  return { root, baseCommit, mergeBase, headCommit, workingHead, dirty: status.length > 0, changedFiles: files, source: 'git-objects', comparison: `${mergeBase}..${headCommit}`, requirement: 'Read after-side files and every dependency with git show HEAD_COMMIT:path (using the returned SHA), or in a clean checkout of headCommit. Do not use dirty working-tree contents for committed findings.' };
}

function main(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i];
    const value = args[i + 1];
    if (!['--repo', '--base', '--head'].includes(key) || options[key.slice(2)] !== undefined || !value || value.startsWith('-')) throw new Error(`Invalid or missing scope option: ${key}`);
    options[key.slice(2)] = value;
  }
  return resolveScope(options);
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.stdout.write(JSON.stringify(main(process.argv.slice(2)), null, 2) + '\n'); }
  catch (error) { process.stderr.write(`code-audit scope: ${error.message}\n`); process.exitCode = 1; }
}
