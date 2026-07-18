import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import {
  accessSync,
  constants,
  createReadStream,
  readFileSync,
  rmSync,
  statSync,
} from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { chromium } from '@playwright/test';


const APPROVED_BRANDS = new Set(['chrome', 'edge']);
const APPROVED_CHANNELS = new Set(['current', 'previous']);
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const VERSION_PATTERN = /^\d+\.\d+\.\d+\.\d+$/;
const APPROVED_ENVIRONMENT_CONTENT_SHA256 = '1361a3f0fdeb6e747c5c1f6bfc682863d2a96240cd2136ec23c2370e52593fbb';
const APPROVED_ENVIRONMENT_FILE_SHA256 = 'd2ff1b070eea3bb33dab665e3e9cf28f7e2253adf6116d4929553783a7242196';
const FRONTEND_ROOT = fileURLToPath(new URL('../', import.meta.url));
const DIST_PATH = fileURLToPath(new URL('../dist/', import.meta.url));
const PROJECT_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const MANIFEST_PATH = fileURLToPath(
  new URL('../../contracts/performance/test-environment-1.0.0.json', import.meta.url),
);
const BASELINE_CHECKER_PATH = resolve(PROJECT_ROOT, 'scripts/check_frontend_baseline.py');

const argumentsMap = parseArguments(process.argv.slice(2));
const brand = argumentsMap.get('--brand');
const channel = argumentsMap.get('--channel');
const executablePath = resolve(argumentsMap.get('--executable'));
const artifactPath = resolve(argumentsMap.get('--artifact'));

if (!APPROVED_BRANDS.has(brand)) {
  throw new Error(`BRAND_NOT_APPROVED: ${brand}`);
}
if (!APPROVED_CHANNELS.has(channel)) {
  throw new Error(`BRAND_CHANNEL_NOT_APPROVED: ${channel}`);
}
assertRegularFile(executablePath, constants.X_OK, 'BRAND_EXECUTABLE_INVALID');
assertRegularFile(artifactPath, constants.R_OK, 'BRAND_ARTIFACT_INVALID');

const npmExecPath = assertExactToolchain();
const environment = loadApprovedEnvironment();
assertApprovedBaselineOracle();
const record = environment.web?.[brand]?.[channel];
if (!record || typeof record !== 'object' || Array.isArray(record)) {
  throw new Error(`BRAND_RECORD_MISSING: web.${brand}.${channel}`);
}

const targetOs = environment.os?.brandMatrixTarget;
if (typeof targetOs !== 'string' || targetOs.length === 0) {
  throw new Error('BRAND_MATRIX_TARGET_OS_REQUIRED');
}
if (record.targetOs !== targetOs) {
  throw new Error(
    `BRAND_RECORD_TARGET_OS_DRIFT: record=${String(record.targetOs)} matrix=${targetOs}`,
  );
}
if (typeof record.version !== 'string' || !VERSION_PATTERN.test(record.version)) {
  throw new Error(`BRAND_RECORD_VERSION_INVALID: web.${brand}.${channel}`);
}
if (record.major !== Number.parseInt(record.version.split('.')[0], 10)) {
  throw new Error(`BRAND_RECORD_MAJOR_DRIFT: web.${brand}.${channel}`);
}
if (
  typeof record.artifactUrl !== 'string'
  || !isApprovedArtifactUrl(record.artifactUrl, brand, record.version)
) {
  throw new Error(`BRAND_ARTIFACT_URL_INVALID: web.${brand}.${channel}`);
}
assertSha256(record.artifactSha256, `BRAND_ARTIFACT_SHA256_INVALID: web.${brand}.${channel}`);
if (record.executableSha256 !== undefined) {
  assertSha256(
    record.executableSha256,
    `BRAND_EXECUTABLE_SHA256_INVALID: web.${brand}.${channel}`,
  );
}

const actualOs = currentOperatingSystem();
if (actualOs !== targetOs) {
  throw new Error(`BRAND_OS_DRIFT: expected=${targetOs} actual=${actualOs}`);
}

const actualArtifactSha256 = await sha256File(artifactPath);
if (actualArtifactSha256 !== record.artifactSha256) {
  throw new Error(
    `BRAND_ARTIFACT_DRIFT: expected=${record.artifactSha256} actual=${actualArtifactSha256}`,
  );
}
const actualExecutableSha256 = await sha256File(executablePath);
if (
  record.executableSha256 !== undefined
  && actualExecutableSha256 !== record.executableSha256
) {
  throw new Error(
    `BRAND_EXECUTABLE_DRIFT: expected=${record.executableSha256} actual=${actualExecutableSha256}`,
  );
}

const host = ['127', '0', '0', '1'].join('.');
const port = '4173';
const baseURL = `http://${host}:${port}/scholarsense/`;
const vite = resolve(FRONTEND_ROOT, 'node_modules/vite/bin/vite.js');

let browser;
let server;
try {
  rmSync(DIST_PATH, { recursive: true, force: true });
  await runCommand(
    process.execPath,
    [npmExecPath, 'run', 'build'],
    { cwd: FRONTEND_ROOT, stdio: 'inherit' },
    'BRAND_CURRENT_SOURCE_BUILD_FAILED',
  );

  server = spawn(
    process.execPath,
    [vite, 'preview', '--host', host, '--port', port, '--strictPort'],
    { cwd: FRONTEND_ROOT, stdio: ['ignore', 'ignore', 'inherit'] },
  );
  await waitUntilAvailable(baseURL, server);

  browser = await chromium.launch({ executablePath });
  const actualVersion = browser.version();
  if (actualVersion !== record.version) {
    throw new Error(`BRAND_VERSION_DRIFT: expected=${record.version} actual=${actualVersion}`);
  }

  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  const userAgent = await page.evaluate(() => navigator.userAgent);
  assertBrowserBrand(brand, userAgent);
  const consoleErrors = [];
  const failedRequests = [];
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('requestfailed', (request) => failedRequests.push(request.url()));
  const response = await page.goto(baseURL);
  const heading = (await page.locator('h1').textContent())?.trim();
  if (
    response?.status() !== 200
    || heading !== 'ScholarSense 前端基线'
    || consoleErrors.length > 0
    || failedRequests.length > 0
  ) {
    throw new Error(
      'BRAND_PREFLIGHT_SMOKE_FAILED: '
      + `status=${response?.status()} heading=${heading} `
      + `console=${consoleErrors.join('|')} network=${failedRequests.join('|')}`,
    );
  }

  console.log(JSON.stringify({
    classification: 'optional-brand-preflight-not-formal-report',
    approvedRecordId: `${environment.environmentVersion}:web.${brand}.${channel}:${environment.contentSha256}`,
    manifestContentSha256: environment.contentSha256,
    brand,
    channel,
    approvedVersion: record.version,
    actualVersion,
    targetOs,
    actualOs,
    artifactUrl: record.artifactUrl,
    approvedArtifactSha256: record.artifactSha256,
    actualArtifactSha256,
    approvedExecutableSha256: record.executableSha256 ?? null,
    actualExecutableSha256,
    buildInput: 'current-source-clean-rebuild',
    viewport: '1440x900',
    result: 'PASS',
  }));
} finally {
  await browser?.close();
  await stopChild(server);
  rmSync(DIST_PATH, { recursive: true, force: true });
}

function parseArguments(values) {
  const approved = new Set(['--brand', '--channel', '--executable', '--artifact']);
  if (values.length !== approved.size * 2) {
    throw new Error(
      'usage: --brand <chrome|edge> --channel <current|previous> '
      + '--executable <path> --artifact <path>',
    );
  }
  const result = new Map();
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!approved.has(key) || !value || value.startsWith('--') || result.has(key)) {
      throw new Error(`PREFLIGHT_ARGUMENT_NOT_APPROVED: ${key}`);
    }
    result.set(key, value);
  }
  if (result.size !== approved.size) {
    throw new Error('PREFLIGHT_REQUIRED_ARGUMENT_MISSING');
  }
  return result;
}

function loadApprovedEnvironment() {
  const source = readFileSync(MANIFEST_PATH, 'utf8');
  const actualFileSha256 = createHash('sha256').update(source).digest('hex');
  if (actualFileSha256 !== APPROVED_ENVIRONMENT_FILE_SHA256) {
    throw new Error(
      `BRAND_ENVIRONMENT_FILE_DRIFT: expected=${APPROVED_ENVIRONMENT_FILE_SHA256} actual=${actualFileSha256}`,
    );
  }
  const value = JSON.parse(source);
  if (
    !value
    || typeof value !== 'object'
    || Array.isArray(value)
    || typeof value.environmentVersion !== 'string'
    || !SHA256_PATTERN.test(value.contentSha256)
  ) {
    throw new Error('BRAND_ENVIRONMENT_MANIFEST_INVALID');
  }
  if (value.contentSha256 !== APPROVED_ENVIRONMENT_CONTENT_SHA256) {
    throw new Error(
      `BRAND_ENVIRONMENT_VERSION_DRIFT: expected=${APPROVED_ENVIRONMENT_CONTENT_SHA256} actual=${value.contentSha256}`,
    );
  }
  const payload = { ...value };
  delete payload.contentSha256;
  const actualContentSha256 = createHash('sha256')
    .update(JSON.stringify(sortJson(payload)))
    .digest('hex');
  if (actualContentSha256 !== value.contentSha256) {
    throw new Error(
      `BRAND_ENVIRONMENT_MANIFEST_DIGEST_DRIFT: expected=${value.contentSha256} actual=${actualContentSha256}`,
    );
  }
  return value;
}

function sortJson(value) {
  if (Array.isArray(value)) return value.map(sortJson);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, sortJson(value[key])]),
    );
  }
  return value;
}

function assertRegularFile(path, mode, reason) {
  try {
    accessSync(path, mode);
    if (!statSync(path).isFile()) throw new Error('not a regular file');
  } catch {
    throw new Error(`${reason}: ${path}`);
  }
}

function assertSha256(value, reason) {
  if (typeof value !== 'string' || !SHA256_PATTERN.test(value)) {
    throw new Error(reason);
  }
}

function isApprovedArtifactUrl(value, expectedBrand, version) {
  try {
    const url = new URL(value);
    if (
      url.protocol !== 'https:'
      || url.username
      || url.password
      || url.port
      || url.search
      || url.hash
    ) {
      return false;
    }
    if (expectedBrand === 'chrome') {
      return (
        url.hostname === 'storage.googleapis.com'
        && url.pathname
          === `/chrome-for-testing-public/${version}/mac-arm64/chrome-mac-arm64.zip`
      );
    }
    if (expectedBrand === 'edge') {
      const match = url.pathname.match(
        /^\/filestreamingservice\/files\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(.+)$/,
      );
      return (
        url.hostname === 'msedge.sf.dl.delivery.mp.microsoft.com'
        && match?.[1] === `MicrosoftEdge-${version}.pkg`
      );
    }
    return false;
  } catch {
    return false;
  }
}

function assertBrowserBrand(expectedBrand, userAgent) {
  const isEdge = /\bEdg\//.test(userAgent);
  const isChrome = /\b(?:Chrome|HeadlessChrome)\//.test(userAgent) && !isEdge;
  if ((expectedBrand === 'edge' && !isEdge) || (expectedBrand === 'chrome' && !isChrome)) {
    throw new Error(`BRAND_EXECUTABLE_PRODUCT_DRIFT: expected=${expectedBrand}`);
  }
}

function currentOperatingSystem() {
  if (process.platform !== 'darwin') {
    throw new Error(`BRAND_OS_UNSUPPORTED: ${process.platform}`);
  }
  const productVersion = execFileSync('/usr/bin/sw_vers', ['-productVersion'], { encoding: 'utf8' }).trim();
  const buildVersion = execFileSync('/usr/bin/sw_vers', ['-buildVersion'], { encoding: 'utf8' }).trim();
  const machine = execFileSync('/usr/bin/uname', ['-m'], { encoding: 'utf8' }).trim();
  return `macOS ${productVersion} build ${buildVersion} ${machine}`;
}

function assertExactToolchain() {
  if (process.version !== 'v24.18.0') {
    throw new Error(`PREFLIGHT_NODE_VERSION_DRIFT: expected=v24.18.0 actual=${process.version}`);
  }
  const npmExecPath = process.env.npm_execpath;
  if (!npmExecPath) {
    throw new Error('PREFLIGHT_NPM_CONTEXT_REQUIRED');
  }
  const npmVersion = execFileSync(process.execPath, [npmExecPath, '--version'], { encoding: 'utf8' }).trim();
  if (npmVersion !== '11.16.0') {
    throw new Error(`PREFLIGHT_NPM_VERSION_DRIFT: expected=11.16.0 actual=${npmVersion}`);
  }
  return npmExecPath;
}

function assertApprovedBaselineOracle() {
  try {
    execFileSync('python3', [BASELINE_CHECKER_PATH, PROJECT_ROOT], {
      cwd: PROJECT_ROOT,
      encoding: 'utf8',
      env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (error) {
    const details = [error?.stdout, error?.stderr]
      .filter((value) => typeof value === 'string' && value.trim())
      .map((value) => value.trim())
      .join(' | ')
      .slice(0, 2000);
    throw new Error(
      `BRAND_BASELINE_ORACLE_REJECTED${details ? `: ${details}` : ''}`,
    );
  }
}

async function sha256File(path) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest('hex');
}

function runCommand(command, args, options, reason) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(command, args, options);
    child.once('error', (error) => rejectPromise(new Error(`${reason}: ${error.message}`)));
    child.once('exit', (code, signal) => {
      if (code === 0) resolvePromise();
      else rejectPromise(new Error(`${reason}: exit=${code} signal=${signal}`));
    });
  });
}

async function waitUntilAvailable(url, child) {
  let spawnError;
  child.once('error', (error) => {
    spawnError = error;
  });
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (spawnError) throw new Error(`PREFLIGHT_PREVIEW_FAILED: ${spawnError.message}`);
    if (child.exitCode !== null || child.signalCode !== null) {
      throw new Error(
        `PREFLIGHT_PREVIEW_EXITED: exit=${child.exitCode} signal=${child.signalCode}`,
      );
    }
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // The preview server has not bound its port yet.
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
  }
  throw new Error('PREFLIGHT_PREVIEW_TIMEOUT');
}

async function stopChild(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  const exited = once(child, 'exit');
  child.kill('SIGTERM');
  let timeout;
  const force = new Promise((resolveDelay) => {
    timeout = setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
      resolveDelay();
    }, 5000);
  });
  try {
    await Promise.race([exited, force]);
  } finally {
    clearTimeout(timeout);
  }
}
