import { expect, test } from '@playwright/test';


const session = {
  authenticated: true,
  sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw',
  sessionVersion: 7,
  expiresAt: '2099-07-20T02:00:00Z',
  warningAt: '2099-07-20T01:55:00Z',
  profileVersion: 'ISP-1.0.0',
};

test('protects shell.session with a fresh server projection and focuses the destination heading', async ({ page }) => {
  let calls = 0;
  await page.route(/\/api\/v1\/identity-sessions\/current$/, async (route) => {
    calls += 1;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(session) });
  });
  await page.goto('session');
  const heading = page.getByRole('heading', { name: '当前统一身份会话' });
  await expect(heading).toBeVisible();
  await expect(heading).toBeFocused();
  await expect(page.getByText('会话版本').locator('..')).toContainText('7');
  expect(calls).toBe(1);
  const text = await page.locator('html').innerText();
  expect(text).not.toMatch(/access[_ -]?token|refresh[_ -]?token|studentName/i);
});

test('stores shell.session continuation and returns to the protected target after reauthentication', async ({ page }) => {
  let authenticated = false;
  let reauthenticationBody: unknown;
  await page.route(/\/api\/v1\/identity-sessions\/current$/, async (route) => route.fulfill(
    authenticated
      ? { status: 200, contentType: 'application/json', body: JSON.stringify(session) }
      : {
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'IDENTITY_SESSION_EXPIRED', message: 'authentication is required',
            traceId: 'trace-safe-1', fieldErrors: [],
          }),
        },
  ));
  await page.route(/\/api\/v1\/identity-sessions\/csrf$/, async (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(Object.fromEntries([
      ['headerName', 'X-CSRF-TOKEN'], ['token', 'abcdefghijklmnopqrstuvwxyzABCDEF'],
    ])),
  }));
  await page.route(/\/api\/v1\/identity-sessions\/reauthentications$/, async (route) => {
    reauthenticationBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        continuationCode: 'ct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
        expiresAt: '2026-07-20T02:15:00Z',
        authorizationUri: '/oauth2/authorization/school-idp',
      }),
    });
  });
  await page.route(/\/oauth2\/authorization\/school-idp$/, async (route) => {
    authenticated = true;
    await route.fulfill({ status: 302, headers: { location: '/scholarsense/session' } });
  });
  await page.goto('session');
  await expect(page).toHaveURL(/\/recovery\?reason=session-expired&targetRouteId=shell.session$/);
  await expect(page.getByRole('heading', { name: '会话已失效' })).toBeFocused();
  await expect(page.locator('.state-panel').getByRole('button')).toHaveCount(1);
  await expect(page.locator('.state-panel')).toContainText('不会自动重放');
  expect(reauthenticationBody).toEqual({
    targetRouteId: 'shell.session', origin: new URL(page.url()).origin,
  });

  await page.getByRole('button', { name: '重新认证' }).click();
  await expect(page).toHaveURL(/\/scholarsense\/session$/);
  await expect(page.getByRole('heading', { name: '当前统一身份会话' })).toBeVisible();
});

test('does not create browser persistence or a service worker', async ({ page }) => {
  await page.route(/\/api\/v1\/identity-sessions\/current$/, async (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(session),
  }));
  await page.goto('./');
  expect(await page.evaluate(async () => ({
    local: localStorage.length,
    session: sessionStorage.length,
    caches: 'caches' in window ? (await caches.keys()).length : 0,
    workers: 'serviceWorker' in navigator
      ? (await navigator.serviceWorker.getRegistrations()).length : 0,
  }))).toEqual({ local: 0, session: 0, caches: 0, workers: 0 });
});
