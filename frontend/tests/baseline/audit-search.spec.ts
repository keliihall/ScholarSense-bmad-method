import { createRequire } from 'node:module';
import { expect, test } from '@playwright/test';

const axePath = createRequire(import.meta.url).resolve('axe-core/axe.min.js');
const session = {
  authenticated: true,
  sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw',
  sessionVersion: 7,
  expiresAt: '2099-07-20T02:00:00Z',
  warningAt: '2099-07-20T01:55:00Z',
  profileVersion: 'ISP-1.0.0',
};

test.beforeEach(async ({ page }) => {
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(session),
  }));
  await page.route(/\/api\/v1\/identity-sessions\/csrf$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify(Object.fromEntries([
      ['headerName', 'X-CSRF-TOKEN'], ['token', 'abcdefghijklmnopqrstuvwxyzABCDEF'],
    ])),
  }));
});

test('deep link renders only projected fields and never persists sensitive filters', async ({ page }, testInfo) => {
  let body: Record<string, unknown> = {};
  await page.route(/\/api\/v1\/audit-records\/search$/, async (route) => {
    body = route.request().postDataJSON() as Record<string, unknown>;
    expect(route.request().headers()['x-csrf-token']).toBe('abcdefghijklmnopqrstuvwxyzABCDEF');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [{ fields: {
        recordId: '019d2c7d-4000-7000-8000-000000000042', ledgerSequence: 42,
        occurredAt: '2026-07-23T00:00:00Z', outcome: 'success',
        businessActionCategory: 'identity', actorDisplayRef: 'actor-00000001',
      } }],
      page: 0, size: 25, total: 1, asOfSequence: 42, sourceLedgerHead: 44,
      projectionWatermark: 42, dataCutoffAt: '2026-07-23T00:00:00Z',
      retentionScheduleVersion: 'RS-1.0.0', roleFieldPolicyVersion: 'RFP-1.0.0',
      projectionStatus: 'degraded',
    }) });
  });

  await page.goto('audit/search');
  await expect(page.getByRole('heading', { name: '授权审计检索' })).toBeFocused();
  await page.getByLabel('用户标识').fill('student-sensitive');
  await page.getByLabel('对象标识').fill('object-sensitive');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('投影正在追赶')).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'actorDisplayRef' })).toBeVisible();
  expect(body).toMatchObject({ actorRef: 'student-sensitive', objectRef: 'object-sensitive' });
  expect(page.url()).not.toContain('student-sensitive');
  expect(page.url()).not.toContain('object-sensitive');
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length })))
    .toEqual({ local: 0, session: 0 });
  const dom = await page.locator('html').innerText();
  for (const forbidden of ['payload', 'actorSearchToken', 'objectSearchToken', 'archiveObjectUrl']) {
    expect(dom).not.toContain(forbidden);
  }
  await expect(page.getByRole('button', { name: /导出|下载|归档|销毁/ })).toHaveCount(0);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);

  if (testInfo.project.name === 'desktop-reference') {
    await page.addScriptTag({ path: axePath });
    const violations = await page.evaluate(async () => {
      const axe = (window as unknown as { axe: { run: () => Promise<{ violations: unknown[] }> } }).axe;
      return (await axe.run()).violations;
    });
    expect(violations).toEqual([]);
    await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
    await expect(page.getByRole('button', { name: '查询', exact: true })).toBeVisible();
    const zoomOverflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(zoomOverflow).toBeLessThanOrEqual(1);
  }
});

test('empty, filtered-empty and forbidden states each expose one recovery action', async ({ page }) => {
  let calls = 0;
  await page.route(/\/api\/v1\/audit-records\/search$/, async (route) => {
    calls += 1;
    if (calls === 3) {
      await route.fulfill({ status: 403, contentType: 'application/json',
        body: JSON.stringify({ code: 'AUDIT_SEARCH_FORBIDDEN' }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [], page: 0, size: 25, total: 0, asOfSequence: 0, sourceLedgerHead: 0,
      projectionWatermark: 0, dataCutoffAt: '2026-07-23T00:00:00Z',
      retentionScheduleVersion: 'RS-1.0.0', roleFieldPolicyVersion: 'RFP-1.0.0',
      projectionStatus: 'current',
    }) });
  });
  await page.goto('audit/search?action=identity.login');
  const state = page.locator('.audit-result-state');
  await expect(state).toContainText('当前筛选没有匹配记录');
  await expect(state.getByRole('button')).toHaveCount(1);
  await state.getByRole('button', { name: '清除筛选' }).click();
  await expect(state).toContainText('当前完整快照中没有审计记录');
  await expect(state.getByRole('button')).toHaveCount(1);
  await state.getByRole('button', { name: '重新查询' }).click();
  await expect(state).toContainText('记录不存在或当前用途无权查看');
  await expect(state.getByRole('button')).toHaveCount(1);
});

test('session expiry stores only audit.search continuation route id', async ({ page }) => {
  await page.unroute(/\/api\/v1\/identity-sessions\/current$/);
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({
    status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'IDENTITY_SESSION_EXPIRED' }),
  }));
  let continuation: unknown;
  await page.route(/\/api\/v1\/identity-sessions\/reauthentications$/, async (route) => {
    continuation = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      continuationCode: 'ct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
      expiresAt: '2099-07-20T02:15:00Z', authorizationUri: '/oauth2/authorization/school-idp',
    }) });
  });
  await page.goto('audit/search?action=identity.login');
  await expect(page).toHaveURL(/targetRouteId=audit.search$/);
  expect(continuation).toEqual({ targetRouteId: 'audit.search', origin: new URL(page.url()).origin });
  expect(JSON.stringify(continuation)).not.toContain('identity.login');
});
