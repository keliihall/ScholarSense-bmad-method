import { createRequire } from 'node:module';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const axePath = createRequire(import.meta.url).resolve('axe-core/axe.min.js');
const catalogId = '019fc6b8-9400-7000-8000-000000000011';
const sourceIds = [
  'SRC-P0-STUDENT-001', 'SRC-P0-RESPONSIBILITY-001', 'SRC-P0-ACCOMMODATION-001',
  'SRC-P0-CARD-001', 'SRC-P0-CAMPUS-ACCESS-001', 'SRC-P0-DORM-ACCESS-001',
  'SRC-P0-DEVICE-001', 'SRC-P0-LEAVE-001', 'SRC-P0-CALENDAR-001', 'SRC-P0-TIMETABLE-001',
  'SRC-P1-OFFCAMPUS-001', 'SRC-P1-NETWORK-001', 'SRC-P1-ACADEMIC-001', 'SRC-P1-CARE-LIST-001',
  'SRC-P1-PSYCH-DEID-001', 'SRC-P1-AID-001', 'SRC-P1-WORK-VISIT-001',
];
const dependencyBindings = {
  'SRC-P0-CAMPUS-ACCESS-001': 'DEP-P0-CAMPUS-ACCESS-001',
  'SRC-P0-DORM-ACCESS-001': 'DEP-P0-DORM-ACCESS-001',
  'SRC-P0-ACCOMMODATION-001': 'DEP-P0-ACCOMMODATION-001',
  'SRC-P0-LEAVE-001': 'DEP-P0-LEAVE-001',
  'SRC-P0-CALENDAR-001': 'DEP-P0-CALENDAR-001',
  'SRC-P0-CARD-001': 'DEP-P0-CONSUMPTION-001',
  'SRC-P0-TIMETABLE-001': 'DEP-P0-TIMETABLE-001',
  'SRC-P0-DEVICE-001': 'DEP-P0-DEVICE-001',
  'SRC-P1-OFFCAMPUS-001': 'DEP-P1-OFFCAMPUS-001',
  'SRC-P1-NETWORK-001': 'DEP-P1-NETWORK-001',
  'SRC-P1-ACADEMIC-001': 'DEP-P1-ACADEMIC-001',
} as const;
const session = { authenticated: true, sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw', sessionVersion: 7,
  expiresAt: '2099-08-04T13:00:00Z', warningAt: '2099-08-04T12:55:00Z', profileVersion: 'ISP-1.0.0' };
const r6Shell = {
  schemaVersion: 'AUTHORIZED-SHELL-1.0.0', policyVersion: 'RFP-1.0.0', fixtureVersion: 'RFP-FIXTURE-1.0.0',
  evaluatedAt: '2026-08-04T12:00:00Z', defaultSurface: { surfaceId: 'data-quality', title: '数据质量', routeName: 'shell.home', providerState: 'not-installed' },
  menuItems: [{ id: 'data-source-catalogs', label: '数据源目录', routeName: 'data-quality.catalogs', providerState: 'available' }],
  entryCapabilities: [{ id: 'data-source-catalogs', state: 'available' }, { id: 'data-quality', state: 'not-installed' }],
  dependencyStatus: 'available',
};
const summary = { catalogId, catalogReleaseId: null, contractVersion: 'DCC-1.0.0', status: 'INVALID', aggregateVersion: 2,
  currentPointerVersion: 0, contentDigest: `sha256:${'a'.repeat(64)}`, evidenceSetDigest: null,
  validationFailures: [{ code: 'DCC_EVIDENCE_MISSING', fieldPath: 'sources[0].evidenceUri' }],
  updatedAt: '2099-08-04T12:00:00Z', publishedAt: null };
const detail = {
  ...summary,
  sources: sourceIds.map((sourceId, index) => ({ sourceId, purpose: `bounded-purpose-${index}`,
    schemaVersion: `SLICE-${index + 1}.0.0`, qualityGateVersion: 'QG-1.0.0',
    evidenceUri: `evidence://pending/${sourceId}`, runtimeEvidenceClaim: 'NONE', metadata: {
      ownerDepartment: `责任部门 ${index + 1}`, ownerName: `责任人 ${index + 1}`,
      responsibleRole: `业务负责角色 ${index + 1}`,
      businessDefinition: `最小业务定义 ${index + 1}`, businessKeys: ['subjectToken', 'effectiveFrom'],
      effectiveInterval: 'half-open-utc',
      updateFrequency: '增量并每日全量', slo: '99% 在 4 小时内到达', coverage: '获准有效区间',
      sensitivity: 'sensitive', schemaRef: `contracts/data-catalog/sources/source-${index + 1}.schema.json`,
      reconciliation: '每日 06:00 对账', reconciliationMinimumBasisPoints: 9950, backfillWindowDays: 90,
      watermarkRequired: true, contractTests: ['provider', 'consumer'],
      consumerMode: index < 11 ? 'rule-dependency' : 'purpose-isolated', status: 'approved-contract',
    } })),
  dependencies: Object.entries(dependencyBindings).map(([sourceId, dependencyId]) => ({ sourceId,
    dependencyId,
    requirement: 'REQUIRED', operator: 'ALL_OF' })),
};
const publishableSummary = {
  ...summary,
  status: 'PUBLISHABLE',
  validationFailures: [],
};
const publishableDetail = { ...detail, ...publishableSummary };

async function install(page: Page, shell: unknown = r6Shell): Promise<void> {
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(session) }));
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(shell) }));
  await page.route(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [summary], page: 0, size: 20, hasMore: false }) }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${catalogId}$`), (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail) }));
}

async function installPublishable(
  page: Page,
  currentSession: () => typeof session = () => session,
  deterministicConflict = false,
): Promise<{ publicationRequests: Array<{ releaseId: string; idempotencyKey: string }> }> {
  let currentSummary: Record<string, unknown> = publishableSummary;
  const publicationRequests: Array<{ releaseId: string; idempotencyKey: string }> = [];
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(currentSession()),
  }));
  await page.route(/\/api\/v1\/identity-sessions\/csrf$/, (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(Object.fromEntries([
      ['headerName', 'X-CSRF-TOKEN'], ['token', 'abcdefghijklmnopqrstuvwxyzABCDEF'],
    ])),
  }));
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(r6Shell),
  }));
  await page.route(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/, (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ items: [currentSummary], page: 0, size: 20, hasMore: false }),
  }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${catalogId}$`), (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ ...publishableDetail, ...currentSummary }),
  }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${catalogId}/publications$`), async (route) => {
    const body = route.request().postDataJSON() as {
      catalogReleaseId: string;
      expectedCurrentVersion: number;
    };
    const idempotencyKey = await route.request().headerValue('Idempotency-Key');
    publicationRequests.push({ releaseId: body.catalogReleaseId, idempotencyKey: idempotencyKey ?? '' });
    if (deterministicConflict) {
      await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({
        code: 'INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT', traceId: 'a'.repeat(32), fieldErrors: [],
      }) });
      return;
    }
    if (publicationRequests.length === 1) {
      await route.abort('connectionreset');
      return;
    }
    currentSummary = {
      ...publishableSummary,
      status: 'PUBLISHED',
      aggregateVersion: 3,
      currentPointerVersion: body.expectedCurrentVersion + 1,
      catalogReleaseId: body.catalogReleaseId,
      evidenceSetDigest: `sha256:${'d'.repeat(64)}`,
      updatedAt: '2099-08-04T12:01:00Z',
      publishedAt: '2099-08-04T12:01:00Z',
    };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentSummary) });
  });
  return { publicationRequests };
}

test('R6 deep link explains all 17 contracts, invalid reasons and remains accessible at 200%', async ({ page }, testInfo) => {
  await install(page);
  await page.goto(`data-quality/catalogs?catalogId=${catalogId}`);
  await expect(page.getByRole('heading', { name: '数据源目录' })).toBeFocused();
  await expect(page.getByText('当前目录的 17 个数据源合同')).toBeVisible();
  await expect(page.getByText('DCC_EVIDENCE_MISSING')).toBeVisible();
  await expect(page.getByRole('cell', { name: '责任部门 1 责任人 1', exact: true })).toBeVisible();
  await expect(page.getByText('○ 仅合同，等待目标证据').first()).toBeVisible();
  await expect(page.locator('.catalog-state')).toHaveAttribute('aria-live', 'polite');
  await page.locator('.catalog-table-wrap').focus();
  await expect(page.locator('.catalog-table-wrap')).toBeFocused();
  const persisted = await page.evaluate(async () => ({ local: localStorage.length, session: sessionStorage.length,
    databases: typeof indexedDB.databases === 'function' ? await indexedDB.databases() : [],
    caches: 'caches' in window ? await caches.keys() : [], workers: 'serviceWorker' in navigator ? (await navigator.serviceWorker.getRegistrations()).length : 0 }));
  expect(persisted).toEqual({ local: 0, session: 0, databases: [], caches: [], workers: 0 });
  expect(page.url()).not.toContain('责任人');
  for (const forbidden of ['studentName', 'studentNumber', 'grade', 'thesis', 'consultationBody']) {
    expect(await page.locator('html').innerText()).not.toContain(forbidden);
  }
  if (testInfo.project.name === 'desktop-reference') {
    await page.addScriptTag({ path: axePath });
    const violations = await page.evaluate(async () => (await (window as any).axe.run()).violations);
    expect(violations).toEqual([]);
    await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
    await expect(page.getByRole('heading', { name: '数据源目录' })).toBeVisible();
    await expect(page.locator('.catalog-table-wrap')).toBeVisible();
  }
});

test('filter-empty and unavailable states are distinct and never retain an old table', async ({ page }) => {
  await install(page);
  await page.goto('data-quality/catalogs');
  await expect(page.getByRole('table')).toBeVisible();
  await page.getByLabel('在当前页按目录 ID 或状态筛选').fill('published-only');
  await page.getByRole('button', { name: '应用筛选' }).click();
  await expect(page.getByText('当前页筛选没有匹配目录；可翻页继续查找。')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
  await page.unroute(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/);
  await page.route(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/, (route) => route.fulfill({ status: 503,
    contentType: 'application/json', body: JSON.stringify({ code: 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE' }) }));
  await page.reload();
  await expect(page.getByText('目录依赖暂时不可用，旧结果已清除。')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});

test('filtering to another catalog replaces the old detail instead of mixing versions', async ({ page }) => {
  const secondCatalogId = '019fc6b8-9400-7000-8000-000000000012';
  const secondSummary = { ...summary, catalogId: secondCatalogId, status: 'DRAFT', aggregateVersion: 3,
    validationFailures: [] } as const;
  const secondDetail = { ...detail, ...secondSummary };
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(session),
  }));
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(r6Shell),
  }));
  await page.route(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ items: [summary, secondSummary], page: 0, size: 20, hasMore: false }),
  }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${catalogId}$`), (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(detail),
  }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${secondCatalogId}$`), (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(secondDetail),
  }));

  await page.goto('data-quality/catalogs');
  await page.getByLabel('在当前页按目录 ID 或状态筛选').fill('DRAFT');
  await page.getByRole('button', { name: '应用筛选' }).click();

  await expect(page).toHaveURL(new RegExp(`catalogId=${secondCatalogId}`));
  await expect(page.locator('.catalog-summary').getByText(secondCatalogId)).toBeVisible();
  await expect(page.locator('.catalog-summary').getByText(catalogId)).toHaveCount(0);
});

test('a detail dependency failure clears the already-loaded list and pagination', async ({ page }) => {
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(session),
  }));
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(r6Shell),
  }));
  await page.route(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ items: [summary], page: 0, size: 20, hasMore: false }),
  }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${catalogId}$`), (route) => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({
      code: 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE', traceId: 'b'.repeat(32), fieldErrors: [],
    }),
  }));

  await page.goto('data-quality/catalogs');

  await expect(page.getByText('目录依赖暂时不可用，旧结果已清除。')).toBeVisible();
  await expect(page.locator('.catalog-version-list')).toHaveCount(0);
  await expect(page.locator('.catalog-pagination')).toHaveCount(0);
  await expect(page.getByRole('table')).toHaveCount(0);
});

test('R7 shell without the R6 capability cannot enter or observe owner details', async ({ page }) => {
  const r7 = { ...r6Shell, defaultSurface: { surfaceId: 'technical-operations', title: '技术运行面板', routeName: 'shell.home', providerState: 'not-installed' },
    menuItems: [], entryCapabilities: [{ id: 'technical-operations', state: 'not-installed' }] };
  await install(page, r7);
  await page.goto('data-quality/catalogs');
  await expect(page).toHaveURL(/\/recovery\?reason=unauthorized/);
  await expect(page.getByText('责任部门 1')).toHaveCount(0);
  expect(await page.locator('html').innerText()).not.toContain('SRC-P0-STUDENT-001');
});

test('authorized pagination reaches later catalog versions by keyboard and returns to page one', async ({ page }) => {
  const secondCatalogId = '019fc6b8-9400-7000-8000-000000000012';
  const secondSummary = { ...summary, catalogId: secondCatalogId, aggregateVersion: 3 };
  const secondDetail = { ...detail, ...secondSummary };
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(session),
  }));
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(r6Shell),
  }));
  const firstPage = Array.from({ length: 20 }, (_, index) => index === 0 ? summary : ({
    ...summary,
    catalogId: `019fc6b8-9400-7000-8000-${String(index + 11).padStart(12, '0')}`,
    aggregateVersion: index + 2,
  }));
  await page.route(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ items: firstPage, page: 0, size: 20, hasMore: true }),
  }));
  await page.route(/\/api\/v1\/data-source-catalogs\?page=1&size=20$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ items: [secondSummary], page: 1, size: 20, hasMore: false }),
  }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${catalogId}$`), (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(detail),
  }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${secondCatalogId}$`), (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(secondDetail),
  }));

  await page.goto('data-quality/catalogs');
  await expect(page.getByRole('button', { name: '上一页' })).toBeDisabled();
  const next = page.getByRole('button', { name: '下一页' });
  await next.focus();
  await page.keyboard.press('Enter');

  await expect(page).toHaveURL(/data-quality\/catalogs\?page=1$/);
  await expect(page.getByText(secondCatalogId)).toBeVisible();
  await expect(page.getByText('第 2 页')).toBeVisible();
  await expect(page.getByRole('button', { name: '下一页' })).toBeDisabled();
  await page.getByLabel('在当前页按目录 ID 或状态筛选').fill('INVALID');
  await page.getByRole('button', { name: '应用筛选' }).click();
  await expect(page).toHaveURL(/data-quality\/catalogs\?(?=.*page=1)(?=.*filter=INVALID)/);
  await expect(page.getByText(secondCatalogId)).toBeVisible();
  await expect(page.getByText('第 2 页')).toBeVisible();
  await expect(page.getByRole('button', { name: '上一页' })).toBeEnabled();
  await page.getByLabel('在当前页按目录 ID 或状态筛选').fill('');
  await page.getByRole('button', { name: '应用筛选' }).click();
  await page.getByRole('button', { name: '上一页' }).click();

  await expect(page).toHaveURL(/data-quality\/catalogs$/);
  await expect(page.getByText(catalogId)).toBeVisible();
  await expect(page.getByText('第 1 页')).toBeVisible();
});

test('a lost publication response retries with the same logical release and idempotency proof', async ({ page }) => {
  const { publicationRequests } = await installPublishable(page);
  await page.goto('data-quality/catalogs');

  await page.getByRole('button', { name: '发布此目录版本' }).click();
  await expect(page.getByText(/操作未完成/)).toBeVisible();
  await page.getByRole('button', { name: '发布此目录版本' }).click();

  await expect(page.locator('.catalog-summary dd').filter({ hasText: '● 已发布' })).toBeVisible();
  expect(publicationRequests).toHaveLength(2);
  expect(publicationRequests[1]).toEqual(publicationRequests[0]);
  expect(publicationRequests[0]?.releaseId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7/);
  expect(publicationRequests[0]?.idempotencyKey).not.toBe('');
});

test('a deterministic publication conflict discards the stale retry proof', async ({ page }) => {
  const { publicationRequests } = await installPublishable(page, () => session, true);
  await page.goto('data-quality/catalogs');

  await page.getByRole('button', { name: '发布此目录版本' }).click();
  await expect(page.getByText(/INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT/)).toBeVisible();
  await page.getByRole('button', { name: '发布此目录版本' }).click();
  await expect.poll(() => publicationRequests.length).toBe(2);

  expect(publicationRequests[1]).not.toEqual(publicationRequests[0]);
});

test('an account switch clears catalog forms and abandons the prior pending publication', async ({ page }) => {
  let activeSession = session;
  const { publicationRequests } = await installPublishable(page, () => activeSession);
  await page.goto('data-quality/catalogs');
  await page.getByLabel('在当前页按目录 ID 或状态筛选').fill('PUBLISHABLE');
  await page.getByRole('button', { name: '发布此目录版本' }).click();
  await expect(page.getByText(/操作未完成/)).toBeVisible();

  activeSession = { ...session, sessionPseudonym: 'sp_differentAccount1234567890' };
  await page.locator('.catalog-version-list button').first().click();
  await expect(page.getByRole('button', { name: '发布此目录版本' })).toBeVisible();
  await expect(page.getByLabel('在当前页按目录 ID 或状态筛选')).toHaveValue('');
  await expect(page.getByText(/操作未完成/)).toHaveCount(0);

  await page.getByRole('button', { name: '发布此目录版本' }).click();
  await expect.poll(() => publicationRequests.length).toBe(2);
  expect(publicationRequests[1]).not.toEqual(publicationRequests[0]);
});
