import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { createReadStream, existsSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';

import { chromium } from '@playwright/test';


const require = createRequire(import.meta.url);
const axePath = require.resolve('axe-core/axe.min.js');
const BASE_PATH = '/scholarsense/';
const MIME = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
]);
const CHECK_NAMES = [
  'artifactServed',
  'resources',
  'console',
  'network',
  'keyboardFocus',
  'axe',
  'zoom200',
  'reflow320',
  'uiTokens',
  'brandAssets',
];


export function loadJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}


export function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}


export function sortJson(value) {
  if (Array.isArray(value)) return value.map(sortJson);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortJson(value[key])]));
  }
  return value;
}


export function canonicalSha256(value) {
  return createHash('sha256').update(JSON.stringify(sortJson(value))).digest('hex');
}


export function buildMatrixTemplate(testEnvironment, artifactSha256, runnerImageSha256) {
  const viewports = new Map(
    testEnvironment.viewports
      .filter((item) => ['desktop-reference', 'desktop-minimum'].includes(item.id))
      .map((item) => [item.id, item]),
  );
  const cells = [];
  for (const browser of ['chrome', 'edge']) {
    for (const channel of ['current', 'previous']) {
      const record = testEnvironment.web[browser][channel];
      for (const viewport of viewports.values()) {
        cells.push({
          id: `${browser}-${channel}-${viewport.id}`,
          browser,
          channel,
          major: record.major,
          browserVersion: record.version,
          browserArtifactSha256: record.artifactSha256,
          executableSha256: record.executableSha256,
          osBuild: testEnvironment.os.brandMatrixTarget,
          viewport: viewport.id,
          width: viewport.width,
          height: viewport.height,
          deviceScaleFactor: 1,
          locale: 'zh-CN',
          timezone: 'Asia/Shanghai',
          clock: '2026-07-19T01:00:00Z',
          networkProfile: 'frozen-local-static-only',
          dataProfile: 'deterministic-baseline-fixture',
          goldenPath: `goldens/${browser}-${channel}-${viewport.id}.png`,
          goldenUri: null,
          goldenScreenshotSha256: null,
        });
      }
    }
  }
  return {
    version: 'VGB-1.0.0',
    status: 'candidate',
    approvedArtifactSha256: artifactSha256,
    runnerImageSha256,
    cells,
  };
}


function ensure(condition, code, details = '') {
  if (!condition) throw new Error(`${code}${details ? `: ${details}` : ''}`);
}


function contentType(path) {
  return MIME.get(extname(path).toLowerCase()) ?? 'application/octet-stream';
}


async function startFrozenServer(servedRoot) {
  const root = resolve(servedRoot);
  const rootStat = statSync(root);
  ensure(rootStat.isDirectory(), 'FORMAL_WEB_SERVED_ROOT_INVALID');
  ensure((rootStat.mode & 0o222) === 0, 'FORMAL_WEB_SERVED_ROOT_WRITABLE');
  const server = createServer((request, response) => {
    try {
      ensure(request.method === 'GET' || request.method === 'HEAD', 'FORMAL_WEB_HTTP_METHOD_FORBIDDEN');
      const url = new URL(request.url, 'http://formal.invalid');
      ensure(url.pathname.startsWith(BASE_PATH), 'FORMAL_WEB_HTTP_PATH_FORBIDDEN');
      const decoded = decodeURIComponent(url.pathname.slice(BASE_PATH.length));
      ensure(!decoded.includes('\\') && !decoded.includes('\0'), 'FORMAL_WEB_HTTP_PATH_UNSAFE');
      const candidate = decoded && extname(decoded) ? decoded : 'index.html';
      const normalized = normalize(candidate);
      ensure(normalized !== '..' && !normalized.startsWith(`..${sep}`), 'FORMAL_WEB_HTTP_PATH_UNSAFE');
      const path = resolve(root, normalized);
      ensure(path === root || path.startsWith(`${root}${sep}`), 'FORMAL_WEB_HTTP_PATH_UNSAFE');
      ensure(existsSync(path) && statSync(path).isFile(), 'FORMAL_WEB_HTTP_FILE_MISSING');
      response.writeHead(200, {
        'cache-control': 'no-store',
        'content-type': contentType(path),
        'x-content-type-options': 'nosniff',
      });
      if (request.method === 'HEAD') response.end();
      else createReadStream(path).pipe(response);
    } catch (error) {
      response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      response.end(String(error.message));
    }
  });
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolvePromise);
  });
  const address = server.address();
  ensure(address && typeof address === 'object', 'FORMAL_WEB_SERVER_ADDRESS_INVALID');
  return {
    baseURL: `http://127.0.0.1:${address.port}${BASE_PATH}`,
    close: () => new Promise((resolvePromise, reject) => server.close((error) => (
      error ? reject(error) : resolvePromise()
    ))),
  };
}


function browserRecords(browserInstall) {
  ensure(browserInstall.version === 'FORMAL-BROWSER-INSTALL-1.0.0', 'FORMAL_WEB_BROWSER_INSTALL_INVALID');
  return new Map(browserInstall.browsers.map((item) => [item.id, item]));
}


async function checkPage(page, baseURL, uiTokens, brandAssets) {
  const consoleErrors = [];
  const failedRequests = [];
  const badResponses = [];
  const requested = [];
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('request', (request) => requested.push(request.url()));
  page.on('requestfailed', (request) => failedRequests.push(request.url()));
  page.on('response', (response) => {
    if (response.status() >= 400) badResponses.push(`${response.status()}:${response.url()}`);
  });

  const response = await page.goto(baseURL, { waitUntil: 'networkidle' });
  ensure(response?.status() === 200, 'FORMAL_WEB_ENTRYPOINT_STATUS_INVALID');
  ensure((await page.locator('h1').textContent())?.trim() === 'ScholarSense 前端基线', 'FORMAL_WEB_HEADING_INVALID');
  ensure(await page.getByText('生产启动面已建立').isVisible(), 'FORMAL_WEB_PRODUCTION_ENTRY_MISSING');
  ensure(new URL(page.url()).pathname === BASE_PATH, 'FORMAL_WEB_BASE_PATH_DRIFT');

  const actualTokens = await page.evaluate((names) => {
    const styles = getComputedStyle(document.documentElement);
    return Object.fromEntries(names.map((name) => [name, styles.getPropertyValue(name).trim()]));
  }, uiTokens.tokens.map((item) => item.name));
  for (const token of uiTokens.tokens) {
    ensure(actualTokens[token.name].toLowerCase() === token.value.toLowerCase(), 'FORMAL_WEB_UI_TOKEN_DRIFT', token.name);
  }

  const icon = await page.locator('link[rel="icon"]').getAttribute('href');
  ensure(icon?.startsWith('data:image/svg+xml,'), 'FORMAL_WEB_BRAND_ASSET_MISSING');
  const iconBytes = Buffer.from(decodeURIComponent(icon.split(',', 2)[1]), 'utf8');
  const iconDigest = createHash('sha256').update(iconBytes).digest('hex');
  ensure(brandAssets.assets.length === 1, 'FORMAL_WEB_BRAND_ASSET_SET_INVALID');
  ensure(iconDigest === brandAssets.assets[0].binarySha256, 'FORMAL_WEB_BRAND_ASSET_DRIFT');

  await page.keyboard.press('Tab');
  ensure((await page.locator(':focus').textContent())?.trim() === '跳至主要内容', 'FORMAL_WEB_KEYBOARD_FOCUS_INVALID');
  await page.keyboard.press('Enter');
  ensure(await page.locator('#main-content').evaluate((element) => element === document.activeElement), 'FORMAL_WEB_SKIP_LINK_INVALID');
  ensure((await page.getByRole('status').textContent()).includes('基线连接可用'), 'FORMAL_WEB_STATUS_SEMANTICS_INVALID');

  await page.goto(new URL('compatibility', baseURL).href, { waitUntil: 'networkidle' });
  ensure(await page.getByRole('table', { name: '图表等价数据' }).isVisible(), 'FORMAL_WEB_CHART_EQUIVALENT_TABLE_MISSING');
  ensure(await page.locator('canvas').isVisible(), 'FORMAL_WEB_CHART_MISSING');
  await page.addScriptTag({ path: axePath });
  const violations = await page.evaluate(async () => (await globalThis.axe.run(document)).violations);
  ensure(violations.length === 0, 'FORMAL_WEB_AXE_VIOLATION', JSON.stringify(violations));

  await page.goto(baseURL, { waitUntil: 'networkidle' });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setPageScaleFactor', { pageScaleFactor: 2 });
  const zoom = await page.evaluate(() => {
    const selectors = ['h1', '#baseline-heading', '.el-alert__title', '.explicit-retry'];
    const viewportWidth = document.documentElement.clientWidth;
    return {
      value: visualViewport?.scale ?? 0,
      overflow: document.documentElement.scrollWidth - viewportWidth,
      invalid: selectors.some((selector) => {
        const element = document.querySelector(selector);
        if (!(element instanceof HTMLElement)) return true;
        const rect = element.getBoundingClientRect();
        return rect.width <= 0 || rect.height <= 0 || rect.left < -1 || rect.right > viewportWidth + 1
          || element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1;
      }),
    };
  });
  ensure(
    zoom.value === 2 && zoom.overflow <= 1 && !zoom.invalid,
    'FORMAL_WEB_ZOOM_200_INVALID',
    JSON.stringify(zoom),
  );

  await cdp.send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 });
  await page.setViewportSize({ width: 320, height: 720 });
  ensure(await page.getByRole('heading', { name: 'ScholarSense 前端基线' }).isVisible(), 'FORMAL_WEB_REFLOW_HEADING_MISSING');
  ensure(await page.getByRole('button', { name: '连接恢复后显式重试' }).isVisible(), 'FORMAL_WEB_REFLOW_ACTION_MISSING');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  ensure(overflow <= 1, 'FORMAL_WEB_REFLOW_OVERFLOW');

  const origin = new URL(baseURL).origin;
  ensure(requested.length > 1, 'FORMAL_WEB_RESOURCE_SET_EMPTY');
  ensure(requested.every((value) => {
    const url = new URL(value);
    return url.origin === origin && url.pathname.startsWith(BASE_PATH);
  }), 'FORMAL_WEB_EXTERNAL_NETWORK_FORBIDDEN');
  ensure(consoleErrors.length === 0, 'FORMAL_WEB_CONSOLE_ERROR', consoleErrors.join('|'));
  ensure(failedRequests.length === 0, 'FORMAL_WEB_NETWORK_FAILURE', failedRequests.join('|'));
  ensure(badResponses.length === 0, 'FORMAL_WEB_BAD_RESPONSE', badResponses.join('|'));
}


export async function runFormalMatrix({
  servedRoot,
  browserInstall,
  oracle,
  uiTokens,
  brandAssets,
  outputRoot,
  goldenRoot = null,
  mode,
}) {
  ensure(['capture', 'verify'].includes(mode), 'FORMAL_WEB_MODE_INVALID');
  const install = browserRecords(browserInstall);
  const server = await startFrozenServer(servedRoot);
  const results = [];
  try {
    for (const cell of oracle.cells) {
      const browserRecord = install.get(`${cell.browser}-${cell.channel}`);
      ensure(browserRecord, 'FORMAL_WEB_BROWSER_RECORD_MISSING', cell.id);
      ensure(browserRecord.version === cell.browserVersion, 'FORMAL_WEB_BROWSER_VERSION_INPUT_DRIFT', cell.id);
      ensure(browserRecord.executableSha256 === cell.executableSha256, 'FORMAL_WEB_BROWSER_EXECUTABLE_INPUT_DRIFT', cell.id);
      ensure(sha256File(browserRecord.executablePath) === cell.executableSha256, 'FORMAL_WEB_BROWSER_EXECUTABLE_TAMPER', cell.id);
      const browser = await chromium.launch({
        executablePath: browserRecord.executablePath,
        headless: true,
        args: [
          '--disable-background-networking',
          '--disable-component-update',
          '--disable-default-apps',
          '--disable-sync',
          '--metrics-recording-only',
          '--no-default-browser-check',
          '--no-first-run',
        ],
      });
      try {
        ensure(browser.version() === cell.browserVersion, 'FORMAL_WEB_BROWSER_RUNTIME_VERSION_DRIFT', cell.id);
        const context = await browser.newContext({
          viewport: { width: cell.width, height: cell.height },
          deviceScaleFactor: cell.deviceScaleFactor,
          locale: cell.locale,
          timezoneId: cell.timezone,
          colorScheme: oracle.colorScheme ?? 'light',
          reducedMotion: oracle.reducedMotion ?? 'reduce',
          serviceWorkers: 'block',
        });
        await context.addInitScript(({ fixedClock }) => {
          const RealDate = Date;
          const fixed = new RealDate(fixedClock).valueOf();
          class FixedDate extends RealDate {
            constructor(...args) { super(...(args.length ? args : [fixed])); }
            static now() { return fixed; }
          }
          globalThis.Date = FixedDate;
        }, { fixedClock: cell.clock });
        const page = await context.newPage();
        await checkPage(page, server.baseURL, uiTokens, brandAssets);
        await page.setViewportSize({ width: cell.width, height: cell.height });
        await page.goto(server.baseURL, { waitUntil: 'networkidle' });
        await page.evaluate(() => document.fonts.ready);
        const screenshotPath = resolve(outputRoot, cell.goldenPath);
        const screenshot = await page.screenshot({
          animations: 'disabled',
          caret: 'hide',
          fullPage: false,
          path: screenshotPath,
          scale: 'css',
        });
        const actualDigest = createHash('sha256').update(screenshot).digest('hex');
        if (mode === 'verify') {
          ensure(goldenRoot, 'FORMAL_WEB_GOLDEN_ROOT_MISSING');
          const goldenPath = resolve(goldenRoot, cell.goldenPath);
          ensure(existsSync(goldenPath), 'FORMAL_WEB_GOLDEN_MISSING', cell.id);
          const goldenDigest = sha256File(goldenPath);
          ensure(goldenDigest === cell.goldenScreenshotSha256, 'FORMAL_WEB_GOLDEN_TAMPER', cell.id);
          ensure(actualDigest === goldenDigest, 'FORMAL_WEB_VISUAL_DRIFT', cell.id);
        }
        results.push({
          id: cell.id,
          browser: cell.browser,
          channel: cell.channel,
          major: cell.major,
          browserVersion: cell.browserVersion,
          viewport: cell.viewport,
          executableSha256: cell.executableSha256,
          actualScreenshotSha256: actualDigest,
          actualScreenshotPath: `screenshots/${cell.goldenPath}`,
          goldenScreenshotSha256: mode === 'verify' ? cell.goldenScreenshotSha256 : actualDigest,
          diffPixels: 0,
          checks: Object.fromEntries(CHECK_NAMES.map((name) => [name, 'passed'])),
          result: 'passed',
        });
        await context.close();
      } finally {
        await browser.close();
      }
    }
  } finally {
    await server.close();
  }
  return results;
}
