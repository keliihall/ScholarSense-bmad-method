import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  buildMatrixTemplate,
  loadJson,
  runFormalMatrix,
  sortJson,
} from './formal-web-harness.mjs';


const PROJECT_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const values = new Map();
for (let index = 2; index < process.argv.length; index += 2) values.set(process.argv[index], process.argv[index + 1]);
for (const name of ['--served-root', '--browsers', '--subject-sha256', '--output']) {
  if (!values.get(name)) throw new Error(`FORMAL_WEB_CAPTURE_INPUT_MISSING: ${name}`);
}
const output = resolve(values.get('--output'));
mkdirSync(resolve(output, 'goldens'), { recursive: true });
const testEnvironment = loadJson(resolve(PROJECT_ROOT, 'contracts/performance/test-environment-1.0.0.json'));
const runner = loadJson(resolve(PROJECT_ROOT, 'contracts/release/formal-web-runner-1.0.0.json'));
const uiTokens = loadJson(resolve(PROJECT_ROOT, 'contracts/release/ui-token-manifest-1.0.0.json'));
const brandAssets = loadJson(resolve(PROJECT_ROOT, 'contracts/release/brand-asset-manifest-1.0.0.json'));
const oracle = buildMatrixTemplate(testEnvironment, values.get('--subject-sha256'), runner.runnerImageSha256);
const matrix = await runFormalMatrix({
  servedRoot: resolve(values.get('--served-root')),
  browserInstall: loadJson(resolve(values.get('--browsers'))),
  oracle,
  uiTokens,
  brandAssets,
  outputRoot: output,
  mode: 'capture',
});
writeFileSync(resolve(output, 'candidate-cells.json'), `${JSON.stringify(sortJson(matrix))}\n`, { flag: 'wx' });
console.log(`formal-web-golden-capture: PASS (${matrix.length} cells)`);
