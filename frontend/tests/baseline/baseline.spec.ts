import { createRequire } from 'node:module';

import { expect, test } from '@playwright/test';


const require = createRequire(import.meta.url);
const axePath = require.resolve('axe-core/axe.min.js');

test.describe('deterministic local baseline fixture', () => {
  test('serves the SPA and assets from the approved base without console or network errors', async ({ page }) => {
    const consoleErrors: string[] = [];
    const failedRequests: string[] = [];
    page.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(message.text());
    });
    page.on('requestfailed', (request) => failedRequests.push(request.url()));

    const response = await page.goto('./');
    expect(response?.status()).toBe(200);
    await expect(page.getByRole('heading', { name: 'ScholarSense 前端基线' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '生产启动面已建立' })).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/scholarsense/');
    expect(consoleErrors).toEqual([]);
    expect(failedRequests).toEqual([]);

    const resourcePaths = await page.evaluate(() =>
      performance.getEntriesByType('resource').map((entry) => new URL(entry.name).pathname),
    );
    expect(resourcePaths.length).toBeGreaterThan(0);
    expect(resourcePaths.every((path) => path.startsWith('/scholarsense/'))).toBe(true);
  });

  test('freezes endpoint-specific tokens, hit targets and horizontal reflow', async ({ page }, testInfo) => {
    await page.goto('./');
    const tokens = await page.evaluate(() => {
      const styles = getComputedStyle(document.documentElement);
      return {
        web: styles.getPropertyValue('--ss-web-primary').trim().toUpperCase(),
        hover: styles.getPropertyValue('--ss-web-primary-hover').trim().toUpperCase(),
        pressed: styles.getPropertyValue('--ss-web-primary-pressed').trim().toUpperCase(),
        mobile: styles.getPropertyValue('--ss-mobile-primary').trim().toUpperCase(),
      };
    });
    expect(tokens).toEqual({ web: '#AF251B', hover: '#C53227', pressed: '#A7180D', mobile: '#D03D37' });

    const firstAction = page.locator('.action-target').first();
    const box = await firstAction.boundingBox();
    expect(box).not.toBeNull();
    const expectedMinimum = testInfo.project.name === 'responsive-375' ? 44 : 40;
    expect(box!.height).toBeGreaterThanOrEqual(expectedMinimum);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
  });

  test('supports keyboard focus, live status and non-color network state', async ({ page }) => {
    await page.goto('./');
    await page.keyboard.press('Tab');
    const skipLink = page.getByRole('link', { name: '跳至主要内容' });
    await expect(skipLink).toBeFocused();
    await expect(skipLink).toBeVisible();
    await page.keyboard.press('Enter');
    await expect(page.locator('#main-content')).toBeFocused();

    const status = page.getByRole('status');
    await expect(status).toContainText('基线连接可用');
    await expect(status).toHaveAttribute('aria-live', 'polite');
    const border = await status.evaluate((element) => getComputedStyle(element).borderLeftWidth);
    expect(Number.parseFloat(border)).toBeGreaterThan(0);
  });

  test('provides chart equivalent data and assistive feedback', async ({ page }) => {
    await page.goto('compatibility');
    await expect(page.getByRole('heading', { name: '图表与等价表格 fixture' })).toBeVisible();
    await expect(page.getByRole('table', { name: '图表等价数据' })).toBeVisible();
    await expect(page.getByRole('status')).toContainText('已同步更新');
    await expect(page.locator('canvas')).toBeVisible();
    expect(await page.getByRole('row').count()).toBe(4);
  });

  test('passes the local axe baseline without leaking forbidden object fields', async ({ page }) => {
    await page.goto('compatibility');
    await page.addScriptTag({ path: axePath });
    const violations = await page.evaluate(async () => {
      const axe = (window as unknown as { axe: { run: (root: Document) => Promise<{ violations: unknown[] }> } }).axe;
      return (await axe.run(document)).violations;
    });
    expect(violations).toEqual([]);
    const dom = await page.locator('html').innerText();
    for (const forbidden of ['studentName', 'evidenceText', 'authorizationResult']) {
      expect(dom).not.toContain(forbidden);
    }
  });

  test('honors reduced motion', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('./');
    const duration = await page.locator('.action-target').first().evaluate((element) =>
      getComputedStyle(element).transitionDuration,
    );
    expect(['0s', '0.001s']).toContain(duration);
  });

  test('keeps critical content intact at real 200-percent CSS zoom', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop-reference', 'single deterministic zoom fixture');
    await page.goto('./');
    await page.evaluate(() => {
      document.documentElement.style.zoom = '2';
    });

    const keyContent = [
      page.getByRole('heading', { name: 'ScholarSense 前端基线' }),
      page.getByRole('heading', { name: '生产启动面已建立' }),
      page.getByText('本页只验证生产前端组合，不含学生对象、SSO 或业务 API。'),
      page.getByRole('button', { name: '连接恢复后显式重试' }),
    ];
    for (const locator of keyContent) await expect(locator).toBeVisible();

    const zoomLayout = await page.evaluate(() => {
      const selectors = ['h1', '#baseline-heading', '.el-alert__title', '.explicit-retry'];
      const viewportWidth = document.documentElement.clientWidth;
      return {
        zoom: Number.parseFloat(getComputedStyle(document.documentElement).zoom),
        overflow: document.documentElement.scrollWidth - viewportWidth,
        keyRects: selectors.map((selector) => {
          const element = document.querySelector(selector);
          if (!(element instanceof HTMLElement)) return null;
          const rect = element.getBoundingClientRect();
          return {
            selector,
            left: rect.left,
            right: rect.right,
            width: rect.width,
            height: rect.height,
            clipped: (
              rect.left < -1
              || rect.right > viewportWidth + 1
              || element.scrollWidth > element.clientWidth + 1
              || element.scrollHeight > element.clientHeight + 1
            ),
          };
        }),
      };
    });
    expect(zoomLayout.zoom).toBe(2);
    expect(zoomLayout.overflow).toBeLessThanOrEqual(1);
    expect(zoomLayout.keyRects).not.toContain(null);
    expect(zoomLayout.keyRects.every((rect) => (
      rect !== null && rect.width > 0 && rect.height > 0 && !rect.clipped
    ))).toBe(true);
  });

  test('keeps critical content at the narrow 320px reflow boundary', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop-reference', 'single deterministic reflow fixture');
    await page.setViewportSize({ width: 320, height: 720 });
    await page.goto('./');
    await expect(page.getByRole('heading', { name: 'ScholarSense 前端基线' })).toBeVisible();
    await expect(page.getByRole('button', { name: '连接恢复后显式重试' })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
  });
});
