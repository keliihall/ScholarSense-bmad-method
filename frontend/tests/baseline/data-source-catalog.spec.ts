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
  contentDigest: `sha256:${'a'.repeat(64)}`, evidenceSetDigest: null,
  validationFailures: [{ code: 'DCC_EVIDENCE_MISSING', fieldPath: 'sources[0].evidenceUri' }],
  updatedAt: '2099-08-04T12:00:00Z', publishedAt: null };
const detail = {
  ...summary,
  sources: sourceIds.map((sourceId, index) => ({ sourceId, purpose: `bounded-purpose-${index}`,
    schemaVersion: `SLICE-${index + 1}.0.0`, qualityGateVersion: 'QG-1.0.0',
    evidenceUri: `evidence://pending/${sourceId}`, runtimeEvidenceClaim: 'NONE', metadata: {
      ownerDepartment: `责任部门 ${index + 1}`, ownerName: `责任人 ${index + 1}`,
      businessDefinition: `最小业务定义 ${index + 1}`, businessKeys: ['subjectToken', 'effectiveFrom'],
      updateFrequency: '增量并每日全量', slo: '99% 在 4 小时内到达', coverage: '获准有效区间',
      sensitivity: 'sensitive', reconciliation: '每日 06:00 对账', backfillWindowDays: 90,
      watermarkRequired: true, contractTests: ['provider', 'consumer'],
      consumerMode: index < 11 ? 'rule-dependency' : 'purpose-isolated',
    } })),
  dependencies: sourceIds.slice(0, 11).map((sourceId, index) => ({ sourceId,
    dependencyId: `DEP-P0-SYNTHETIC-${String(index + 1).padStart(3, '0')}`,
    requirement: 'REQUIRED', operator: 'ALL_OF' })),
};

async function install(page: Page, shell: unknown = r6Shell): Promise<void> {
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(session) }));
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(shell) }));
  await page.route(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [summary], page: 0, size: 20, hasMore: false }) }));
  await page.route(new RegExp(`/api/v1/data-source-catalogs/${catalogId}$`), (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail) }));
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
  await page.getByLabel('按目录 ID 或状态筛选').fill('published-only');
  await page.getByRole('button', { name: '应用筛选' }).click();
  await expect(page.getByText('当前筛选没有匹配目录。')).toBeVisible();
  await page.unroute(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/);
  await page.route(/\/api\/v1\/data-source-catalogs\?page=0&size=20$/, (route) => route.fulfill({ status: 503,
    contentType: 'application/json', body: JSON.stringify({ code: 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE' }) }));
  await page.reload();
  await expect(page.getByText('目录依赖暂时不可用，旧结果已清除。')).toBeVisible();
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
