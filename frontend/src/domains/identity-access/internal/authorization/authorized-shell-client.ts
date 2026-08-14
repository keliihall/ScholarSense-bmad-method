export type AuthorizedShellProviderState = 'available' | 'not-installed' | 'unavailable';

export type AuthorizedShellSurface = Readonly<{
  surfaceId: string;
  title: string;
  routeName: 'shell.home';
  providerState: AuthorizedShellProviderState;
}>;

export type AuthorizedShellMenuItem = Readonly<{
  id: string;
  label: string;
  routeName: string;
  providerState: 'available' | 'unavailable';
}>;

export type AuthorizedShellEntryCapability = Readonly<{
  id: string;
  state: AuthorizedShellProviderState;
}>;

export type AuthorizedShellActionCapability = Readonly<{
  actionType: 'quality-fuse.recover';
  state: AuthorizedShellProviderState;
}>;

export type CurrentAuthorizedShell = Readonly<{
  schemaVersion: 'AUTHORIZED-SHELL-1.1.0';
  policyVersion: 'RFP-1.0.0';
  fixtureVersion: 'RFP-FIXTURE-1.0.0';
  evaluatedAt: string;
  defaultSurface: AuthorizedShellSurface;
  menuItems: readonly AuthorizedShellMenuItem[];
  entryCapabilities: readonly AuthorizedShellEntryCapability[];
  actionCapabilities: readonly AuthorizedShellActionCapability[];
  dependencyStatus: 'available' | 'unavailable';
}>;

export class AuthorizedShellClient {
  public constructor(
    private readonly request: typeof fetch = (input, init) => globalThis.fetch(input, init),
  ) {}

  public async current(signal?: AbortSignal): Promise<CurrentAuthorizedShell> {
    const response = await this.request('/api/v1/authorized-shell', {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal,
    });
    if (!response.ok) throw await safeFailure(response);
    const value: unknown = await response.json();
    if (!isCurrentAuthorizedShell(value)) {
      throw new Error('IDENTITY_AUTHORIZED_SHELL_RESPONSE_INVALID');
    }
    return freezeShell(value);
  }
}

function isCurrentAuthorizedShell(value: unknown): value is CurrentAuthorizedShell {
  if (!isExactRecord(value, [
    'actionCapabilities', 'defaultSurface', 'dependencyStatus', 'entryCapabilities', 'evaluatedAt',
    'fixtureVersion', 'menuItems', 'policyVersion', 'schemaVersion',
  ])) return false;
  if (value.schemaVersion !== 'AUTHORIZED-SHELL-1.1.0'
    || value.policyVersion !== 'RFP-1.0.0'
    || value.fixtureVersion !== 'RFP-FIXTURE-1.0.0'
    || !validDate(value.evaluatedAt)
    || !['available', 'unavailable'].includes(String(value.dependencyStatus))
    || !isSurface(value.defaultSurface)
    || !Array.isArray(value.menuItems)
    || !value.menuItems.every(isMenuItem)
    || !unique(value.menuItems.map((item) => item.id))
    || !unique(value.menuItems.map((item) => item.routeName))
    || !Array.isArray(value.entryCapabilities)
    || !value.entryCapabilities.every(isCapability)
    || !unique(value.entryCapabilities.map((item) => item.id))
    || !Array.isArray(value.actionCapabilities)
    || !value.actionCapabilities.every(isActionCapability)
    || !unique(value.actionCapabilities.map((item) => item.actionType))) return false;
  return true;
}

function isActionCapability(value: unknown): value is AuthorizedShellActionCapability {
  return isExactRecord(value, ['actionType', 'state'])
    && value.actionType === 'quality-fuse.recover'
    && isProviderState(value.state);
}

function isSurface(value: unknown): value is AuthorizedShellSurface {
  return isExactRecord(value, ['providerState', 'routeName', 'surfaceId', 'title'])
    && validKebabId(value.surfaceId)
    && typeof value.title === 'string' && value.title.trim().length > 0
    && value.routeName === 'shell.home'
    && isProviderState(value.providerState);
}

function isMenuItem(value: unknown): value is AuthorizedShellMenuItem {
  return isExactRecord(value, ['id', 'label', 'providerState', 'routeName'])
    && validKebabId(value.id)
    && typeof value.label === 'string' && value.label.trim().length > 0
    && typeof value.routeName === 'string'
    && /^[a-z][a-z0-9.-]+$/.test(value.routeName)
    && (value.providerState === 'available' || value.providerState === 'unavailable');
}

function isCapability(value: unknown): value is AuthorizedShellEntryCapability {
  return isExactRecord(value, ['id', 'state'])
    && typeof value.id === 'string' && /^[a-z][a-z0-9.-]+$/.test(value.id)
    && isProviderState(value.state);
}

function isProviderState(value: unknown): value is AuthorizedShellProviderState {
  return value === 'available' || value === 'not-installed' || value === 'unavailable';
}

function validKebabId(value: unknown): value is string {
  return typeof value === 'string' && /^[a-z][a-z0-9-]+$/.test(value);
}

function validDate(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function unique(values: readonly string[]): boolean {
  return new Set(values).size === values.length;
}

function isExactRecord(value: unknown, keys: readonly string[]): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && Object.getPrototypeOf(value) === Object.prototype
    && Object.keys(value).sort().join() === [...keys].sort().join();
}

function freezeShell(value: CurrentAuthorizedShell): CurrentAuthorizedShell {
  return Object.freeze({
    ...value,
    defaultSurface: Object.freeze({ ...value.defaultSurface }),
    menuItems: Object.freeze(value.menuItems.map((item) => Object.freeze({ ...item }))),
    entryCapabilities: Object.freeze(
      value.entryCapabilities.map((item) => Object.freeze({ ...item })),
    ),
    actionCapabilities: Object.freeze(
      value.actionCapabilities.map((item) => Object.freeze({ ...item })),
    ),
  });
}

async function safeFailure(response: Response): Promise<Error> {
  const fallback = response.status === 401
    ? 'IDENTITY_SESSION_REQUIRED'
    : response.status === 403
      ? 'IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN'
      : 'IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE';
  try {
    const value: unknown = await response.json();
    if (isExactRecord(value, ['code', 'fieldErrors', 'message', 'traceId'])
      && typeof value.code === 'string'
      && new Set([
        'IDENTITY_SESSION_REQUIRED', 'IDENTITY_SESSION_EXPIRED',
        'IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN',
        'IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE',
      ]).has(value.code)) return new Error(value.code);
  } catch {
    // The response body is never surfaced.
  }
  return new Error(fallback);
}
