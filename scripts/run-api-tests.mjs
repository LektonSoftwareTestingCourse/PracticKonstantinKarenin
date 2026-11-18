import { spawnSync } from 'node:child_process';
import { mkdirSync, copyFileSync, readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { findMaven } from './api-test-tools.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';
const maven = findMaven();
const compose = ['compose', '--env-file', '.env.example', '-f', 'docker-compose.yaml',
  '-f', 'docker-compose.api-tests.yaml'];
const services = ['postgres', 'rabbitmq', 'bin-lookup', 'card-management', 'authorization',
  'transaction-logger', 'switch', 'gateway', 'merchant-acquirer', 'terminal-simulator',
  'gateway-faults', 'authorization-faults', 'switch-faults'];

function run(command, args) {
  console.log(`\n> ${command} ${args.join(' ')}`);
  const options = { cwd: root, stdio: 'inherit' };
  const result = windows && /\.cmd$/i.test(command)
    ? spawnSync('C:\\Windows\\System32\\cmd.exe', ['/d', '/s', '/c',
      `""${command}" ${args.map(arg => `"${arg}"`).join(' ')}"`], { ...options, windowsVerbatimArguments: true })
    : spawnSync(command, args, options);
  if (result.error || result.status !== 0) {
    throw new Error(`${command} failed: ${result.error || result.status}`);
  }
}

const repeatsArg = process.argv.find(arg => arg.startsWith('--repeat='));
const repeats = repeatsArg ? Number(repeatsArg.split('=')[1]) : 1;
if (!Number.isInteger(repeats) || repeats < 1 || repeats > 10) {
  throw new Error('--repeat must be an integer between 1 and 10');
}

try {
  if (!process.argv.includes('--existing')) {
    run(maven, ['-B', '-f', 'services/pom.xml', '-Pall', '-pl',
      'gateway,switch,card-management,authorization,transaction-logger,merchant-acquirer,terminal-simulator,bin-lookup',
      '-am', 'package', '-Dmaven.test.skip=true', '-Dcheckstyle.skip=true']);
    // --wait uses service healthchecks; no manual readiness steps or fixed startup sleeps.
    run('docker', [...compose, 'up', '-d', '--build', '--wait', '--wait-timeout', '360', ...services]);
  }
  for (let iteration = 1; iteration <= repeats; iteration++) {
    run(maven, ['-B', '-f', 'services/pom.xml', '-Papi-tests', 'clean', 'verify']);
    const target = path.join(root, 'services/api-tests/target');
    const reports = path.join(target, 'surefire-reports');
    const evidence = path.join(root, 'docs/practice-5/evidence', `run-${iteration}`);
    mkdirSync(evidence, { recursive: true });
    const summary = [];
    for (const name of readdirSync(reports).filter(name => name.endsWith('.txt'))) {
      const report = readFileSync(path.join(reports, name), 'utf8');
      summary.push(...report.split(/\r?\n/).filter(line => line.startsWith('Tests run:')));
    }
    writeFileSync(path.join(evidence, 'summary.txt'), summary.join('\n') + '\n');
    if (existsSync(path.join(target, 'api-requests.log'))) {
      copyFileSync(path.join(target, 'api-requests.log'), path.join(evidence, 'requests.log'));
    }
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  if (process.argv.includes('--down')) {
    // Preserve volumes; never erase somebody's database as part of a test run.
    run('docker', [...compose, 'stop', ...services]);
  }
}
