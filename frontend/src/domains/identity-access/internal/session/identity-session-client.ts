export type CurrentIdentitySession = Readonly<{
  authenticated: true;
  sessionPseudonym: string;
  sessionVersion: number;
  expiresAt: string;
  warningAt: string;
  profileVersion: 'ISP-1.0.0';
}>;

export type ReauthenticationTarget = 'shell.home' | 'shell.session' | 'audit.search';

export type CsrfProof = Readonly<{
  headerName: string;
  value: string;
}>;

export type HostInputRejectionCode =
  | 'HOST_ORIGIN_FORBIDDEN'
  | 'HOST_SOURCE_FORBIDDEN'
  | 'HOST_MESSAGE_INVALID'
  | 'HOST_MESSAGE_REPLAYED';

export type HostBootstrapChallenge = Readonly<{
  bootstrapCode: string;
  audience: 'scholarsense-web';
  origin: string;
  expiresAt: string;
}>;

export class IdentitySessionClient {
  public constructor(
    private readonly request: typeof fetch = (input, init) => globalThis.fetch(input, init),
  ) {}

  public async current(signal?: AbortSignal): Promise<CurrentIdentitySession> {
    const response = await this.request('/api/v1/identity-sessions/current', {
      credentials: 'include', headers: { Accept: 'application/json' }, signal,
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!isCurrentSession(value)) throw new Error('IDENTITY_RESPONSE_INVALID');
    return Object.freeze(value);
  }

  public async exchangeBootstrap(
    bootstrapCode: string,
    portalOrigin: string,
    csrf: CsrfProof,
    signal?: AbortSignal,
  ): Promise<void> {
    const response = await this.request('/api/v1/host-bootstrap-exchanges', {
      method: 'POST', credentials: 'include', signal,
      headers: {
        'Content-Type': 'application/json', [csrf.headerName]: csrf.value,
      },
      body: JSON.stringify({ bootstrapCode, audience: 'scholarsense-web', origin: portalOrigin }),
    });
    if (!response.ok) throw await safeFailure(response);
  }

  public async issueHostBootstrap(
    csrf: CsrfProof,
    signal?: AbortSignal,
  ): Promise<HostBootstrapChallenge> {
    const response = await this.request('/api/v1/host-bootstrap-issuances', {
      method: 'POST', credentials: 'include', signal,
      headers: { Accept: 'application/json', [csrf.headerName]: csrf.value },
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!isRecord(value)
      || Object.keys(value).sort().join() !== [
        'audience', 'bootstrapCode', 'expiresAt', 'origin', 'profileVersion',
      ].join()
      || typeof value.bootstrapCode !== 'string'
      || !/^hb_[A-Za-z0-9_-]{43,86}$/.test(value.bootstrapCode)
      || value.audience !== 'scholarsense-web'
      || typeof value.origin !== 'string'
      || value.profileVersion !== 'HIP-1.0.0'
      || !validDate(value.expiresAt)) {
      throw new Error('IDENTITY_RESPONSE_INVALID');
    }
    return Object.freeze({
      bootstrapCode: value.bootstrapCode,
      audience: value.audience,
      origin: value.origin,
      expiresAt: value.expiresAt,
    });
  }

  public async csrfProof(signal?: AbortSignal): Promise<CsrfProof> {
    const response = await this.request('/api/v1/identity-sessions/csrf', {
      credentials: 'include', headers: { Accept: 'application/json' }, signal,
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!isRecord(value) || typeof value.headerName !== 'string'
      || !/^X-[A-Za-z0-9-]{1,64}$/.test(value.headerName)
      || typeof value.token !== 'string'
      || !/^[A-Za-z0-9._~-]{16,512}$/.test(value.token)) {
      throw new Error('IDENTITY_RESPONSE_INVALID');
    }
    return Object.freeze({ headerName: value.headerName, value: value.token });
  }

  public async createReauthentication(
    targetRouteId: ReauthenticationTarget,
    applicationOrigin: string,
    csrf: CsrfProof,
    opaqueContext?: string,
    signal?: AbortSignal,
  ): Promise<string> {
    const response = await this.request('/api/v1/identity-sessions/reauthentications', {
      method: 'POST', credentials: 'include', signal,
      headers: {
        Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.value,
      },
      body: JSON.stringify({
        targetRouteId,
        origin: applicationOrigin,
        ...(opaqueContext === undefined ? {} : { opaqueContext }),
      }),
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!isRecord(value)
      || Object.keys(value).sort().join() !== [
        'authorizationUri', 'continuationCode', 'expiresAt',
      ].join()
      || typeof value.continuationCode !== 'string'
      || !/^ct_[A-Za-z0-9_-]{43,86}$/.test(value.continuationCode)
      || typeof value.authorizationUri !== 'string'
      || !/^\/oauth2\/authorization\/[A-Za-z0-9._-]+$/.test(value.authorizationUri)
      || !validDate(value.expiresAt)) {
      throw new Error('IDENTITY_RESPONSE_INVALID');
    }
    return value.authorizationUri;
  }

  public async logout(
    sessionVersion: number,
    csrf: CsrfProof,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<void> {
    if (!Number.isSafeInteger(sessionVersion) || sessionVersion < 1
      || !/^idem_[A-Za-z0-9_-]{43}$/.test(idempotencyKey)) {
      throw new Error('IDENTITY_COMMAND_INVALID');
    }
    const init: RequestInit = {
      method: 'POST', credentials: 'include', signal,
      headers: {
        Accept: 'application/json', 'Content-Type': 'application/json',
        [csrf.headerName]: csrf.value, 'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ sessionVersion }),
    };
    let response: Response;
    try {
      response = await this.request('/api/v1/identity-sessions/logout', init);
    } catch (failure) {
      if (signal?.aborted === true) throw failure;
      // A committed logout may lose its first response. Reuse the exact proof and key so
      // the BFF can return the detached idempotency result without executing again.
      response = await this.request('/api/v1/identity-sessions/logout', init);
    }
    if (!response.ok) throw await safeFailure(response);
  }

  public async reportHostInputRejection(
    code: HostInputRejectionCode,
    csrf: CsrfProof,
    signal?: AbortSignal,
  ): Promise<void> {
    const response = await this.request('/api/v1/host-input-rejections', {
      method: 'POST', credentials: 'include', signal,
      headers: {
        Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.value,
      },
      body: JSON.stringify({ code }),
    });
    if (!response.ok) throw await safeFailure(response);
  }
}

export function newSessionCommandIdempotencyKey(): string {
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(32));
  const base64 = globalThis.btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
  if (!/^[A-Za-z0-9_-]{43}$/.test(base64)) throw new Error('IDENTITY_DEPENDENCY_UNAVAILABLE');
  return `idem_${base64}`;
}

function isCurrentSession(value: unknown): value is CurrentIdentitySession {
  if (!isRecord(value) || Object.keys(value).sort().join() !== [
    'authenticated', 'expiresAt', 'profileVersion', 'sessionPseudonym', 'sessionVersion', 'warningAt',
  ].join()) return false;
  return value.authenticated === true
    && typeof value.sessionPseudonym === 'string'
    && /^sp_[A-Za-z0-9_-]{20,86}$/.test(value.sessionPseudonym)
    && Number.isSafeInteger(value.sessionVersion) && Number(value.sessionVersion) >= 1
    && value.profileVersion === 'ISP-1.0.0'
    && validDate(value.expiresAt) && validDate(value.warningAt);
}

async function safeFailure(response: Response): Promise<Error> {
  let code = response.status === 401 ? 'IDENTITY_SESSION_REQUIRED' : 'IDENTITY_DEPENDENCY_UNAVAILABLE';
  try {
    const value: unknown = await response.json();
    if (isRecord(value) && typeof value.code === 'string' && /^[A-Z0-9_]{3,128}$/.test(value.code)) {
      code = value.code;
    }
  } catch {
    // The external body is deliberately ignored.
  }
  return new Error(code);
}

function validDate(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
