import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const srcDir = path.resolve(__dirname, '../dist');
const destDir = path.resolve(__dirname, '../../desktop/frontend/dist');

if (fs.existsSync(srcDir)) {
  fs.mkdirSync(destDir, { recursive: true });
  fs.cpSync(srcDir, destDir, { recursive: true, force: true });
  console.log('[sync-desktop] Copied client/dist to desktop/frontend/dist');
}
