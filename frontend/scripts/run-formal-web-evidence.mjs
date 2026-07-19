import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  canonicalSha256,
  loadJson,
  runFormalMatrix,
  sortJson,
} from './formal-web-harness.mjs';


const PROJECT_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const values = new Map();
for (let index = 2; index < process.argv.length; index += 2) values.set(process.argv[index], process.argv[index + 1]);
for (const name of [
  '--served-root', '--browsers', '--golden-root', '--artifact-uri', '--subject-sha256',
  '--build-manifest-sha256', '--output',
]) {
  if (!values.get(name)) throw new Error(`FORMAL_WEB_INPUT_MISSING: ${name}`);
}
const output = resolve(values.get('--output'));
mkdirSync(resolve(output, 'screenshots/goldens'), { recursive: true });
const testEnvironment = loadJson(resolve(PROJECT_ROOT, 'contracts/performance/test-environment-1.0.0.json'));
const runner = loadJson(resolve(PROJECT_ROOT, 'contracts/release/formal-web-runner-1.0.0.json'));
const oracle = loadJson(resolve(PROJECT_ROOT, 'contracts/release/visual-baseline-vgb-1.0.0.json'));
const uiTokens = loadJson(resolve(PROJECT_ROOT, 'contracts/release/ui-token-manifest-1.0.0.json'));
const brandAssets = loadJson(resolve(PROJECT_ROOT, 'contracts/release/brand-asset-manifest-1.0.0.json'));
if (oracle.approvedArtifactSha256 !== values.get('--subject-sha256')) {
  throw new Error('FORMAL_WEB_ORACLE_SUBJECT_MISMATCH');
}
if (runner.runnerImageSha256 !== oracle.runnerImageSha256) {
  throw new Error('FORMAL_WEB_ORACLE_RUNNER_MISMATCH');
}
const matrix = await runFormalMatrix({
  servedRoot: resolve(values.get('--served-root')),
  browserInstall: loadJson(resolve(values.get('--browsers'))),
  oracle,
  uiTokens,
  brandAssets,
  outputRoot: resolve(output, 'screenshots'),
  goldenRoot: resolve(values.get('--golden-root')),
  mode: 'verify',
});
const report = {
  version: 'FORMAL-WEB-REPORT-1.0.0',
  subjectArtifactUri: values.get('--artifact-uri'),
  subjectArtifactSha256: values.get('--subject-sha256'),
  buildManifestSha256: values.get('--build-manifest-sha256'),
  testEnvironmentSha256: testEnvironment.contentSha256,
  visualBaselineSha256: canonicalSha256(oracle),
  runnerImageSha256: runner.runnerImageSha256,
  matrix,
  scope: {
    productionEntry: '/scholarsense/',
    downstreamBusinessPages: 'not-in-scope-before-their-own-stories',
    appApplicability: 'not-applicable',
    appRuntimeEvidenceClaim: 'none',
  },
  result: 'passed',
  createdAt: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
};
writeFileSync(resolve(output, 'formal-web-report.json'), `${JSON.stringify(sortJson(report))}\n`, { flag: 'wx' });
console.log(`formal-web-evidence: PASS (${matrix.length} exact cells)`);
