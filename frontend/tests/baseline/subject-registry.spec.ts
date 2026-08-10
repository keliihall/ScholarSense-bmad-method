import { createRequire } from 'node:module';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const axePath = createRequire(import.meta.url).resolve('axe-core/axe.min.js');
const exceptionId = '019fcfea-6500-7000-8000-000000000001';
const sourceStudentRef = '019fcfea-6500-7000-8000-000000000002';
const targetStudentRef = '019fcfea-6500-7000-8000-000000000003';
const jobId = '019fcfea-6500-7000-8000-000000000011';
const session = {
  authenticated: true,
  sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw',
  sessionVersion: 7,
  expiresAt: '2099-08-04T13:00:00Z',
  warningAt: '2099-08-04T12:55:00Z',
  profileVersion: 'ISP-1.0.0',
};
const r6Shell = {
  schemaVersion: 'AUTHORIZED-SHELL-1.0.0', policyVersion: 'RFP-1.0.0',
  fixtureVersion: 'RFP-FIXTURE-1.0.0', evaluatedAt: '2026-08-04T12:00:00Z',
  defaultSurface: { surfaceId: 'data-quality', title: '数据质量', routeName: 'shell.home', providerState: 'not-installed' },
  menuItems: [
    { id: 'subject-mapping-exceptions', label: '主体映射异常', routeName: 'data-quality.subject-mapping-exceptions', providerState: 'available' },
  ],
  entryCapabilities: [
    { id: 'subject-mapping-exceptions', state: 'available' },
  ],
  dependencyStatus: 'available',
};
const item = {
  exceptionId, status: 'open', subjectOfficialRef: 'SYNTHETIC-001', exceptionCode: 'AMBIGUOUS',
  sourceSystem: 'SRC-P0-CARD-001', sourceOwner: '合成一卡通 owner', detectedAt: '2026-08-06T07:00:00Z',
};
const job = {
  jobId, status: 'running', attemptNo: 2, queuedAt: '2026-08-06T08:00:00Z',
  completedAt: null, resultCode: null, traceId: '00112233445566778899aabbccddeeff',
};

async function install(
  page: Page,
  options: Readonly<{ shell?: unknown; currentSession?: () => typeof session }> = {},
): Promise<{
  repairRequests: Array<{ body: Record<string, unknown>; idempotencyKey: string }>;
  jobRequests: string[];
}> {
  const currentSession = options.currentSession ?? (() => session);
  const shell = options.shell ?? r6Shell;
  const repairRequests: Array<{ body: Record<string, unknown>; idempotencyKey: string }> = [];
  const jobRequests: string[] = [];
  await page.route(/\/api\/v1\/identity-sessions\/current$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(currentSession()),
  }));
  await page.route(/\/api\/v1\/identity-sessions\/csrf$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'abcdefghijklmnopqrstuvwxyzABCDEF' }),
  }));
  await page.route(/\/api\/v1\/authorized-shell$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(shell),
  }));
  await page.route(/\/api\/v1\/subject-mapping-exceptions\?page=0&size=20(?:&status=open)?$/, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      items: [item], page: 0, size: 20, hasMore: false,
    }) }));
  await page.route(new RegExp(`/api/v1/subject-mapping-exceptions/${exceptionId}$`), (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: '"1"' }, body: JSON.stringify(item) }));
  await page.route(new RegExp(`/api/v1/subject-mapping-exceptions/${exceptionId}/repair$`), async (route) => {
    repairRequests.push({
      body: route.request().postDataJSON() as Record<string, unknown>,
      idempotencyKey: await route.request().headerValue('Idempotency-Key') ?? '',
    });
    if (repairRequests.length === 1) {
      await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({
        code: 'SUBJECT_REGISTRY_VERSION_CONFLICT', message: 'Request could not be completed',
        traceId: '00112233445566778899aabbccddeeff', fieldErrors: [], currentAggregateVersion: 2,
      }) });
      return;
    }
    await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({
      exceptionId, status: 'resolved', aggregateVersion: 3,
      correctionId: '019fcfea-6500-7000-8000-000000000010', jobIds: [jobId],
      traceId: '00112233445566778899aabbccddeeff',
    }) });
  });
  await page.route(new RegExp(`/api/v1/subject-recompute-jobs/${jobId}$`), (route) => {
    jobRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(job) });
  });
  return { repairRequests, jobRequests };
}

test('R6 repairs an owned exception but cannot enter the R7-only detached job', async ({ page }, testInfo) => {
  const { repairRequests, jobRequests } = await install(page);
  await page.goto(`data-quality/subject-mapping-exceptions?exceptionId=${exceptionId}`);

  await expect(page.getByRole('heading', { name: '主体映射异常' })).toBeFocused();
  await expect(page.getByRole('cell', { name: 'SYNTHETIC-001' })).toBeVisible();
  await expect(page.getByText('证据正文')).toHaveCount(0);
  await page.getByLabel('修复原因').selectOption('SUBJECT_MERGED');
  await page.getByLabel('源水位').fill('wm-synthetic-42');
  await page.getByLabel('源 StudentRef').fill(sourceStudentRef);
  await page.getByLabel('目标 StudentRef').fill(targetStudentRef);
  await page.getByRole('button', { name: '提交受控修复' }).click();

  await expect(page.locator('.subject-error')).toContainText('版本已变化');
  await expect(page.getByLabel('源水位')).toHaveValue('wm-synthetic-42');
  await page.getByRole('button', { name: '提交受控修复' }).click();
  await expect(page.getByRole('heading', { name: '修复已受理' })).toBeVisible();
  expect(repairRequests).toHaveLength(2);
  expect(repairRequests[0]?.idempotencyKey).not.toBe(repairRequests[1]?.idempotencyKey);
  expect(repairRequests[1]?.body.expectedAggregateVersion).toBe(2);

  await expect(page.getByText('技术作业已转交平台运维')).toBeVisible();
  await expect(page.getByRole('link', { name: new RegExp('查看作业') })).toHaveCount(0);

  const persisted = await page.evaluate(async () => ({
    local: localStorage.length, session: sessionStorage.length,
    databases: typeof indexedDB.databases === 'function' ? await indexedDB.databases() : [],
    caches: 'caches' in window ? await caches.keys() : [],
    workers: 'serviceWorker' in navigator ? (await navigator.serviceWorker.getRegistrations()).length : 0,
  }));
  expect(persisted).toEqual({ local: 0, session: 0, databases: [], caches: [], workers: 0 });
  expect(page.url()).not.toContain('SYNTHETIC-001');
  if (testInfo.project.name === 'desktop-reference') {
    await page.addScriptTag({ path: axePath });
    expect((await page.evaluate(async () => (await (window as any).axe.run()).violations))).toEqual([]);
    await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
    await expect(page.getByText('技术作业已转交平台运维')).toBeVisible();
    await page.evaluate(() => { document.documentElement.style.zoom = '1'; });
  }

  await page.goto(`subject-recompute-jobs?jobId=${jobId}`);
  await expect(page).toHaveURL(/\/recovery\?reason=unauthorized/);
  expect(jobRequests).toHaveLength(0);
});

test('R7 sees only the technical job surface and cannot enter mapping exceptions', async ({ page }) => {
  const r7Shell = {
    ...r6Shell,
    defaultSurface: { surfaceId: 'technical-operations', title: '技术运行面板', routeName: 'shell.home', providerState: 'not-installed' },
    menuItems: [
      { id: 'subject-recompute-jobs', label: '主体重算作业', routeName: 'subject-registry.recompute-jobs', providerState: 'available' },
    ],
    entryCapabilities: [{ id: 'subject-recompute-jobs', state: 'available' }],
  };
  await install(page, { shell: r7Shell });

  await page.goto(`subject-recompute-jobs?jobId=${jobId}`);
  await expect(page.getByText('运行中')).toBeVisible();
  expect(await page.locator('html').innerText()).not.toContain('SYNTHETIC-001');
  await page.goto(`data-quality/subject-mapping-exceptions?exceptionId=${exceptionId}`);
  await expect(page).toHaveURL(/\/recovery\?reason=unauthorized/);
  await expect(page.getByText('SYNTHETIC-001')).toHaveCount(0);
});

test('an account switch during command recheck clears the draft and prevents repair', async ({ page }) => {
  let sessionCalls = 0;
  const switched = { ...session, sessionPseudonym: 'sp_differentAccount1234567890' };
  const { repairRequests } = await install(page, {
    currentSession: () => ++sessionCalls >= 2 ? switched : session,
  });
  await page.goto(`data-quality/subject-mapping-exceptions?exceptionId=${exceptionId}`);
  await page.getByLabel('源水位').fill('wm-must-clear');
  await page.getByLabel('源 StudentRef').fill(sourceStudentRef);
  await page.getByLabel('目标 StudentRef').fill(targetStudentRef);
  await page.getByRole('button', { name: '提交受控修复' }).click();

  await expect(page.getByText('身份或授权已变化')).toBeVisible();
  await expect(page.getByLabel('源水位')).toHaveCount(0);
  expect(await page.locator('html').innerText()).not.toContain('wm-must-clear');
  expect(repairRequests).toHaveLength(0);
});
