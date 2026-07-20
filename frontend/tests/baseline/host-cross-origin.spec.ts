import { expect, test } from '@playwright/test';


const PORTAL_ORIGIN = 'http://127.0.0.1:4174';
const APP_ORIGIN = 'http://127.0.0.1:4173';
const now = '2026-07-20T00:00:00.000Z';
const bootstrapCode = 'hb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ';

function message(overrides: Record<string, unknown> = {}) {
  return {
    schemaVersion: 'HIP-1.0.0',
    eventType: 'host.ready',
    messageId: '018f7b87-ee53-7942-9aec-d5948b86b811',
    correlationId: 'cross-origin-1',
    issuedAt: now,
    nonce: 'abcdefghijklmnopqrstuvwxyzABCDEF',
    payload: { bootstrapCode },
    ...overrides,
  };
}

async function configure(page: import('@playwright/test').Page) {
  const requestCount = { current: 0, logout: 0 };
  const bootstrapRequests: Array<{ origin?: string; body: unknown }> = [];
  const logoutRequests: Array<{ idempotencyKey?: string; csrf?: string; body: unknown }> = [];
  await page.clock.setFixedTime(now);
  await page.route(/\/api\/v1\/identity-runtime$/, async (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ schemaVersion: 'HIP-1.0.0', portalOrigin: PORTAL_ORIGIN }),
  }));
  await page.route(/\/api\/v1\/identity-sessions\/current$/, async (route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw',
        sessionVersion: 3,
        expiresAt: '2026-07-20T02:00:00Z',
        warningAt: '2026-07-20T01:55:00Z',
        profileVersion: 'ISP-1.0.0',
      }),
    });
  });
  await page.route(/\/api\/v1\/identity-sessions\/csrf$/, async (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(Object.fromEntries([
      ['headerName', 'X-CSRF-TOKEN'], ['token', 'abcdefghijklmnopqrstuvwxyzABCDEF'],
    ])),
  }));
  await page.route(/\/api\/v1\/host-bootstrap-exchanges$/, async (route) => {
    bootstrapRequests.push({
      origin: route.request().headers().origin,
      body: route.request().postDataJSON(),
    });
    await route.fulfill({ status: 204 });
  });
  await page.route(/\/api\/v1\/host-bootstrap-issuances$/, async (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      bootstrapCode,
      audience: 'scholarsense-web',
      origin: PORTAL_ORIGIN,
      expiresAt: '2026-07-20T00:01:00Z',
      profileVersion: 'HIP-1.0.0',
    }),
  }));
  await page.route(/\/api\/v1\/identity-sessions\/logout$/, async (route) => {
    requestCount.logout += 1;
    logoutRequests.push({
      idempotencyKey: route.request().headers()['idempotency-key'],
      csrf: route.request().headers()['x-csrf-token'],
      body: route.request().postDataJSON(),
    });
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/identity-sessions/current') {
      requestCount.current += 1;
    }
  });
  return { requestCount, bootstrapRequests, logoutRequests };
}

async function sendFromPortal(
  page: import('@playwright/test').Page,
  payload: unknown,
  waitForChallenge = true,
) {
  if (waitForChallenge) {
    await expect.poll(() => page.evaluate(() =>
      (window as unknown as { hostChallenges: unknown[] }).hostChallenges.length)).toBe(1);
  }
  await page.locator('#scholarsense-frame').evaluate((element, input) => {
    (element as HTMLIFrameElement).contentWindow?.postMessage(input.payload, input.appOrigin);
  }, { payload, appOrigin: APP_ORIGIN });
}

test('acknowledges the exact cross-origin parent and reuses replay results without repeating navigation', async ({ page }) => {
  const { requestCount, bootstrapRequests } = await configure(page);
  await page.goto(`${PORTAL_ORIGIN}/`);
  await expect(page.frameLocator('#scholarsense-frame').getByRole('heading', { name: '统一身份已确认' }))
    .toBeVisible();

  await sendFromPortal(page, message());
  await expect.poll(() => page.evaluate(() => (window as unknown as { hostResponses: unknown[] }).hostResponses.length))
    .toBe(1);
  await sendFromPortal(page, message());
  await expect.poll(() => page.evaluate(() => (window as unknown as { hostResponses: unknown[] }).hostResponses.length))
    .toBe(2);
  const responses = await page.evaluate(() => (window as unknown as { hostResponses: Array<{ status: string }> }).hostResponses);
  expect(responses[0]?.status).toBe('accepted');
  expect(responses[1]).toEqual(responses[0]);
  expect(bootstrapRequests).toEqual([{
    origin: APP_ORIGIN,
    body: { bootstrapCode, audience: 'scholarsense-web', origin: PORTAL_ORIGIN },
  }]);

  const navigate = message({
    eventType: 'navigate.requested',
    messageId: '018f7b87-ee53-7942-9aec-d5948b86b814',
    nonce: 'abcdefghijklmnopqrstuvwxyzABCDE1',
    payload: { targetRouteId: 'shell.session' },
  });
  await sendFromPortal(page, navigate);
  await expect.poll(() => page.frames().find((frame) => frame !== page.mainFrame())?.url())
    .toContain('/scholarsense/session');
  const afterNavigation = requestCount.current;
  await sendFromPortal(page, navigate);
  await expect.poll(() => page.evaluate(() => (window as unknown as { hostResponses: unknown[] }).hostResponses.length))
    .toBe(4);
  expect(requestCount.current).toBe(afterNavigation);
});

test('acknowledges logout only after the CSRF and idempotent BFF command succeeds', async ({ page }) => {
  const { requestCount, logoutRequests } = await configure(page);
  await page.goto(`${PORTAL_ORIGIN}/`);
  await expect(page.frameLocator('#scholarsense-frame').getByRole('heading', { name: '统一身份已确认' }))
    .toBeVisible();

  await sendFromPortal(page, message({
    eventType: 'logout.requested',
    messageId: '018f7b87-ee53-7942-9aec-d5948b86b820',
    nonce: 'abcdefghijklmnopqrstuvwxyzABCDE4',
    payload: {},
  }));

  await expect.poll(() => page.evaluate(() =>
    (window as unknown as { hostResponses: Array<{ requestMessageId: string }> }).hostResponses
      .some((response) => response.requestMessageId === '018f7b87-ee53-7942-9aec-d5948b86b820')))
    .toBe(true);
  expect(requestCount.logout).toBe(1);
  expect(logoutRequests).toEqual([{
    idempotencyKey: expect.stringMatching(/^idem_[A-Za-z0-9_-]{43}$/),
    csrf: 'abcdefghijklmnopqrstuvwxyzABCDEF',
    body: { sessionVersion: 3 },
  }]);
  await expect(page.frameLocator('#scholarsense-frame').getByRole('heading', { name: '会话已失效' }))
    .toBeVisible();
});

test('clears the old identity before auth.changed recheck and keeps failure visible', async ({ page }) => {
  await configure(page);
  await page.goto(`${PORTAL_ORIGIN}/`);
  const frame = page.frameLocator('#scholarsense-frame');
  await expect(frame.getByRole('heading', { name: '统一身份已确认' })).toBeVisible();
  await sendFromPortal(page, message());
  await expect.poll(() => page.evaluate(() =>
    (window as unknown as { hostResponses: unknown[] }).hostResponses.length)).toBe(1);

  await page.route(/\/api\/v1\/identity-sessions\/current$/, async (route) => route.fulfill({
    status: 401,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 'IDENTITY_SESSION_REQUIRED', message: 'authentication is required',
      traceId: 'trace-auth-changed', fieldErrors: [],
    }),
  }));
  await sendFromPortal(page, message({
    eventType: 'auth.changed',
    messageId: '018f7b87-ee53-7942-9aec-d5948b86b821',
    nonce: 'abcdefghijklmnopqrstuvwxyzABCDE5',
    payload: {},
  }));

  await expect(frame.getByRole('heading', { name: '会话已失效' })).toBeVisible();
  await expect(frame.getByText('正在安全确认统一身份')).toBeVisible();
});

test('routes runtime load failure to one persistent host recovery action', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-reference');
  await page.route(/\/api\/v1\/identity-runtime$/, async (route) => route.fulfill({ status: 503 }));
  await page.route(/\/api\/v1\/identity-sessions\/current$/, async (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({
      authenticated: true,
      sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw',
      sessionVersion: 3,
      expiresAt: '2026-07-20T02:00:00Z',
      warningAt: '2026-07-20T01:55:00Z',
      profileVersion: 'ISP-1.0.0',
    }),
  }));
  await page.goto(`${PORTAL_ORIGIN}/`);
  const panel = page.frameLocator('#scholarsense-frame').locator('.state-panel');
  await expect(panel.getByRole('heading', { name: '门户宿主降级' })).toBeVisible();
  await expect(panel.getByRole('button')).toHaveCount(1);
});

test('stops accepting host commands after the five-second handshake deadline', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-reference');
  await configure(page);
  await page.goto(`${PORTAL_ORIGIN}/`);
  const panel = page.frameLocator('#scholarsense-frame').locator('.state-panel');
  await expect(panel.getByRole('heading', { name: '门户宿主降级' })).toBeVisible({ timeout: 7_000 });
  await sendFromPortal(page, message({
    eventType: 'navigate.requested',
    messageId: '018f7b87-ee53-7942-9aec-d5948b86b822',
    nonce: 'abcdefghijklmnopqrstuvwxyzABCDE6',
    payload: { targetRouteId: 'shell.session' },
  }));
  await page.waitForTimeout(100);
  await expect(panel.getByRole('heading', { name: '门户宿主降级' })).toBeVisible();
});

test('returns explicit failure for an unknown field and ignores a lookalike parent origin', async ({ page }) => {
  await configure(page);
  await page.goto(`${PORTAL_ORIGIN}/`);
  await expect(page.frameLocator('#scholarsense-frame').getByRole('heading', { name: '统一身份已确认' }))
    .toBeVisible();
  await sendFromPortal(page, { ...message(), unexpectedField: 'forbidden' });
  await expect.poll(() => page.evaluate(() => (window as unknown as { hostResponses: unknown[] }).hostResponses.length))
    .toBe(1);
  const failed = await page.evaluate(() =>
    (window as unknown as { hostResponses: Array<{ errorCode: string }> }).hostResponses[0]);
  expect(failed?.errorCode).toBe('HOST_MESSAGE_INVALID');
  await sendFromPortal(page, message({
    messageId: '018f7b87-ee53-7942-9aec-d5948b86b815',
    nonce: 'abcdefghijklmnopqrstuvwxyzABCDE2',
    issuedAt: '2026-07-19T23:54:59.000Z',
  }));
  await expect.poll(() => page.evaluate(() => (window as unknown as { hostResponses: unknown[] }).hostResponses.length))
    .toBe(2);
  const expired = await page.evaluate(() =>
    (window as unknown as { hostResponses: Array<{ errorCode: string }> }).hostResponses[1]);
  expect(expired?.errorCode).toBe('HOST_MESSAGE_INVALID');

  const applicationFrame = page.frames().find((frame) => frame !== page.mainFrame());
  expect(applicationFrame).toBeDefined();
  const sourceFailure = await applicationFrame!.evaluate(({ envelope, portalOrigin }) =>
    new Promise<string>((resolve) => {
      window.addEventListener('scholarsense:host-failure', (event) => {
        resolve((event as CustomEvent<{ code: string }>).detail.code);
      }, { once: true });
      window.dispatchEvent(new MessageEvent('message', {
        data: envelope, origin: portalOrigin, source: window,
      }));
    }), { envelope: message({
      messageId: '018f7b87-ee53-7942-9aec-d5948b86b816',
      nonce: 'abcdefghijklmnopqrstuvwxyzABCDE3',
    }), portalOrigin: PORTAL_ORIGIN });
  expect(sourceFailure).toBe('HOST_SOURCE_FORBIDDEN');

  await page.goto('http://localhost:4174/');
  await page.route(/\/api\/v1\/identity-runtime$/, async (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ schemaVersion: 'HIP-1.0.0', portalOrigin: PORTAL_ORIGIN }),
  }));
  await page.waitForTimeout(100);
  await sendFromPortal(
    page, message({ messageId: '018f7b87-ee53-7942-9aec-d5948b86b813' }), false,
  );
  await page.waitForTimeout(100);
  expect(await page.evaluate(() => (window as unknown as { hostResponses: unknown[] }).hostResponses.length))
    .toBe(0);
});
