// Stamps a build identity into the service worker and copies it into dist/.
//
// The worker is not processed by Vite (it must stay a top-level script at /sw.js),
// so its version placeholder is substituted here. A hard-coded constant meant the
// registered worker was byte-identical across releases, and browsers only fetch a
// new worker when its bytes change.
import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(root, 'sw.js');
const target = resolve(root, 'dist', 'sw.js');

function buildId() {
  if (process.env.SW_VERSION) return process.env.SW_VERSION;
  try {
    const sha = execFileSync('git', ['rev-parse', '--short', 'HEAD'], {
      cwd: root,
      stdio: ['ignore', 'pipe', 'ignore'],
    }).toString().trim();
    if (sha) return sha;
  } catch {
    // Not a git checkout (or git unavailable) — fall back to the build time.
  }
  return new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
}

const version = buildId();
const contents = readFileSync(source, 'utf8');

mkdirSync(dirname(target), { recursive: true });
if (contents.includes('__SW_VERSION__')) {
  writeFileSync(target, contents.replaceAll('__SW_VERSION__', version));
} else {
  copyFileSync(source, target);
}
console.log(`[build-sw] wrote dist/sw.js (version ${version})`);
