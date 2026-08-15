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
  schemaVersion: 'AUTHORIZED-SHELL-1.1.0', policyVersion: 'RFP-1.0.0', fixtureVersion: 'RFP-FIXTURE-1.0.0',
  evaluatedAt: '2026-08-10T00:00:00Z',
  defaultSurface: { surfaceId: 'data-quality', title: '数据质量', routeName: 'shell.home', providerState: 'not-installed' },
  menuItems: [{ id: 'quality-snapshots', label: '批次与质量快照', routeName: 'data-quality.quality-snapshots', providerState: 'available' }],
  entryCapabilities: [{ id: 'quality-snapshots', state: 'available' }],
  actionCapabilities: [{ actionType: 'quality-fuse.recover', state: 'available' }],
  dependencyStatus: 'available',
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
const eligibilityId = '019fe570-0000-7000-8000-000000000704';
const eligibility = {
  eligibilityId, ruleId: 'ACC-SAFE-001', ruleVersion: '1.0.0', status: 'fused',
  reasonCode: 'REQUIRED_MEMBER_FUSED', operator: 'all-of', threshold: null,
  members: [{
    sourceId: 'SRC-P0-CAMPUS-ACCESS-001', sourceVersion: 12,
    dependencyId: 'DEP-P0-CAMPUS-ACCESS-001', dependencyVersion: 12,
    requirement: 'required', state: 'eligible', versionContinuous: true,
    sourceWatermark: 'campus-access-source-watermark',
    dependencyWatermark: 'campus-access-dependency-watermark', snapshotId,
    snapshotImmutableHash: `sha256:${'5'.repeat(64)}`,
  }, {
    sourceId: 'SRC-P0-DORM-ACCESS-001', sourceVersion: 12,
    dependencyId: 'DEP-P0-DORM-ACCESS-001', dependencyVersion: 12,
    requirement: 'required', state: 'fused', versionContinuous: true,
    sourceWatermark: 'dorm-access-source-watermark',
    dependencyWatermark: 'dorm-access-dependency-watermark',
    snapshotId: '019fe570-0000-7000-8000-000000000705',
    snapshotImmutableHash: `sha256:${'6'.repeat(64)}`,
  }],
  failedMembers: ['DEP-P0-DORM-ACCESS-001'],
  registryVersion: 'RULE-DEPENDENCY-REGISTRY-1.0.0', aggregateVersion: 1,
  effectiveAt: '2026-08-10T00:00:00Z', occurredAt: '2026-08-10T00:03:00Z',
};
const taskId = '019fe570-0000-7000-8000-000000000706';
const episodeId = '019fe570-0000-7000-8000-000000000707';
const recoveryTask = {
  taskId, taskVersion: 1, episodeId, episodeGeneration: 1,
  sourceId: 'SRC-P0-CAMPUS-ACCESS-001',
  dependencyId: 'DEP-P0-CAMPUS-ACCESS-001',
  affectedRules: [{ ruleId: 'ACC-SAFE-001', ruleVersion: '1.0.0' }],
  ownerRef: 'source-owner:SRC-P0-CAMPUS-ACCESS-001', priority: 'P1',
  dueAt: '2026-08-12T08:00:00Z', status: 'open', watermark: 'source-version:42',
  closedAt: null, closureReason: null, ownerResultDigest: null,
  trigger: { batchId, snapshotId, reasonCode: 'REQUIRED_MEMBER_FUSED' },
  currentEvidence: { qualityGateVersion: 'QG-1.0.0', qmdpVersion: 'QMDP-1.0.0',
    qshmVersion: 'QSHM-1.0.0' },
  taskDelivery: { target: 'public-task-platform', status: 'pending', attempt: 0,
    nextAttemptAt: null },
};
const recoveryRequestId = '019fe570-0000-7000-8000-000000000708';
const recoveryPreviewSummary = {
  qualityRecoveryPolicyVersion: 'QRP-1.0.0', requiredConsecutivePassedBatches: 3,
  actualConsecutivePassedBatches: 3, observationDuration: 'PT60M',
  backfillStatus: 'succeeded', backfillLookbackDays: 90,
  reconciliationExpectedCount: 108, reconciliationActualCount: 108,
  reconciliationMismatchCount: 0, samplePopulationCount: 108, sampleSelectedCount: 100,
  sampleMismatchCount: 0, sampleStrataCount: 1, impactAlreadyExpiredCount: 0,
  impactPotentiallyActionableCount: 1, impactExpectedToExpireCount: 0,
  finalActionabilityOwnerStory: '2.5c',
} as const;

async function install(page: Page, listStatus = 200): Promise<{
  listRequests: string[]; eligibilityRequests: string[]; taskRequests: string[];
}> {
  const listRequests: string[] = [];
  const eligibilityRequests: string[] = [];
  const taskRequests: string[] = [];
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
  await page.route(/\/api\/v1\/quality-eligibilities\?/, (route) => {
    eligibilityRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [eligibility], size: 20, hasMore: false, nextCursor: null,
    }) });
  });
  await page.route(new RegExp(`/api/v1/quality-eligibilities/${eligibilityId}$`),
    (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify(eligibility),
    }));
  await page.route(/\/api\/v1\/quality-recovery-tasks\?/, (route) => {
    taskRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [recoveryTask], size: 20, hasMore: false, nextCursor: null,
    }) });
  });
  await page.route(new RegExp(`/api/v1/quality-recovery-tasks/${taskId}$`),
    (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify(recoveryTask),
    }));
  for (const item of metrics) {
    await page.route(new RegExp(`/api/v1/quality-snapshots/${snapshotId}/metrics/${item.metricId}\\?formulaId=${encodeURIComponent(item.formulaId)}&formulaVersion=${item.formulaVersion}$`),
      (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(item) }));
  }
  return { listRequests, eligibilityRequests, taskRequests };
}

test('R6 snapshot surface is desktop-only, accessible and carries no forbidden object content', async ({ page }, testInfo) => {
  const { listRequests, eligibilityRequests, taskRequests } = await install(page);
  await page.goto('data-quality/quality-snapshots?taskSourceId=SRC-P0-CAMPUS-ACCESS-001');
  await expect(page.getByRole('heading', { name: '批次与质量快照' })).toBeFocused();

  if (testInfo.project.name === 'responsive-375') {
    await expect(page.getByText('小于 768px 不加载质量表格或业务对象。')).toBeVisible();
    expect(listRequests).toHaveLength(0);
    expect(eligibilityRequests).toHaveLength(0);
    expect(taskRequests).toHaveLength(0);
    await expect(page.getByText('SRC-P0-CARD-001')).toHaveCount(0);
    await expect(page.getByText('DEP-P0-DORM-ACCESS-001')).toHaveCount(0);
    await expect(page.getByRole('table')).toHaveCount(0);
    return;
  }

  await expect(page.getByRole('heading', { name: '四类质量指标' })).toBeVisible();
  for (const category of ['主键完整性', '连续性', '覆盖率', '新鲜度']) {
    await expect(page.getByRole('heading', { name: category, exact: true })).toBeVisible();
  }
  await expect(page.getByRole('heading', { name: '规则依赖资格与组合树' })).toBeVisible();
  const composition = page.locator('.quality-composition');
  await expect(composition.getByText('⛔ Fused（质量熔断）').first()).toBeVisible();
  await expect(composition.getByText('✓ Eligible（全部必需依赖可用）')).toBeVisible();
  await expect(composition.getByText('DEP-P0-DORM-ACCESS-001', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('heading', { name: '质量异常任务与投递状态' })).toBeVisible();
  await expect(page.getByText('门禁数据连续性未达门槛，相关规则已暂停产出')).toBeVisible();
  await expect(page.locator('.quality-recovery-task-detail')
    .getByText('待投递（本地任务已建立）')).toBeVisible();
  await expect(page.getByLabel('受影响规则范围')
    .getByText('ACC-SAFE-001 @ 1.0.0')).toBeVisible();
  await expect(page.getByRole('heading', { name: '证据化质量恢复' })).toBeVisible();
  await expect(page.getByRole('button', { name: '发起恢复验证' })).toBeVisible();
  await expect(page.getByText('解除熔断与 Eligible：not-available')).toBeVisible();
  await expect(page.locator('.quality-pending')
    .getByRole('button', { name: /解除|手工熔断/ })).toHaveCount(0);
  await expect(page.getByText('pending-story-execution/not-available（Story 2.4）')).toHaveCount(0);
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
    await expect(page.getByLabel('质量趋势等价数据，可横向查看')).toBeVisible();
  }
});

test('recovery request through D4 approval only enters Recovering after explicit execute', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-reference', 'reference browser proves the command lifecycle');
  await install(page);
  const requests: string[] = [];
  let status = 'validating';
  let version = 2;
  let approvalVersion: number | null = null;
  await page.route(/\/api\/v1\/identity-sessions\/csrf$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({
      headerName: 'X-CSRF-TOKEN', token: 'abcdefghijklmnopqrstuvwxyzABCDEF',
    }),
  }));
  await page.route(/\/api\/v1\/quality-recovery-tasks\/[^/]+\/recovery-requests$/, (route) => {
    requests.push(route.request().url());
    expect(route.request().headers()['idempotency-key']).toBeTruthy();
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(view()) });
  });
  await page.route(new RegExp(`/api/v1/quality-recovery-requests/${recoveryRequestId}$`), (route) => {
    status = 'validation-succeeded'; version = 3;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(view()) });
  });
  await page.route(new RegExp(`/api/v1/quality-recovery-requests/${recoveryRequestId}/approval-requests$`), (route) => {
    expect(route.request().headers()['idempotency-key']).toBeTruthy();
    status = 'approval-pending'; version = 4; approvalVersion = 1;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(view()) });
  });
  await page.route(new RegExp(`/api/v1/quality-recovery-requests/${recoveryRequestId}/approval-decisions$`), (route) => {
    expect(route.request().headers()['idempotency-key']).toBeTruthy();
    status = 'approval-approved'; version = 5; approvalVersion = 2;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(view()) });
  });
  await page.route(new RegExp(`/api/v1/quality-recovery-requests/${recoveryRequestId}/execute$`), (route) => {
    expect(route.request().headers()['idempotency-key']).toBeTruthy();
    requests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      recoveryRequestId, taskId, episodeId, state: 'recovering', transitionApplied: true,
      ownerCommittedAt: '2026-08-13T00:10:00Z', traceId: '00112233445566778899aabbccddeeff',
    }) });
  });
  await page.route(new RegExp(`/api/v1/quality-recovery-tasks/${taskId}/observation$`),
    (route) => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({
        recoveryId: recoveryRequestId, recoveryVersion: 5, taskId, taskVersion: 1,
        generation: 1, sourceClass: 'streaming', policyVersion: 'QRP-1.0.0',
        policyDigest: `sha256:${'9'.repeat(64)}`, status: 'observing',
        finalizationState: 'not-requested', approvalId: null, approvalVersion: null,
        consecutivePassedBatches: 0,
        requiredPassedBatches: 3, observedDurationMicros: 0,
        requiredDurationMicros: 3_600_000_000, observationDuration: 'PT60M',
        watermark: null, recoveringStartedAt: '2026-08-13T00:10:00Z',
        lastObservedAt: '2026-08-13T00:10:00Z', latestActionableAt: '2099-08-13T00:10:00Z',
        failedMembers: [], failureReasonCode: null,
        eligibilityStatus: 'recovering', taskStatus: 'open',
        taskClosedAt: null, ownerResultDigest: null, deliveryStatus: 'confirmed',
        deliveryAttempt: 1, deliveryNextAttemptAt: null,
        eligibleForHandoffWindowCount: 0, historyOnlyWindowCount: 0,
        traceId: '0123456789abcdef0123456789abcdef',
      }) }));

  function view() {
    const ready = status !== 'validating';
    return {
      recoveryRequestId, requestVersion: version, taskId, status,
      validationJobId: '019fe570-0000-7000-8000-000000000709',
      validationStatus: ready ? 'succeeded' : 'queued',
      validationResultDigest: ready ? `sha256:${'7'.repeat(64)}` : null,
      previewId: ready ? '019fe570-0000-7000-8000-000000000710' : null,
      previewVersion: ready ? 1 : null, previewDigest: ready ? `sha256:${'8'.repeat(64)}` : null,
      previewExpiresAt: ready ? '2099-08-13T00:15:00Z' : null,
      previewSummary: ready ? recoveryPreviewSummary : null,
      approvalId: approvalVersion === null ? null : '019fe570-0000-7000-8000-000000000711',
      approvalVersion, approvalStatus: status === 'approval-pending' ? 'pending'
        : status === 'approval-approved' ? 'approved' : null,
      traceId: '00112233445566778899aabbccddeeff',
    };
  }

  await page.goto('data-quality/quality-snapshots?taskSourceId=SRC-P0-CAMPUS-ACCESS-001');
  await page.getByRole('button', { name: '发起恢复验证' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: '确认发起' }).click();
  await expect(page.getByText('恢复验证已排队')).toBeVisible();
  await page.getByRole('button', { name: '刷新恢复状态' }).click();
  await expect(page.getByRole('heading', { name: '执行前证据与影响预览' })).toBeVisible();
  await expect(page.getByText('总体 108 / 已选 100 / 分层 1 / 不一致 0')).toBeVisible();
  await page.getByRole('button', { name: '申请 D4 审批' }).click();
  await expect(page.getByText('已提交 D4 maker-checker 审批；恢复执行仍不可用。')).toBeVisible();
  await page.getByRole('button', { name: '独立 checker 批准' }).click();
  await expect(page.getByText('D4 审批已完成')).toBeVisible();
  await page.getByRole('button', { name: '执行进入 Recovering' }).click();
  await expect(page.getByText('正在累计真实质量事实；无数据不会被当作通过。')).toBeVisible();
  expect(requests).toHaveLength(2);
  const persisted = await page.evaluate(async () => ({ local: localStorage.length,
    session: sessionStorage.length, databases: await indexedDB.databases(),
    caches: await caches.keys() }));
  expect(persisted).toEqual({ local: 0, session: 0, databases: [], caches: [] });
});

test('filters are URL-restorable and dependency failure removes the old snapshot DOM', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'responsive-375', 'mobile intentionally does not load business objects');
  const { listRequests, eligibilityRequests, taskRequests } = await install(page);
  await page.goto('data-quality/quality-snapshots?taskSourceId=SRC-P0-CAMPUS-ACCESS-001');
  await expect(page.locator('.quality-summary').getByText(snapshotId)).toBeVisible();
  await page.getByLabel('Source ID', { exact: true }).fill('SRC-P0-CARD-001');
  await page.getByLabel('结论').selectOption('quality-passed');
  await page.getByLabel('评估起点（含时区）').fill('2026-08-01T08:00:00+08:00');
  await page.getByRole('button', { name: '应用筛选' }).click();
  await expect(page).toHaveURL(/sourceId=SRC-P0-CARD-001/);
  await expect(page).toHaveURL(/overallResult=quality-passed/);
  await expect(page).toHaveURL(/evaluatedFrom=2026-08-01T08:00:00%2B08:00/);
  await expect.poll(() => listRequests.length).toBe(2);

  await page.getByLabel('资格状态').selectOption('fused');
  await page.getByLabel('Rule ID').fill('ACC-SAFE-001');
  await page.getByRole('button', { name: '应用资格筛选' }).click();
  await expect(page).toHaveURL(/eligibilityStatus=fused/);
  await expect(page).toHaveURL(/eligibilityRuleId=ACC-SAFE-001/);
  await expect.poll(() => eligibilityRequests.length).toBeGreaterThanOrEqual(3);
  const latestEligibility = new URL(eligibilityRequests.at(-1)!);
  expect(latestEligibility.searchParams.get('status')).toBe('fused');
  expect(latestEligibility.searchParams.get('ruleId')).toBe('ACC-SAFE-001');

  await page.getByLabel('任务 Source ID').fill('SRC-P0-CAMPUS-ACCESS-001');
  await page.getByLabel('任务状态').selectOption('open');
  await page.getByRole('button', { name: '应用任务筛选' }).click();
  await expect(page).toHaveURL(/taskSourceId=SRC-P0-CAMPUS-ACCESS-001/);
  await expect(page).toHaveURL(/taskStatus=open/);
  await expect.poll(() => taskRequests.at(-1) === undefined ? null
    : new URL(taskRequests.at(-1)!).searchParams.get('sourceId'))
    .toBe('SRC-P0-CAMPUS-ACCESS-001');
  const latestTask = new URL(taskRequests.at(-1)!);
  expect(latestTask.searchParams.get('sourceId')).toBe('SRC-P0-CAMPUS-ACCESS-001');
  expect(latestTask.searchParams.get('status')).toBe('open');

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

test('task owner denial, dependency failure and filter-empty never leave stale task facts', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-reference', 'one reference browser proves task states');
  await install(page);
  await page.unroute(/\/api\/v1\/quality-recovery-tasks\?/);
  let mode: 'forbidden' | 'error' | 'empty' | 'result' = 'forbidden';
  await page.route(/\/api\/v1\/quality-recovery-tasks\?/, (route) => {
    if (mode === 'forbidden') return route.fulfill({ status: 404, contentType: 'application/json',
      body: JSON.stringify({ code: 'INGESTION_QUALITY_FORBIDDEN' }) });
    if (mode === 'error') return route.fulfill({ status: 503, contentType: 'application/json',
      body: JSON.stringify({ code: 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE' }) });
    const items = mode === 'empty' ? [] : [recoveryTask];
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items, size: 20, hasMore: false, nextCursor: null,
    }) });
  });

  await page.goto('data-quality/quality-snapshots?taskSourceId=SRC-P0-CAMPUS-ACCESS-001');
  await expect(page.getByText('未暴露总数、分页或详情')).toBeVisible();
  await expect(page.locator('.quality-recovery-task-detail')).toHaveCount(0);

  mode = 'error';
  await page.reload();
  await expect(page.getByText('旧任务已清除')).toBeVisible();
  await expect(page.locator('.quality-recovery-task-detail')).toHaveCount(0);

  mode = 'empty';
  await page.goto('data-quality/quality-snapshots?taskSourceId=SRC-P0-CAMPUS-ACCESS-001&taskStatus=open');
  await expect(page.getByText('任务筛选没有匹配项')).toBeVisible();
  await expect(page.locator('.quality-recovery-task-state')
    .getByRole('button', { name: '清除任务筛选' })).toBeVisible();
  await expect(page.locator('.quality-recovery-task-detail')).toHaveCount(0);
});

test('RecoveryTask remains available when snapshot list is empty or snapshot detail fails', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-reference', 'one reference browser proves independent panels');
  await install(page);
  await page.unroute(/\/api\/v1\/quality-snapshots\?/);
  await page.route(/\/api\/v1\/quality-snapshots\?/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [], size: 20, hasMore: false, nextCursor: null,
    }),
  }));
  await page.goto('data-quality/quality-snapshots?taskSourceId=SRC-P0-CAMPUS-ACCESS-001');
  await expect(page.getByText('当前授权范围内还没有已评估质量快照。')).toBeVisible();
  await expect(page.locator('.quality-recovery-task-detail').getByText(taskId)).toBeVisible();

  await page.unroute(/\/api\/v1\/quality-snapshots\?/);
  await page.route(/\/api\/v1\/quality-snapshots\?/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [snapshot], size: 20, hasMore: false, nextCursor: null,
    }),
  }));
  await page.unroute(new RegExp(`/api/v1/quality-snapshots/${snapshotId}$`));
  await page.route(new RegExp(`/api/v1/quality-snapshots/${snapshotId}$`),
    (route) => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
      code: 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE',
    }) }));
  await page.reload();
  await expect(page.getByText('快照、授权或读取审计依赖暂时不可用')).toBeVisible();
  await expect(page.locator('.quality-recovery-task-detail').getByText(taskId)).toBeVisible();
});

test('partial ownership, technical failure and filter-empty remain distinct from Fused', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-reference', 'one reference browser proves eligibility states');
  await install(page);
  await page.unroute(/\/api\/v1\/quality-eligibilities\?/);
  let mode: 'forbidden' | 'error' | 'empty' = 'forbidden';
  await page.route(/\/api\/v1\/quality-eligibilities\?/, (route) => {
    if (mode === 'forbidden') return route.fulfill({
      status: 404, contentType: 'application/json',
      body: JSON.stringify({ code: 'INGESTION_QUALITY_FORBIDDEN' }),
    });
    if (mode === 'error') return route.fulfill({
      status: 503, contentType: 'application/json',
      body: JSON.stringify({ code: 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE' }),
    });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [], size: 20, hasMore: false, nextCursor: null,
    }) });
  });

  await page.goto('data-quality/quality-snapshots');
  await expect(page.getByText('至少一个暴露成员不在当前 owned-source 范围内')).toBeVisible();
  await expect(page.locator('.quality-composition')).toHaveCount(0);

  mode = 'error';
  await page.reload();
  await expect(page.getByText('技术错误没有显示为 Fused')).toBeVisible();
  await expect(page.locator('.quality-composition')).toHaveCount(0);

  mode = 'empty';
  await page.goto('data-quality/quality-snapshots?eligibilityStatus=eligible');
  await expect(page.getByText('不会返回空成员树或把无数据显示为 Missing')).toBeVisible();
  await expect(page.locator('.quality-eligibility-state')
    .getByRole('button', { name: '清除资格筛选' })).toBeVisible();
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
  await page.getByRole('button', { name: '下一页', exact: true }).click();

  await expect(page).toHaveURL(new RegExp(`afterSnapshotId=${cursorItem.snapshotId}`));
  await expect(page.getByText('本页 1 条', { exact: true })).toBeVisible();
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
  await expect(page.locator('.quality-recovery-task-detail')).toHaveCount(0);
  await page.context().setOffline(false);
});
