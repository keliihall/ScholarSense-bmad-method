import { describe, expect, it, vi } from 'vitest';

import { IdentitySessionClient } from '../../src/domains/identity-access';

describe('identity session BFF client', () => {
  it('accepts only the frozen current-session projection and includes credentials', async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      authenticated: true,
      sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw',
      sessionVersion: 3,
      expiresAt: '2026-07-20T02:00:00Z',
      warningAt: '2026-07-20T01:55:00Z',
      profileVersion: 'ISP-1.0.0',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const client = new IdentitySessionClient(request);
    expect((await client.current()).sessionVersion).toBe(3);
    expect(request).toHaveBeenCalledWith('/api/v1/identity-sessions/current', expect.objectContaining({
      credentials: 'include',
    }));
  });

  it('rejects token-shaped extra fields and does not surface external error text', async () => {
    const invalid = new IdentitySessionClient(vi.fn().mockResolvedValue(new Response(JSON.stringify({
      authenticated: true,
      sessionPseudonym: 'sp_RWxQcW41M2dSeHVIZ0JpYw',
      sessionVersion: 3,
      expiresAt: '2026-07-20T02:00:00Z',
      warningAt: '2026-07-20T01:55:00Z',
      profileVersion: 'ISP-1.0.0',
      accessToken: 'forbidden',
    }), { status: 200 })));
    await expect(invalid.current()).rejects.toThrow('IDENTITY_RESPONSE_INVALID');

    const failed = new IdentitySessionClient(vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'IDENTITY_SESSION_REQUIRED',
      message: 'secret diagnostic must not escape',
    }), { status: 401 })));
    await expect(failed.current()).rejects.toThrow('IDENTITY_SESSION_REQUIRED');
  });

  it('uses the browser-owned Origin while binding bootstrap exchange to the portal origin', async () => {
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = new IdentitySessionClient(request);
    await client.exchangeBootstrap(
      'hb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
      'https://portal.stage.invalid',
      { headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF' },
    );

    const init = request.mock.calls[0]?.[1] as RequestInit;
    expect(init.headers).not.toHaveProperty('Origin');
    expect(JSON.parse(String(init.body))).toEqual({
      bootstrapCode: 'hb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
      audience: 'scholarsense-web',
      origin: 'https://portal.stage.invalid',
    });
  });

  it('creates a frozen targetRouteId continuation and consumes only authorizationUri', async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      continuationCode: 'ct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
      expiresAt: '2026-07-20T02:15:00Z',
      authorizationUri: '/oauth2/authorization/school-idp',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const client = new IdentitySessionClient(request);

    await expect(client.createReauthentication(
      'shell.session',
      'https://app.stage.invalid',
      { headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF' },
    )).resolves.toBe('/oauth2/authorization/school-idp');
    expect(JSON.parse(String(request.mock.calls[0]?.[1]?.body))).toEqual({
      targetRouteId: 'shell.session', origin: 'https://app.stage.invalid',
    });
  });

  it('continues audit.search without filters or execution identifiers', async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      continuationCode: 'ct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
      expiresAt: '2026-07-20T02:15:00Z',
      authorizationUri: '/oauth2/authorization/school-idp',
    }), { status: 200 }));
    const client = new IdentitySessionClient(request);
    await client.createReauthentication(
      'audit.search', 'https://app.stage.invalid',
      { headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF' },
    );
    expect(JSON.parse(String(request.mock.calls[0]?.[1]?.body))).toEqual({
      targetRouteId: 'audit.search', origin: 'https://app.stage.invalid',
    });
  });

  it('submits logout with CSRF, session version and a stable idempotency key', async () => {
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = new IdentitySessionClient(request);
    await client.logout(
      7,
      { headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF' },
      'idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
    );

    expect(request).toHaveBeenCalledWith('/api/v1/identity-sessions/logout', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify({ sessionVersion: 7 }),
      headers: expect.objectContaining({
        'X-CSRF-TOKEN': 'abcdefghijklmnopqrstuvwxyzABCDEF',
        'Idempotency-Key': 'idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
      }),
    }));
  });

  it('replays a lost logout response with the identical detached idempotency proof', async () => {
    const request = vi.fn()
      .mockRejectedValueOnce(new TypeError('response lost'))
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    const client = new IdentitySessionClient(request);

    await client.logout(
      7,
      { headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF' },
      'idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
    );

    expect(request).toHaveBeenCalledTimes(2);
    expect(request.mock.calls[1]).toEqual(request.mock.calls[0]);
  });

  it('reports only a safe host rejection code without reflecting the rejected payload', async () => {
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = new IdentitySessionClient(request);
    await client.reportHostInputRejection(
      'HOST_MESSAGE_INVALID',
      { headerName: 'X-CSRF-TOKEN', value: 'abcdefghijklmnopqrstuvwxyzABCDEF' },
    );

    expect(JSON.parse(String(request.mock.calls[0]?.[1]?.body)))
      .toEqual({ code: 'HOST_MESSAGE_INVALID' });
  });
});
