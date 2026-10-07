import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { defaultAgentFiles, validateBundle } from './skills/code-audit/scripts/agents.mjs';

const bundle = path.dirname(fileURLToPath(import.meta.url));
const digest = data => crypto.createHash('sha256').update(data).digest('hex');
function install() {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === '--help') return { usage: 'node install.mjs [--upgrade] [--project /path/to/repo | --config-dir /path/to/claude-config]', default: 'CLAUDE_CONFIG_DIR or ~/.claude', requires: 'Node.js 18+; current Claude Code; restart after installation' };
  let scope;
  let location;
  let upgrade = false;
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--upgrade' && !upgrade) { upgrade = true; continue; }
    if (!['--project', '--config-dir'].includes(arg) || scope) throw new Error('Invalid or repeated option. Use --help.');
    const value = args[++i];
    if (!value?.trim() || value.startsWith('-')) throw new Error('Missing installation destination. Use a directory path; prefix flag-like directory names with ./');
    scope = arg;
    location = value;
  }
  const destination = path.resolve(scope === '--project' ? path.join(location, '.claude') : location || process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), '.claude'));
  validateBundle();
  const files = new Map();
  function collect(from, rel) {
    for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
      const name = path.posix.join(rel, entry.name);
      if (entry.isDirectory()) collect(path.join(from, entry.name), name);
      else if (entry.isFile()) files.set(name, fs.readFileSync(path.join(from, entry.name)));
      else throw new Error(`Unsupported bundle entry: ${entry.name}`);
    }
  }
  collect(path.join(bundle, 'skills/code-audit'), 'skills/code-audit');
  for (const [name, content] of Object.entries(defaultAgentFiles())) files.set(`agents/${name}`, Buffer.from(content));

  function readExisting(file) {
    let stat;
    try { stat = fs.lstatSync(file); } catch (error) { if (error.code === 'ENOENT') return null; throw error; }
    if (!stat.isFile() || stat.isSymbolicLink()) throw new Error(`Refusing to replace non-regular file: ${file}`);
    return fs.readFileSync(file);
  }
  const manifestPath = path.join(destination, '.code-audit-manifest.json');
  const oldManifestBytes = readExisting(manifestPath);
  let previous = {};
  if (oldManifestBytes) {
    const manifest = JSON.parse(oldManifestBytes.toString('utf8'));
    if (manifest.package !== 'code-audit' || !manifest.hashes || typeof manifest.hashes !== 'object') throw new Error('Unrecognized code-audit installation manifest.');
    previous = manifest.hashes;
  }
  const legacy = JSON.parse(fs.readFileSync(path.join(bundle, 'legacy-v1-hashes.json'), 'utf8'));
  const additions = [];
  const replacements = [];
  for (const [rel, content] of files) {
    const file = path.join(destination, rel);
    const existing = readExisting(file);
    if (existing === null) additions.push([rel, content]);
    else if (!existing.equals(content)) {
      if (!upgrade) throw new Error(`Refusing to overwrite existing content: ${file}. For an unchanged older installation, rerun with --upgrade.`);
      const hash = digest(existing);
      if (hash !== previous[rel] && hash !== legacy[rel]) throw new Error(`Preserving customized or unrecognized file: ${file}. Compare it with the bundle and move it aside yourself before retrying.`);
      replacements.push([rel, content, existing]);
    }
  }
  // No destination writes occur until every collision has been checked.
  let backup = null;
  if (replacements.length) {
    backup = path.join(destination, 'code-audit-backups', `${new Date().toISOString().replace(/[:.]/g, '-')}-${crypto.randomBytes(4).toString('hex')}`);
    for (const [rel, , existing] of replacements) {
      const file = path.join(backup, rel);
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, existing, { flag: 'wx', mode: 0o600 });
    }
    if (oldManifestBytes) fs.writeFileSync(path.join(backup, '.code-audit-manifest.json'), oldManifestBytes, { flag: 'wx', mode: 0o600 });
  }
  for (const [rel, content] of additions) {
    const file = path.join(destination, rel);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content, { flag: 'wx', mode: 0o600 });
  }
  function replaceAtomically(file, content) {
    const temporary = `${file}.${crypto.randomBytes(6).toString('hex')}.tmp`;
    try {
      fs.writeFileSync(temporary, content, { flag: 'wx', mode: 0o600 });
      fs.renameSync(temporary, file);
    } finally {
      if (fs.existsSync(temporary)) fs.unlinkSync(temporary);
    }
  }
  for (const [rel, content] of replacements) replaceAtomically(path.join(destination, rel), content);
  const manifest = Buffer.from(JSON.stringify({ package: 'code-audit', version: 3, hashes: Object.fromEntries([...files].map(([rel, content]) => [rel, digest(content)])) }, null, 2) + '\n');
  if (!oldManifestBytes?.equals(manifest)) {
    fs.mkdirSync(destination, { recursive: true });
    if (oldManifestBytes) replaceAtomically(manifestPath, manifest);
    else fs.writeFileSync(manifestPath, manifest, { flag: 'wx', mode: 0o600 });
  }
  return { installed: path.join(destination, 'skills/code-audit'), specialists: 8, agents: Object.keys(defaultAgentFiles()).length, filesCreated: additions.length, filesUpdated: replacements.length, backup, next: 'Restart Claude Code in your repository, then run /code-audit -all opus --effort high' };
}
try { process.stdout.write(`${JSON.stringify(install(), null, 2)}\n`); }
catch (error) { process.stderr.write(`code-audit install: ${error.message}\n`); process.exitCode = 1; }
