import { createRequire } from 'node:module';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { authorizedShell, installAuthorizedShellRoute } from './authorized-shell-fixture';

const axePath = createRequire(import.meta.url).resolve('axe-core/axe.min.js');
const session = {
  authenticated: true,
  sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw',
  sessionVersion: 7,
  expiresAt: '2099-07-20T02:00:00Z',
  warningAt: '2099-07-20T01:55:00Z',
  profileVersion: 'ISP-1.0.0',
};

function searchResponse(
  fields: Record<string, string | number | boolean>,
  projectionStatus: 'current' | 'degraded' = 'current',
) {
  return {
    items: [{ fields }],
    page: 0, size: 25, total: 1, asOfSequence: 42, sourceLedgerHead: 44,
    projectionWatermark: 42, dataCutoffAt: '2026-07-23T00:00:00Z',
    retentionScheduleVersion: 'RS-1.0.0', roleFieldPolicyVersion: 'RFP-1.0.0',
    projectionStatus,
  } as const;
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}

async function browserPersistenceSnapshot(page: Page) {
  return page.evaluate(async () => ({
    localStorageEntries: localStorage.length,
    sessionStorageEntries: sessionStorage.length,
    indexedDatabases: typeof indexedDB.databases === 'function'
      ? (await indexedDB.databases()).map((database) => database.name ?? '') : [],
    cacheNames: 'caches' in window ? await caches.keys() : [],
    serviceWorkers: 'serviceWorker' in navigator
      ? (await navigator.serviceWorker.getRegistrations()).length : 0,
    url: window.location.href,
    historyState: JSON.stringify(history.state),
  }));
}

test.beforeEach(async ({ page }) => {
  await installAuthorizedShellRoute(page);
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

test('global shell degradation does not disable an independently available audit capability', async ({ page }) => {
  await page.unroute(/\/api\/v1\/authorized-shell$/);
  await installAuthorizedShellRoute(page, {
    ...authorizedShell,
    dependencyStatus: 'unavailable',
  });
  await page.route(/\/api\/v1\/audit-records\/search$/, (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(searchResponse({
      recordId: '019d2c7d-4000-7000-8000-000000000042',
      ledgerSequence: 42,
    })),
  }));

  await page.goto('audit/search');

  await expect(page.getByText('019d2c7d-4000-7000-8000-000000000042')).toBeVisible();
  await expect(page.getByText('记录不存在或当前用途无权查看。')).toHaveCount(0);
});

test('deep link renders only projected fields and never persists sensitive filters', async ({ page }, testInfo) => {
  let body: Record<string, unknown> = {};
  await page.route(/\/api\/v1\/audit-records\/search$/, async (route) => {
    body = route.request().postDataJSON() as Record<string, unknown>;
    expect(route.request().headers()['x-csrf-token']).toBe('abcdefghijklmnopqrstuvwxyzABCDEF');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(
      searchResponse({
        recordId: '019d2c7d-4000-7000-8000-000000000042', ledgerSequence: 42,
        occurredAt: '2026-07-23T00:00:00Z', outcome: 'success',
        businessActionCategory: 'identity', actorDisplayRef: '[MASKED-IDENTITY]',
      }, 'degraded'),
    ) });
  });

  await page.goto('audit/search');
  await expect(page.getByRole('heading', { name: '授权审计检索' })).toBeFocused();
  await page.getByLabel('用户标识').fill('student-sensitive');
  await page.getByLabel('对象标识').fill('object-sensitive');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('投影正在追赶')).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'actorDisplayRef' })).toBeVisible();
  await expect(page.getByLabel('已脱敏')).toHaveText('[MASKED-IDENTITY]');
  await expect(page.locator('.audit-result-state')).toHaveAttribute('role', 'status');
  await expect(page.locator('.audit-result-state')).toHaveAttribute('aria-live', 'polite');
  await page.getByRole('button', { name: '查询', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('columnheader', { name: 'actorDisplayRef' })).toBeVisible();
  await page.getByRole('button', { name: '清除筛选' }).focus();
  await expect(page.getByRole('button', { name: '清除筛选' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: '刷新到最新完整快照' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.locator('.audit-table-wrap')).toBeFocused();
  expect(body).toMatchObject({ actorRef: 'student-sensitive', objectRef: 'object-sensitive' });
  expect(page.url()).not.toContain('student-sensitive');
  expect(page.url()).not.toContain('object-sensitive');
  const persistence = await browserPersistenceSnapshot(page);
  expect(persistence).toMatchObject({
    localStorageEntries: 0, sessionStorageEntries: 0, indexedDatabases: [], cacheNames: [],
    serviceWorkers: 0,
  });
  expect(JSON.stringify(persistence)).not.toContain('student-sensitive');
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
    await expect(page.getByLabel('已脱敏')).toBeVisible();
    await expect(page.locator('.audit-table-wrap')).toBeVisible();
    const zoomOverflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(zoomOverflow).toBeLessThanOrEqual(1);
  }
});

test('switching purpose view clears old columns before the new projection arrives', async ({ page }) => {
  let calls = 0;
  const releaseTechnical = deferred();
  const releaseBusinessReturn = deferred();
  await page.route(/\/api\/v1\/audit-records\/search$/, async (route) => {
    calls += 1;
    const request = route.request().postDataJSON() as { view: string };
    if (request.view === 'technical') {
      await releaseTechnical.promise;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(
        searchResponse({
          recordId: '019d2c7d-4000-7000-8000-000000000042',
          traceId: 'trace-safe', businessActionCategory: '[MASKED-CATEGORY]',
        }),
      ) });
      return;
    }
    if (calls > 1) await releaseBusinessReturn.promise;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(
      searchResponse({
        recordId: '019d2c7d-4000-7000-8000-000000000042',
        actorDisplayRef: '[MASKED-IDENTITY]', businessActionCategory: 'identity',
      }),
    ) });
  });

  await page.goto('audit/search');
  await expect(page.getByRole('columnheader', { name: 'actorDisplayRef' })).toBeVisible();
  await page.getByLabel('用途视图').selectOption('technical');
  await expect.poll(() => calls).toBe(2);
  await expect(page.getByRole('table')).toHaveCount(0);
  await expect(page.getByText('正在冻结快照并检索…')).toBeVisible();
  releaseTechnical.resolve();
  await expect(page.getByRole('columnheader', { name: 'traceId' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'actorDisplayRef' })).toHaveCount(0);
  await expect(page.getByLabel('已脱敏')).toHaveText('[MASKED-CATEGORY]');

  await page.getByLabel('用途视图').selectOption('business');
  await expect.poll(() => calls).toBe(3);
  await expect(page.getByRole('table')).toHaveCount(0);
  releaseBusinessReturn.resolve();
  await expect(page.getByRole('columnheader', { name: 'actorDisplayRef' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'traceId' })).toHaveCount(0);
  await expect(page.getByLabel('已脱敏')).toHaveText('[MASKED-IDENTITY]');
});

test('an aborted search cannot clear the newer view or rapid-search result', async ({ page }) => {
  let calls = 0;
  const initialStarted = deferred();
  const releaseInitial = deferred();
  const technicalStarted = deferred();
  const releaseTechnical = deferred();
  const staleStarted = deferred();
  const releaseStale = deferred();
  const newestStarted = deferred();
  const releaseNewest = deferred();
  await page.route(/\/api\/v1\/audit-records\/search$/, async (route) => {
    calls += 1;
    const currentCall = calls;
    const controls = [
      [initialStarted, releaseInitial], [technicalStarted, releaseTechnical],
      [staleStarted, releaseStale], [newestStarted, releaseNewest],
    ] as const;
    controls[currentCall - 1]?.[0].resolve();
    await controls[currentCall - 1]?.[1].promise;
    const response = currentCall === 1
      ? searchResponse({ recordId: 'record-business', actorDisplayRef: '[MASKED-IDENTITY]' })
      : searchResponse({
          recordId: `record-technical-${currentCall}`,
          traceId: currentCall === 3 ? 'trace-stale' : `trace-current-${currentCall}`,
          businessActionCategory: '[MASKED-CATEGORY]',
        });
    try {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(response) });
    } catch {
      // An intentionally superseded browser request can close before the fixture is released.
    }
  });

  await page.goto('audit/search');
  await initialStarted.promise;
  await page.getByLabel('用途视图').selectOption('technical');
  await technicalStarted.promise;
  releaseInitial.resolve();
  await expect(page.getByRole('table')).toHaveCount(0);
  releaseTechnical.resolve();
  await expect(page.getByText('trace-current-2')).toBeVisible();

  await page.getByRole('button', { name: '查询', exact: true }).click();
  await staleStarted.promise;
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await newestStarted.promise;
  releaseStale.resolve();
  await expect(page.getByRole('table')).toHaveCount(0);
  releaseNewest.resolve();
  await expect(page.getByText('trace-current-4')).toBeVisible();
  await expect(page.getByText('trace-stale')).toHaveCount(0);
  expect(calls).toBe(4);
});

test('an omitted optional field stays an empty cell without a false mask', async ({ page }) => {
  const response = {
    ...searchResponse({}),
    items: [
      { fields: { recordId: 'record-1', actorDisplayRef: '[MASKED-IDENTITY]' } },
      { fields: { recordId: 'record-2' } },
    ],
    total: 2,
  };
  await page.route(/\/api\/v1\/audit-records\/search$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(response),
  }));

  await page.goto('audit/search');
  await expect(page.getByRole('columnheader', { name: 'actorDisplayRef' })).toBeVisible();
  await expect(page.getByLabel('已脱敏')).toHaveCount(1);
  const rows = page.locator('.audit-table-wrap tbody tr');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(1).locator('td').nth(1)).toHaveText('');
  await expect(page.locator('html')).not.toContainText('undefined');
});

test('pagination keeps the original frozen as-of sequence', async ({ page }) => {
  const requests: Array<{ page: number; asOfSequence?: number }> = [];
  await page.route(/\/api\/v1\/audit-records\/search$/, async (route) => {
    const request = route.request().postDataJSON() as { page: number; asOfSequence?: number };
    requests.push(request);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      ...searchResponse({ recordId: `record-page-${request.page}` }),
      page: request.page, total: 26,
    }) });
  });

  await page.goto('audit/search');
  await expect(page.getByText('record-page-0')).toBeVisible();
  await page.getByRole('button', { name: '下一页' }).click();
  await expect(page.getByText('record-page-1')).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(requests[0]).not.toHaveProperty('asOfSequence');
  expect(requests[1]).toMatchObject({ page: 1, asOfSequence: 42 });
});

test('cancelling a request leaves one stable recovery action and no old table', async ({ page }) => {
  let calls = 0;
  const pendingStarted = deferred();
  const releasePending = deferred();
  await page.route(/\/api\/v1\/audit-records\/search$/, async (route) => {
    calls += 1;
    if (calls === 2) {
      pendingStarted.resolve();
      await releasePending.promise;
    }
    try {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(
        searchResponse({ recordId: `record-${calls}` }),
      ) });
    } catch {
      // The cancellation under test closes the pending route.
    }
  });

  await page.goto('audit/search');
  await expect(page.getByRole('table')).toBeVisible();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await pendingStarted.promise;
  await expect(page.getByRole('table')).toHaveCount(0);
  await page.getByRole('button', { name: '取消本次查询' }).click();
  const state = page.locator('.audit-result-state');
  await expect(state).toContainText('已取消本次查询，未保留任何结果');
  await expect(state.getByRole('button', { name: '重新查询' })).toHaveCount(1);
  await expect(page.getByRole('table')).toHaveCount(0);
  releasePending.resolve();
  await expect(state).toContainText('已取消本次查询，未保留任何结果');
});

test('dependency failure and revoked authorization remove the old table immediately', async ({ page }) => {
  let calls = 0;
  const failureStarted = deferred();
  const releaseFailure = deferred();
  const revokedStarted = deferred();
  const releaseRevoked = deferred();
  await page.route(/\/api\/v1\/audit-records\/search$/, async (route) => {
    calls += 1;
    if (calls === 2) {
      failureStarted.resolve();
      await releaseFailure.promise;
      await route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ code: 'AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE' }) });
      return;
    }
    if (calls === 4) {
      revokedStarted.resolve();
      await releaseRevoked.promise;
      await route.fulfill({ status: 403, contentType: 'application/json',
        body: JSON.stringify({ code: 'AUDIT_SEARCH_FORBIDDEN' }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(
      searchResponse({
        recordId: '019d2c7d-4000-7000-8000-000000000042',
        actorDisplayRef: '[MASKED-IDENTITY]',
      }),
    ) });
  });

  await page.goto('audit/search');
  await expect(page.getByRole('table')).toBeVisible();
  await page.getByLabel('用户标识').fill('audit-failure-persistence-canary');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await failureStarted.promise;
  await expect(page.getByRole('table')).toHaveCount(0);
  releaseFailure.resolve();
  await expect(page.getByText('审计服务暂时不可用，未返回任何结果。')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
  expect(JSON.stringify(await browserPersistenceSnapshot(page)))
    .not.toContain('audit-failure-persistence-canary');

  await page.getByRole('button', { name: '重试', exact: true }).click();
  await expect(page.getByRole('table')).toBeVisible();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await revokedStarted.promise;
  await expect(page.getByRole('table')).toHaveCount(0);
  releaseRevoked.resolve();
  await expect(page.getByText('记录不存在或当前用途无权查看。')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
  await page.goto('./');
  const afterLeave = await browserPersistenceSnapshot(page);
  expect(afterLeave).toMatchObject({
    localStorageEntries: 0, sessionStorageEntries: 0, indexedDatabases: [], cacheNames: [],
    serviceWorkers: 0,
  });
  expect(JSON.stringify(afterLeave)).not.toContain('audit-failure-persistence-canary');
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
