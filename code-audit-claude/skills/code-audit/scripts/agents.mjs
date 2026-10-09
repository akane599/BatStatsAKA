import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const skillRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const defaultAgentsDir = path.resolve(skillRoot, '..', '..', 'agents');
export const reviewerTools = ['Read', 'Glob', 'Grep', 'Bash'];
export const roles = JSON.parse(fs.readFileSync(path.join(skillRoot, 'references/roles.json'), 'utf8'));
const contract = fs.readFileSync(path.join(skillRoot, 'references/contract.md'), 'utf8');
const efforts = new Set(['inherit', 'low', 'medium', 'high', 'xhigh', 'max']);
const modes = new Set(['review', 'audit', 'debug', 'bug-hunt']);
const roleIds = Object.keys(roles);
const valueFlags = new Set(['-all', '--all', '--model', '--effort', '--agent-model', '--agent-effort', '--only', '--parallel', '--base', '--pr', '--symptom']);

function fail(message) { throw new Error(message); }
function model(value) {
  if (!value || /^-/.test(value) || /[\s\x00-\x1f\x7f]/.test(value)) fail('Model must be a nonempty alias or full model ID without whitespace.');
  return value;
}
function effort(value) {
  if (!efforts.has(value)) fail(`Invalid effort ${JSON.stringify(value)}. Use ${[...efforts].join(', ')}.`);
  return value;
}
function assignment(value, validate) {
  const split = value.indexOf('=');
  const role = value.slice(0, split);
  if (split < 1 || !Object.hasOwn(roles, role)) fail(`Expected ROLE=VALUE with role: ${roleIds.join(', ')}.`);
  return [role, validate(value.slice(split + 1))];
}

export function parseArgs(args = []) {
  if (!Array.isArray(args) || args.some(v => typeof v !== 'string' || /[\x00]/.test(v))) fail('args must be an array of strings without NUL characters.');
  const input = [...args];
  const config = { mode: modes.has(input[0]) ? input.shift() : 'review', targets: [], model: 'inherit', effort: 'inherit', parallel: 4, selected: [...roleIds], agentModels: {}, agentEfforts: {}, notices: [] };
  const seen = new Set();
  for (let i = 0; i < input.length; i++) {
    let flag = input[i];
    if (flag === '--') { config.targets.push(...input.slice(i + 1)); break; }
    if (flag === '--help' || flag === '-h') { config.help = true; continue; }
    if (!flag.startsWith('-')) { config.targets.push(flag); continue; }
    let value;
    const equals = flag.indexOf('=');
    if (equals > 0) { value = flag.slice(equals + 1); flag = flag.slice(0, equals); }
    if (!valueFlags.has(flag)) fail(`Unknown option ${flag}. Use --help.`);
    if (value === undefined) {
      value = input[++i];
      if (value === undefined || value.startsWith('--') || value === '-all') fail(`Missing value for ${flag}.`);
    }
    if (!value) fail(`Empty value for ${flag}.`);
    const canonical = flag === '-all' ? '--all' : flag;
    if (flag !== '--agent-model' && flag !== '--agent-effort') {
      if (seen.has(canonical)) fail(`Repeated option ${canonical}.`);
      seen.add(canonical);
    }
    switch (canonical) {
      case '--all': config.allModel = model(value); break;
      case '--model': config.model = model(value); break;
      case '--effort': config.effort = effort(value); break;
      case '--agent-model':
      case '--agent-effort': {
        const [role, resolved] = assignment(value, canonical === '--agent-model' ? model : effort);
        const map = canonical === '--agent-model' ? config.agentModels : config.agentEfforts;
        if (Object.hasOwn(map, role)) fail(`Repeated ${canonical} for ${role}.`);
        map[role] = resolved;
        break;
      }
      case '--only': {
        const selected = value.split(',');
        if (selected.some(r => !Object.hasOwn(roles, r))) fail(`Unknown role in --only. Use ${roleIds.join(', ')}.`);
        if (new Set(selected).size !== selected.length || selected.length < 2) fail('--only needs at least two distinct roles, without duplicates.');
        config.selected = selected;
        break;
      }
      case '--parallel':
        if (!/^[1-8]$/.test(value)) fail('--parallel must be an integer from 1 to 8.');
        config.parallel = Number(value); break;
      case '--base': config.base = value; break;
      case '--pr': config.pr = value; break;
      case '--symptom': config.symptom = value; break;
    }
  }
  if (config.base && config.pr) fail('Choose either --base or --pr, not both.');
  for (const role of new Set([...Object.keys(config.agentModels), ...Object.keys(config.agentEfforts)])) {
    if (!config.selected.includes(role)) fail(`Override for unselected role ${role}. Add it to --only or remove the override.`);
  }
  if (config.allModel && Object.keys(config.agentModels).length) config.notices.push('-all overrides all individual model selections; individual effort selections still apply.');
  config.agents = config.selected.map(role => ({ role, model: config.allModel ?? config.agentModels[role] ?? config.model, effort: config.agentEfforts[role] ?? config.effort }));
  return config;
}

export function renderAgent(agent, name, runId) {
  const lines = ['---', `name: ${name}`, `description: ${JSON.stringify(roles[agent.role].description)}`, 'tools: Read, Glob, Grep, Bash', `model: ${JSON.stringify(agent.model)}`];
  if (agent.effort !== 'inherit') lines.push(`effort: ${agent.effort}`);
  lines.push('---', '', `# ${agent.role} specialist`, '', roles[agent.role].prompt, '', contract.trim(), '');
  if (runId) lines.push(`<!-- code-audit-owned-run:${runId} -->`, '');
  return lines.join('\n');
}

export function registeredName(role, level = 'inherit') {
  if (!Object.hasOwn(roles, role)) fail('Unknown reviewer role: ' + role);
  effort(level);
  return 'code-audit-' + role + (level === 'inherit' ? '' : '-' + level);
}

export function agentPrompt(role) {
  if (!Object.hasOwn(roles, role)) fail('Unknown reviewer role: ' + role);
  return '# ' + role + ' specialist\n\n' + roles[role].prompt + '\n\n' + contract.trim() + '\n';
}

export function defaultAgentFiles() {
  const files = {};
  for (const role of roleIds) {
    for (const level of efforts) {
      const name = registeredName(role, level);
      files[name + '.md'] = renderAgent({ role, model: 'inherit', effort: level }, name);
    }
  }
  return files;
}

function sameOrAbsent(file, content) {
  let stat;
  try { stat = fs.lstatSync(file); } catch (error) { if (error.code === 'ENOENT') return false; throw error; }
  if (!stat.isFile() || stat.isSymbolicLink() || fs.readFileSync(file, 'utf8') !== content) fail(`Refusing to overwrite existing content: ${file}`);
  return true;
}

export function installAgents(directory = defaultAgentsDir) {
  const files = defaultAgentFiles();
  for (const [name, content] of Object.entries(files)) sameOrAbsent(path.join(directory, name), content);
  fs.mkdirSync(directory, { recursive: true });
  for (const [name, content] of Object.entries(files)) {
    const file = path.join(directory, name);
    if (!sameOrAbsent(file, content)) fs.writeFileSync(file, content, { flag: 'wx', mode: 0o600 });
  }
  return { agentsDirectory: path.resolve(directory), agents: Object.keys(files), restart: 'Restart Claude Code after first installation to load the agents directory.' };
}

export function prepare(args, directory = defaultAgentsDir, environment = process.env) {
  const config = parseArgs(args);
  if (config.help) return { help: true, guide: path.join(skillRoot, 'references/usage.md') };
  if (!fs.existsSync(directory)) fail('Companion agents directory is missing. Install or upgrade the bundle and restart Claude Code first.');
  if (!fs.statSync(directory).isDirectory()) fail('Cannot prepare subagents: agent path is not a directory.');
  let session = {};
  if (environment.CODE_AUDIT_SESSION_AGENTS) {
    const data = JSON.parse(environment.CODE_AUDIT_SESSION_AGENTS);
    if (data.version !== 1 || !Array.isArray(data.agents)) fail('Invalid code-audit launcher session metadata.');
    for (const entry of data.agents) {
      if (registeredName(entry.role, entry.effort) !== entry.subagent_type) fail('Invalid registered name in launcher session metadata.');
      model(entry.model);
      session[entry.subagent_type] = entry;
    }
  }
  for (const agent of config.agents) {
    agent.subagent_type = registeredName(agent.role, agent.effort);
    const predefined = session[agent.subagent_type];
    if (!predefined) {
      const file = path.join(directory, agent.subagent_type + '.md');
      try { fs.accessSync(file, fs.constants.R_OK); }
      catch (error) { fail('Installed reviewer profile is missing or unreadable: ' + file + '. Upgrade the bundle and restart Claude Code. No new reviewer files are created during a review.'); }
    }
    agent.invocation = { subagent_type: agent.subagent_type };
    if (predefined && predefined.model === agent.model) agent.modelSource = 'startup-definition';
    else if (agent.model !== 'inherit') {
      agent.modelSource = 'per-invocation';
      agent.invocation.model = agent.model;
    } else if (predefined && predefined.model !== 'inherit') {
      fail('This launcher session pins ' + agent.subagent_type + ' to ' + predefined.model + '. Repeat that model selection or start a new session for inherited settings.');
    } else agent.modelSource = 'inherit';
  }
  if (environment.CLAUDE_CODE_SUBAGENT_MODEL_FORCE && !['0', 'false', 'off', ''].includes(environment.CLAUDE_CODE_SUBAGENT_MODEL_FORCE.toLowerCase())) {
    config.notices.push('CLAUDE_CODE_SUBAGENT_MODEL_FORCE is set: runtime model selection may override these settings. Verify before claiming requested models were used.');
  }
  if (environment.CLAUDE_CODE_EFFORT_LEVEL && environment.CLAUDE_CODE_EFFORT_LEVEL !== 'auto') {
    config.notices.push('CLAUDE_CODE_EFFORT_LEVEL is set and can override reviewer effort. Verify runtime effort.');
  }
  return { ...config, agentsDirectory: path.resolve(directory), registration: 'preinstalled-or-startup', settingsStatus: 'requested; verify effective model and effort in Claude Code runtime metadata or /tasks' };
}

export function cleanup(runId, directory = defaultAgentsDir) {
  if (!/^[a-f0-9]{16}$/.test(runId ?? '')) fail('Invalid run ID; expected 16 lowercase hexadecimal characters.');
  const files = [];
  for (const role of roleIds) {
    const file = path.join(directory, `code-audit-${runId}-${role}.md`);
    let stat;
    try { stat = fs.lstatSync(file); } catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    if (!stat.isFile() || stat.isSymbolicLink() || !fs.readFileSync(file, 'utf8').endsWith(`<!-- code-audit-owned-run:${runId} -->\n`)) fail(`Refusing to delete an unrecognized file: ${file}`);
    files.push(file);
  }
  for (const file of files) fs.unlinkSync(file);
  return { runId, removed: files.length };
}

export function validateBundle() {
  const skill = fs.readFileSync(path.join(skillRoot, 'SKILL.md'), 'utf8');
  if (!skill.startsWith('---\nname: code-audit\n') || !skill.includes('\n---\n') || !skill.includes('$ARGUMENTS')) fail('Invalid skill entrypoint.');
  if (roleIds.length !== 8) fail('Expected eight distinct specialist roles.');
  for (const [id, spec] of Object.entries(roles)) {
    if (!/^[a-z]+$/.test(id) || typeof spec.prompt !== 'string' || !spec.description || spec.prompt.length < 100) fail(`Invalid role ${id}.`);
  }
  for (const match of skill.matchAll(/\]\((references\/[^)]+)\)/g)) if (!fs.existsSync(path.join(skillRoot, match[1]))) fail(`Missing reference ${match[1]}.`);
  return { valid: true, roles: roleIds, note: 'Structural validation; live Claude Code loading still needs runtime verification.' };
}

function main(argv) {
  const [command, ...rest] = argv;
  if (command === 'plan') return parseArgs(rest);
  if (command === 'validate') { if (rest.length) fail('validate accepts no options.'); return validateBundle(); }
  if (!['install', 'prepare', 'cleanup'].includes(command)) fail('Usage: agents.mjs plan [skill args] | install | prepare --request FILE | cleanup --run ID | validate');
  const allowed = new Set(['--agents-dir', ...(command === 'prepare' ? ['--request'] : command === 'cleanup' ? ['--run'] : [])]);
  const options = {};
  for (let i = 0; i < rest.length; i += 2) {
    const key = rest[i];
    if (!allowed.has(key) || options[key] !== undefined || rest[i + 1] === undefined || !rest[i + 1].trim() || rest[i + 1].startsWith('-')) fail(`Invalid or missing value for helper option ${key}.`);
    options[key] = rest[i + 1];
  }
  const directory = options['--agents-dir'] ? path.resolve(options['--agents-dir']) : defaultAgentsDir;
  if (command === 'install') return installAgents(directory);
  if (command === 'cleanup') return cleanup(options['--run'], directory);
  if (!options['--request']) fail('prepare requires --request FILE containing {"args":[...]}');
  const request = JSON.parse(fs.readFileSync(options['--request'], 'utf8'));
  if (!request || !Object.hasOwn(request, 'args')) fail('Request must contain an args array.');
  return prepare(request.args, directory);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.stdout.write(`${JSON.stringify(main(process.argv.slice(2)), null, 2)}\n`); }
  catch (error) { process.stderr.write(`code-audit: ${error.message}\n`); process.exitCode = 1; }
}
