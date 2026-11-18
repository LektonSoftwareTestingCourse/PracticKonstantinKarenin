import { existsSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';

export function findMaven() {
  if (process.env.MAVEN_CMD) {
    return process.env.MAVEN_CMD;
  }
  if (process.platform !== 'win32') {
    return 'mvn';
  }
  const found = spawnSync('C:\\Windows\\System32\\cmd.exe', ['/d', '/c', 'where mvn.cmd'],
    { encoding: 'utf8', windowsHide: true });
  if (found.status === 0) {
    return found.stdout.trim().split(/\r?\n/)[0];
  }
  // IntelliJ includes Maven even when the standalone command is absent from PATH.
  for (const base of [process.env.ProgramFiles, process.env['ProgramFiles(x86)']].filter(Boolean)) {
    const jetbrains = path.join(base, 'JetBrains');
    if (!existsSync(jetbrains)) {
      continue;
    }
    for (const name of readdirSync(jetbrains).filter(name => name.startsWith('IntelliJ IDEA')).sort().reverse()) {
      const candidate = path.join(jetbrains, name, 'plugins/maven/lib/maven3/bin/mvn.cmd');
      if (existsSync(candidate)) {
        return candidate;
      }
    }
  }
  return 'mvn.cmd';
}
