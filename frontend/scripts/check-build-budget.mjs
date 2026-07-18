import { gzipSync } from 'node:zlib';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';


const dist = new URL('../dist/', import.meta.url);
const manifest = JSON.parse(readFileSync(new URL('.vite/manifest.json', dist), 'utf8'));
const budget = {
  initialRawPerFile: 250000,
  initialGzipPerFile: 90000,
  initialRawTotal: 350000,
  asyncRawPerFile: 600000,
  asyncGzipPerFile: 200000,
  cssRawPerFile: 120000,
  cssGzipPerFile: 25000,
};

const initial = new Set();
const visit = (key) => {
  const entry = manifest[key];
  if (!entry || initial.has(entry.file)) return;
  initial.add(entry.file);
  for (const dependency of entry.imports ?? []) visit(dependency);
};
for (const [key, entry] of Object.entries(manifest)) if (entry.isEntry) visit(key);

const failures = [];
let initialRawTotal = 0;
for (const file of readdirSync(new URL('assets/', dist))) {
  if (!file.endsWith('.js') && !file.endsWith('.css')) continue;
  const relative = `assets/${file}`;
  const payload = readFileSync(join(dist.pathname, relative));
  const raw = payload.byteLength;
  const gzip = gzipSync(payload, { level: 9 }).byteLength;
  if (file.endsWith('.css')) {
    if (raw > budget.cssRawPerFile || gzip > budget.cssGzipPerFile) failures.push(`${relative}: css ${raw}/${gzip}`);
  } else if (initial.has(relative)) {
    initialRawTotal += raw;
    if (raw > budget.initialRawPerFile || gzip > budget.initialGzipPerFile) failures.push(`${relative}: initial ${raw}/${gzip}`);
  } else if (raw > budget.asyncRawPerFile || gzip > budget.asyncGzipPerFile) {
    failures.push(`${relative}: async ${raw}/${gzip}`);
  }
}
if (initialRawTotal > budget.initialRawTotal) failures.push(`initial-total: ${initialRawTotal}`);
if (failures.length) {
  console.error(`BUILD_BUDGET_EXCEEDED\n${failures.join('\n')}`);
  process.exitCode = 1;
} else {
  console.log(`build-budget: PASS initialRawTotal=${initialRawTotal}`);
}
