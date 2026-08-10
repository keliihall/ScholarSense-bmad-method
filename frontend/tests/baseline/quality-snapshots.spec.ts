import { createRequire } from 'node:module';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const axePath = createRequire(import.meta.url).resolve('axe-core/axe.min.js');
const snapshotId = '019fe570-0000-7000-8000-000000000701';
const batchId = '019fe570-0000-7000-8000-000000000702';
const lineageId = '019fe570-0000-7000-8000-000000000703';
const session = {
  authenticated: true, sessionPseudonym: 'sp_qualitySnapshotOwner1234567', sessionVersion: 7,
  expiresAt: '2099-08-10T02:00:00Z', warningAt: '2099-08-10T01:55:00Z', profileVersion: 'ISP-1.0.0',
};
const shell = {
  schemaVersion: 'AUTHORIZED-SHELL-1.0.0', policyVersion: 'RFP-1.0.0', fixtureVersion: 'RFP-FIXTURE-1.0.0',
  evaluatedAt: '2026-08-10T00:00:00Z',
  defaultSurface: { surfaceId: 'data-quality', title: '数据质量', routeName: 'shell.home', providerState: 'not-installed' },
  menuItems: [{ id: 'quality-snapshots', label: '批次与质量快照', routeName: 'data-quality.quality-snapshots', providerState: 'available' }],
  entryCapabilities: [{ id: 'quality-snapshots', state: 'available' }], dependencyStatus: 'available',
};
const metric = (metricId: string, valueBasisPoints: number, result = 'passed') => ({
  metricId, formulaId: `QMDP-1.0.0/${metricId}`, formulaVersion: '1.0.0', result,
  applicable: true, numerator: valueBasisPoints, denominator: 10000, valueBasisPoints,
  unit: 'basis-point', operator: '>=', thresholdNumerator: 9950,
  thresholdDenominator: 10000, boundary: 'inclusive', reasonCode: null,
});
const metrics = [
  metric('PRIMARY_KEY_COMPLETENESS_BP', 9990),
  metric('SOURCE_CONTINUITY_GATE', 10000),
  metric('CORE_FIELD_COVERAGE_BP', 9980),
  metric('FRESHNESS_WITHIN_SLO_BP', 9970),
];
const snapshot = {
  snapshotId, batchId, sourceId: 'SRC-P0-CARD-001', assessedBatchStatus: 'quality-passed',
  overallResult: 'quality-passed',
  observationWindow: { startAt: '2026-08-01T00:00:00Z', endAt: '2026-08-10T00:00:00Z' },
  cutoffAt: '2026-08-10T00:00:00Z', evaluatedAt: '2026-08-10T00:02:00Z',
  watermark: 'src-p0-card-001@2026-08-10', metricResults: metrics,
  impactScopeCodes: ['PRIMARY_KEY_COMPLETENESS_BP'], sourceOwnerRef: 'CARD-OWNER',
  approvalRef: 'AUTH-2026-08-08-001', effectiveAt: '2026-08-09T00:00:00Z',
  retentionScheduleVersion: 'RS-1.0.0', qualityMetricDecisionProfileVersion: 'QMDP-1.0.0',
  qualityMetricDecisionProfileDigest: `sha256:${'1'.repeat(64)}`,
  qualityGateVersion: 'QG-1.0.0', qualityGateDigest: `sha256:${'2'.repeat(64)}`,
  canonicalizationProfile: 'SCHOLARSENSE-CANONICAL-JSON-1.0.0',
  manifestDigest: `sha256:${'3'.repeat(64)}`, sourceSchemaVersion: 'CARD-SLICE-1.0.0',
  sourceSchemaDigest: `sha256:${'4'.repeat(64)}`, immutableHash: `sha256:${'5'.repeat(64)}`,
  traceId: '00112233445566778899aabbccddeeff', lineageId, supersedesSnapshotId: null,
  aggregateVersion: 3,
};

async function install(page: Page, listStatus = 200): Promise<{ listRequests: string[] }> {
  const listRequests: string[] = [];
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(session),
  }));
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(shell),
  }));
  await page.route(/\/api\/v1\/quality-snapshots\?/, (route) => {
    listRequests.push(route.request().url());
    return route.fulfill({ status: listStatus, contentType: 'application/json', body: JSON.stringify(
      listStatus === 200
        ? { items: [snapshot], size: 20, hasMore: false, nextCursor: null }
        : { code: 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE' },
    ) });
  });
  await page.route(new RegExp(`/api/v1/quality-snapshots/${snapshotId}$`), (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(snapshot),
  }));
  for (const item of metrics) {
    await page.route(new RegExp(`/api/v1/quality-snapshots/${snapshotId}/metrics/${item.metricId}\\?formulaId=${encodeURIComponent(item.formulaId)}&formulaVersion=${item.formulaVersion}$`),
      (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(item) }));
  }
  return { listRequests };
}

test('R6 snapshot surface is desktop-only, accessible and carries no forbidden object content', async ({ page }, testInfo) => {
  const { listRequests } = await install(page);
  await page.goto('data-quality/quality-snapshots');
  await expect(page.getByRole('heading', { name: '批次与质量快照' })).toBeFocused();

  if (testInfo.project.name === 'responsive-375') {
    await expect(page.getByText('小于 768px 不加载质量表格或业务对象。')).toBeVisible();
    expect(listRequests).toHaveLength(0);
    await expect(page.getByText('SRC-P0-CARD-001')).toHaveCount(0);
    await expect(page.getByRole('table')).toHaveCount(0);
    return;
  }

  await expect(page.getByRole('heading', { name: '四类质量指标' })).toBeVisible();
  for (const category of ['主键完整性', '连续性', '覆盖率', '新鲜度']) {
    await expect(page.getByRole('heading', { name: category, exact: true })).toBeVisible();
  }
  await expect(page.getByText('pending-story-execution/not-available').first()).toBeVisible();
  await expect(page.locator('canvas')).toBeVisible();
  await expect(page.getByRole('table', { name: /PRIMARY_KEY_COMPLETENESS_BP/ })).toBeVisible();
  await page.getByRole('button', { name: /PRIMARY_KEY_COMPLETENESS_BP/ }).click();
  await expect(page.getByText('分子 9990 / 分母 10000')).toBeVisible();
  const html = await page.locator('html').innerText();
  const accessibilityTree = await page.locator('body').ariaSnapshot();
  for (const forbidden of ['studentName', 'studentNumber', 'evidenceBody', 'diagnosisText']) {
    expect(html).not.toContain(forbidden);
    expect(accessibilityTree).not.toContain(forbidden);
  }
  const persisted = await page.evaluate(async () => ({
    local: localStorage.length, session: sessionStorage.length,
    databases: typeof indexedDB.databases === 'function' ? await indexedDB.databases() : [],
    caches: 'caches' in window ? await caches.keys() : [],
  }));
  expect(persisted).toEqual({ local: 0, session: 0, databases: [], caches: [] });

  if (testInfo.project.name === 'desktop-reference') {
    await page.addScriptTag({ path: axePath });
    const violations = await page.evaluate(async () => (await (window as any).axe.run()).violations);
    expect(violations).toEqual([]);
    await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
    await expect(page.getByRole('heading', { name: '冻结快照与批次元数据' })).toBeVisible();
    await expect(page.locator('.quality-table-wrap')).toBeVisible();
  }
});

test('filters are URL-restorable and dependency failure removes the old snapshot DOM', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'responsive-375', 'mobile intentionally does not load business objects');
  const { listRequests } = await install(page);
  await page.goto('data-quality/quality-snapshots');
  await expect(page.locator('.quality-summary').getByText(snapshotId)).toBeVisible();
  await page.getByLabel('Source ID').fill('SRC-P0-CARD-001');
  await page.getByLabel('结论').selectOption('quality-passed');
  await page.getByLabel('评估起点（含时区）').fill('2026-08-01T08:00:00+08:00');
  await page.getByRole('button', { name: '应用筛选' }).click();
  await expect(page).toHaveURL(/sourceId=SRC-P0-CARD-001/);
  await expect(page).toHaveURL(/overallResult=quality-passed/);
  await expect(page).toHaveURL(/evaluatedFrom=2026-08-01T08:00:00%2B08:00/);
  await expect.poll(() => listRequests.length).toBe(2);

  await page.unroute(/\/api\/v1\/quality-snapshots\?/);
  await page.route(/\/api\/v1\/quality-snapshots\?/, (route) => route.fulfill({
    status: 503, contentType: 'application/json',
    body: JSON.stringify({ code: 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE' }),
  }));
  await page.reload();
  await expect(page.getByText('旧结果已清除')).toBeVisible();
  await expect(page.getByText(snapshotId)).toHaveCount(0);
  await expect(page.getByRole('table')).toHaveCount(0);
  await expect(page.locator('.quality-state').getByRole('button')).toHaveCount(1);
});

test('keyset pagination puts the server cursor in the URL and replaces the page', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'responsive-375', 'mobile intentionally does not load business objects');
  await install(page);
  await page.unroute(/\/api\/v1\/quality-snapshots\?/);
  const pageItems = Array.from({ length: 20 }, (_, index) => index === 0 ? snapshot : {
    ...snapshot,
    snapshotId: `019fe570-0000-7000-8000-${String(800 + index).padStart(12, '0')}`,
  });
  const cursorItem = pageItems.at(-1)!;
  const requests: string[] = [];
  await page.route(/\/api\/v1\/quality-snapshots\?/, (route) => {
    const url = new URL(route.request().url());
    requests.push(url.toString());
    const next = url.searchParams.has('afterSnapshotId');
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(
      next
        ? { items: [snapshot], size: 20, hasMore: false, nextCursor: null }
        : { items: pageItems, size: 20, hasMore: true,
          nextCursor: { snapshotId: cursorItem.snapshotId, evaluatedAt: cursorItem.evaluatedAt } },
    ) });
  });

  await page.goto('data-quality/quality-snapshots');
  await expect(page.getByText('本页 20 条')).toBeVisible();
  await page.getByRole('button', { name: '下一页' }).click();

  await expect(page).toHaveURL(new RegExp(`afterSnapshotId=${cursorItem.snapshotId}`));
  await expect(page.getByText('本页 1 条')).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(new URL(requests[1]!).searchParams.get('afterEvaluatedAt')).toBe(cursorItem.evaluatedAt);
});

test('evaluatedAt sort field and direction are recoverable from the URL', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-reference', 'one reference browser proves URL sort recovery');
  await install(page);
  const requests: string[] = [];
  await page.unroute(/\/api\/v1\/quality-snapshots\?/);
  await page.route(/\/api\/v1\/quality-snapshots\?/, (route) => {
    requests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [snapshot], size: 20, hasMore: false, nextCursor: null,
    }) });
  });

  await page.goto('data-quality/quality-snapshots');
  await expect(page.locator('.quality-summary')).toBeVisible();
  await page.getByLabel('评估时间排序').selectOption('asc');
  await page.getByRole('button', { name: '应用筛选' }).click();

  await expect(page).toHaveURL(/sortField=evaluatedAt/);
  await expect(page).toHaveURL(/sortDirection=asc/);
  await expect.poll(() => requests.length).toBe(2);
  const latest = new URL(requests.at(-1)!);
  expect(latest.searchParams.get('sortField')).toBe('evaluatedAt');
  expect(latest.searchParams.get('sortDirection')).toBe('asc');
});

test('loading, empty, filter-empty, forbidden, degraded and offline states clear business DOM', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-reference', 'one reference browser proves the state matrix');
  await install(page);
  await page.unroute(/\/api\/v1\/quality-snapshots\?/);
  let mode: 'loading' | 'empty' | 'forbidden' | 'result' = 'loading';
  await page.route(/\/api\/v1\/quality-snapshots\?/, async (route) => {
    if (mode === 'loading') {
      await new Promise((resolve) => setTimeout(resolve, 250));
      mode = 'result';
    }
    if (mode === 'forbidden') return route.fulfill({ status: 404, contentType: 'application/json',
      body: JSON.stringify({ code: 'INGESTION_QUALITY_FORBIDDEN' }) });
    const items = mode === 'empty' ? [] : [snapshot];
    return route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ items, size: 20, hasMore: false, nextCursor: null }) });
  });

  await page.goto('data-quality/quality-snapshots');
  await expect(page.getByText('正在重新鉴权并读取不可变快照…')).toBeVisible();
  await expect(page.locator('.quality-summary')).toBeVisible();

  mode = 'empty';
  await page.goto('data-quality/quality-snapshots?sourceId=SRC-P0-CARD-001');
  await expect(page.getByText('筛选条件没有匹配快照；不会把无数据显示为 0。')).toBeVisible();
  await expect(page.locator('.quality-summary')).toHaveCount(0);

  await page.goto('data-quality/quality-snapshots');
  await expect(page.getByText('当前授权范围内还没有已评估质量快照。')).toBeVisible();

  mode = 'forbidden';
  await page.reload();
  await expect(page.getByText('记录不存在或当前用途无权查看。')).toBeVisible();
  await expect(page.locator('.quality-summary')).toHaveCount(0);

  mode = 'result';
  await page.unroute(/\/api\/v1\/authorized-shell$/);
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ ...shell, dependencyStatus: 'unavailable' }),
  }));
  await page.reload();
  await expect(page.getByText('已加载当前快照；其他授权能力暂时不可用。')).toBeVisible();

  await page.context().setOffline(true);
  await expect(page.getByText('旧质量对象已清除且不会离线缓存')).toBeVisible();
  await expect(page.locator('.quality-summary')).toHaveCount(0);
  await page.context().setOffline(false);
});
