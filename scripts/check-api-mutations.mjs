import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, readdirSync, copyFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { findMaven } from './api-test-tools.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const maven = findMaven();
const compose = ['compose', '--env-file', '.env.example', '-f', 'docker-compose.yaml',
  '-f', 'docker-compose.api-tests.yaml'];
const evidence = path.join(root, 'docs/practice-5/evidence/mutations');
mkdirSync(evidence, { recursive: true });
const mutations = [
  {
    id: 'deduplication', service: 'transaction-logger',
    file: 'services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java',
    from: 'transactionRepository.findById(request.id())', to: 'Optional.<Transaction>empty()', occurrences: 2,
    test: 'LoggerDeliveryTest#repeatedDeliveryReturnsExistingTransactionWithoutCreatingAnotherRow',
  },
  {
    id: 'zero-amount', service: 'gateway',
    file: 'services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java',
    from: 'request.amount().compareTo(BigDecimal.ZERO) <= 0',
    to: 'request.amount().compareTo(BigDecimal.ZERO) < 0', occurrences: 1,
    test: 'GatewayValidationTest#rejectsOneInvalidField',
  },
  {
    id: 'rrn-format', service: 'authorization',
    file: 'services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java',
    from: 'AuthorizationResponse.approved(request, rrn, authCode, requestInputTime)',
    to: 'AuthorizationResponse.approved(request, "BROKEN", authCode, requestInputTime)', occurrences: 1,
    test: 'AuthorizationTest#directApprovalHasFormattedGeneratedFields',
  },
];

function execute(command, args, logName) {
  console.log(`> ${command} ${args.join(' ')}`);
  const options = { cwd: root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 };
  const result = process.platform === 'win32' && /\.cmd$/i.test(command)
    ? spawnSync('C:\\Windows\\System32\\cmd.exe', ['/d', '/s', '/c',
      `""${command}" ${args.map(arg => `"${arg}"`).join(' ')}"`], { ...options, windowsVerbatimArguments: true })
    : spawnSync(command, args, options);
  if (logName) {
    writeFileSync(path.join(evidence, logName), (result.stdout || '') + (result.stderr || ''));
  }
  if (result.error) {
    throw result.error;
  }
  return result.status;
}

function rebuild(service, label) {
  if (execute(maven, ['-B', '-f', 'services/pom.xml', `-P${service}`, '-pl', service, '-am',
    'package', '-Dmaven.test.skip=true', '-Dcheckstyle.skip=true'], `${label}-package.log`) !== 0) {
    throw new Error(`Maven build failed for ${label}; this is NOT a killed mutation`);
  }
  if (execute('docker', [...compose, 'build', service], `${label}-build.log`) !== 0) {
    throw new Error(`Build failed for ${label}; this is NOT a killed mutation`);
  }
  if (execute('docker', [...compose, 'up', '-d', '--no-deps', '--force-recreate', '--wait',
    '--wait-timeout', '180', service], `${label}-start.log`) !== 0) {
    throw new Error(`Restart failed for ${label}; this is NOT a killed mutation`);
  }
}

const summary = [];
for (const mutation of mutations) {
  const file = path.join(root, mutation.file);
  const original = readFileSync(file, 'utf8');
  const count = original.split(mutation.from).length - 1;
  if (count !== mutation.occurrences) {
    throw new Error(`Source changed: ${mutation.id} expected ${mutation.occurrences} anchors, found ${count}`);
  }
  try {
    writeFileSync(file, original.replaceAll(mutation.from, mutation.to));
    rebuild(mutation.service, mutation.id);
    const status = execute(maven, ['-B', '-f', 'services/api-tests/pom.xml', 'test', `-Dtest=${mutation.test}`],
      `${mutation.id}-test.log`);
    const reports = path.join(root, 'services/api-tests/target/surefire-reports');
    const xmlName = `TEST-com.processing.api.${mutation.test.split('#')[0]}.xml`;
    const xml = readFileSync(path.join(reports, xmlName), 'utf8');
    // Compile errors, unavailable infrastructure, and zero executed tests do not count as evidence.
    const failures = [...xml.matchAll(/<failure\b/g)].length;
    const errors = [...xml.matchAll(/<error\b/g)].length;
    if (status === 0 || failures < 1 || errors > 0 || !xml.includes('AssertionError')) {
      throw new Error(`Mutation ${mutation.id} did not cause a clean assertion failure`);
    }
    copyFileSync(path.join(reports, xmlName), path.join(evidence, `${mutation.id}.xml`));
    summary.push(`${mutation.id}: KILLED, assertion failures=${failures}, errors=${errors}`);
    writeFileSync(path.join(evidence, 'summary.txt'), summary.join('\n') + '\n');
  } finally {
    writeFileSync(file, original);
    rebuild(mutation.service, `${mutation.id}-restored`);
  }
}
console.log(summary.join('\n'));
