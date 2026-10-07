import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs, registeredName, agentPrompt, roles, reviewerTools } from './agents.mjs';

export function quoteArgument(value) {
  return "'" + value.replaceAll("'", "'\"'\"'") + "'";
}

export function buildLaunch(args, environment = process.env) {
  const config = parseArgs(args);
  if (config.help) return { help: true };
  const definitions = {};
  const profiles = [];
  for (const agent of config.agents) {
    const name = registeredName(agent.role, agent.effort);
    definitions[name] = { description: roles[agent.role].description, prompt: agentPrompt(agent.role), tools: [...reviewerTools], model: agent.model };
    if (agent.effort !== 'inherit') definitions[name].effort = agent.effort;
    profiles.push({ ...agent, subagent_type: name });
  }
  const prompt = '/code-audit ' + args.map(quoteArgument).join(' ');
  return { config, command: 'claude', argv: ['--agents', JSON.stringify(definitions), prompt], env: { ...environment, CODE_AUDIT_SESSION_AGENTS: JSON.stringify({ version: 1, agents: profiles }) } };
}

export async function startReview(args, { environment = process.env, spawnProcess = spawn } = {}) {
  const launch = buildLaunch(args, environment);
  if (launch.help) {
    process.stdout.write('Usage: node launch.mjs [--dry-run] [review|audit|debug|bug-hunt] [targets] [-all MODEL] [--effort LEVEL] [--agent-model ROLE=MODEL] [--agent-effort ROLE=LEVEL]\nRun from the reviewed repository after installing the skill. Model IDs are passed verbatim to Claude Code; the provider must support them.\n');
    return 0;
  }
  const child = spawnProcess(launch.command, launch.argv, { stdio: 'inherit', env: launch.env, shell: false });
  return await new Promise((resolve, reject) => {
    child.once('error', error => reject(new Error(error.code === 'ENOENT' ? 'Claude Code executable was not found. Install it or run the launcher in the environment where claude is available.' : error.message)));
    child.once('exit', (code, signal) => resolve(code ?? (signal === 'SIGINT' ? 130 : 1)));
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const input = process.argv.slice(2);
    if (input[0] === '--dry-run') {
      const launch = buildLaunch(input.slice(1));
      // Export no environment variables or credentials in dry-run output.
      process.stdout.write(JSON.stringify(launch.help ? { help: true } : { command: launch.command, argv: launch.argv, registration: 'startup --agents JSON', reviewers: launch.config.agents }, null, 2) + '\n');
    } else process.exitCode = await startReview(input);
  } catch (error) { process.stderr.write('code-audit launcher: ' + error.message + '\n'); process.exitCode = 1; }
}
