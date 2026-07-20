export type HostEventType =
  | 'host.ready'
  | 'auth.changed'
  | 'navigate.requested'
  | 'logout.requested'
  | 'theme.changed';

export type HostEnvelope = Readonly<{
  schemaVersion: 'HIP-1.0.0';
  eventType: HostEventType;
  messageId: string;
  correlationId: string;
  issuedAt: string;
  nonce: string;
  payload: Readonly<{
    bootstrapCode?: string;
    targetRouteId?: 'shell.home' | 'shell.session';
    theme?: 'light' | 'dark' | 'system';
  }>;
}>;

export type HostResponse = Readonly<{
  schemaVersion: 'HIP-1.0.0';
  eventType: 'host.acknowledged' | 'host.failed';
  messageId: string;
  correlationId: string;
  requestMessageId: string;
  issuedAt: string;
  status: 'accepted' | 'rejected';
  errorCode?: HostFailureCode;
}>;

export type HostChallenge = Readonly<{
  schemaVersion: 'HIP-1.0.0';
  eventType: 'host.challenge';
  messageId: string;
  correlationId: string;
  issuedAt: string;
  payload: Readonly<{
    bootstrapCode: string;
    audience: 'scholarsense-web';
    origin: string;
    expiresAt: string;
  }>;
}>;

export type HostFailureCode =
  | 'HOST_ORIGIN_FORBIDDEN'
  | 'HOST_SOURCE_FORBIDDEN'
  | 'HOST_MESSAGE_INVALID'
  | 'HOST_MESSAGE_REPLAYED'
  | 'IDENTITY_DEPENDENCY_UNAVAILABLE';

export type HostDispatchContext = Readonly<{
  messageId: string;
  correlationId: string;
  signal: AbortSignal;
  assertActive: () => void;
  commit: (effect: () => void) => void;
}>;

type MessageEventPort = Readonly<{ data: unknown; origin: string; source: unknown }>;
type HostTarget = Readonly<{
  postMessage: (message: HostResponse | HostChallenge, targetOrigin: string) => void;
}>;
type BrowserPort = Readonly<{
  parent: HostTarget;
  addEventListener: (type: 'message', listener: (event: MessageEventPort) => void) => void;
  removeEventListener: (type: 'message', listener: (event: MessageEventPort) => void) => void;
}>;

export type HostBridgeHandlers = Readonly<{
  onHostReady: (context: HostDispatchContext) => Promise<void>;
  onAuthChanged: (context: HostDispatchContext) => Promise<void>;
  onNavigateRequested: (
    route: 'shell.home' | 'shell.session', context: HostDispatchContext,
  ) => Promise<void>;
  onLogoutRequested: (context: HostDispatchContext) => Promise<void>;
  onThemeChanged: (
    theme: 'light' | 'dark' | 'system', context: HostDispatchContext,
  ) => Promise<void>;
  onBootstrapCode: (code: string, context: HostDispatchContext) => Promise<void>;
  onRejected: (code: HostFailureCode) => void;
}>;

type Reservation = {
  readonly messageId: string;
  readonly nonce: string;
  response: Promise<HostResponse>;
  settled: boolean;
};

const envelopeKeys = Object.freeze([
  'correlationId', 'eventType', 'issuedAt', 'messageId', 'nonce', 'payload', 'schemaVersion',
]);
const payloadKeys = new Set(['bootstrapCode', 'targetRouteId', 'theme']);
const eventTypes = new Set<HostEventType>([
  'host.ready', 'auth.changed', 'navigate.requested', 'logout.requested', 'theme.changed',
]);
const replayWindowMs = 5 * 60 * 1000;
const responseDeadlineMs = 5 * 1000;

export class HostBridge {
  readonly #nonceReservations = new Map<string, Reservation>();
  readonly #messageReservations = new Map<string, Reservation>();
  readonly #listener = (event: MessageEventPort): void => { void this.#receive(event); };
  #started = false;

  public constructor(
    private readonly browser: BrowserPort,
    private readonly allowedOrigin: string,
    private readonly handlers: HostBridgeHandlers,
    private readonly now: () => number = Date.now,
    private readonly nextId: () => string = () => crypto.randomUUID(),
  ) {
    if (!isHttpsOrigin(allowedOrigin)) throw new Error('HOST_ORIGIN_INVALID');
  }

  public start(): void {
    if (this.#started) return;
    this.#started = true;
    this.browser.addEventListener('message', this.#listener);
  }

  public stop(): void {
    if (!this.#started) return;
    this.browser.removeEventListener('message', this.#listener);
    this.clearReplayCache();
    this.#started = false;
  }

  public sendChallenge(payload: HostChallenge['payload']): void {
    if (!this.#started) throw new Error('HOST_BRIDGE_NOT_STARTED');
    const messageId = this.nextId();
    this.browser.parent.postMessage(Object.freeze({
      schemaVersion: 'HIP-1.0.0',
      eventType: 'host.challenge',
      messageId,
      correlationId: `host-bootstrap:${messageId}`,
      issuedAt: new Date(this.now()).toISOString(),
      payload: Object.freeze({ ...payload }),
    }), this.allowedOrigin);
  }

  public clearReplayCache(): void {
    for (const [messageId, reservation] of this.#messageReservations) {
      if (reservation.settled) this.#messageReservations.delete(messageId);
    }
    for (const [nonce, reservation] of this.#nonceReservations) {
      if (reservation.settled) this.#nonceReservations.delete(nonce);
    }
  }

  async #receive(event: MessageEventPort): Promise<void> {
    if (event.origin !== this.allowedOrigin) {
      this.handlers.onRejected('HOST_ORIGIN_FORBIDDEN');
      return;
    }
    if (event.source !== this.browser.parent) {
      this.handlers.onRejected('HOST_SOURCE_FORBIDDEN');
      return;
    }
    const parsed = parseEnvelope(event.data, this.now());
    if (parsed === undefined) {
      this.handlers.onRejected('HOST_MESSAGE_INVALID');
      this.#send(failedResponse(event.data, 'HOST_MESSAGE_INVALID', this.now(), this.nextId));
      return;
    }

    const priorMessage = this.#messageReservations.get(parsed.messageId);
    if (priorMessage !== undefined) {
      if (priorMessage.nonce !== parsed.nonce) {
        this.handlers.onRejected('HOST_MESSAGE_REPLAYED');
        this.#send(responseFor(parsed, false, 'HOST_MESSAGE_REPLAYED', this.now(), this.nextId));
      } else {
        this.#send(await priorMessage.response);
      }
      return;
    }
    const priorNonce = this.#nonceReservations.get(parsed.nonce);
    if (priorNonce !== undefined) {
      this.handlers.onRejected('HOST_MESSAGE_REPLAYED');
      this.#send(responseFor(parsed, false, 'HOST_MESSAGE_REPLAYED', this.now(), this.nextId));
      return;
    }

    const reservation: Reservation = {
      messageId: parsed.messageId,
      nonce: parsed.nonce,
      response: Promise.resolve(undefined as never),
      settled: false,
    };
    this.#messageReservations.set(parsed.messageId, reservation);
    this.#nonceReservations.set(parsed.nonce, reservation);
    reservation.response = Promise.resolve()
      .then(() => this.#execute(parsed))
      .finally(() => { reservation.settled = true; });
    this.#send(await reservation.response);
  }

  async #execute(envelope: HostEnvelope): Promise<HostResponse> {
    const controller = new AbortController();
    let active = true;
    let timeoutId: ReturnType<typeof setTimeout> | undefined;
    const context: HostDispatchContext = Object.freeze({
      messageId: envelope.messageId,
      correlationId: envelope.correlationId,
      signal: controller.signal,
      assertActive: () => {
        if (!active || controller.signal.aborted) throw new Error('HOST_COMMIT_EXPIRED');
      },
      commit: (effect: () => void) => {
        if (!active || controller.signal.aborted) throw new Error('HOST_COMMIT_EXPIRED');
        effect();
      },
    });
    try {
      await Promise.race([
        this.#dispatch(envelope, context),
        new Promise<never>((_resolve, reject) => {
          timeoutId = setTimeout(() => {
            active = false;
            controller.abort();
            reject(new Error('HOST_RESPONSE_TIMEOUT'));
          }, responseDeadlineMs);
        }),
      ]);
      context.assertActive();
      return responseFor(envelope, true, undefined, this.now(), this.nextId);
    } catch {
      this.handlers.onRejected('IDENTITY_DEPENDENCY_UNAVAILABLE');
      return responseFor(
        envelope, false, 'IDENTITY_DEPENDENCY_UNAVAILABLE', this.now(), this.nextId,
      );
    } finally {
      active = false;
      controller.abort();
      if (timeoutId !== undefined) clearTimeout(timeoutId);
    }
  }

  async #dispatch(envelope: HostEnvelope, context: HostDispatchContext): Promise<void> {
    switch (envelope.eventType) {
      case 'host.ready':
        await this.handlers.onBootstrapCode(envelope.payload.bootstrapCode!, context);
        return this.handlers.onHostReady(context);
      case 'auth.changed': return this.handlers.onAuthChanged(context);
      case 'navigate.requested':
        return this.handlers.onNavigateRequested(envelope.payload.targetRouteId!, context);
      case 'logout.requested': return this.handlers.onLogoutRequested(context);
      case 'theme.changed':
        return this.handlers.onThemeChanged(envelope.payload.theme!, context);
    }
  }

  #send(response: HostResponse): void {
    this.browser.parent.postMessage(response, this.allowedOrigin);
  }
}

function parseEnvelope(value: unknown, now: number): HostEnvelope | undefined {
  if (!isRecord(value) || Object.keys(value).sort().join() !== envelopeKeys.join()) return undefined;
  if (
    value.schemaVersion !== 'HIP-1.0.0'
    || typeof value.eventType !== 'string'
    || !eventTypes.has(value.eventType as HostEventType)
    || typeof value.messageId !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value.messageId)
    || typeof value.correlationId !== 'string'
    || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value.correlationId)
    || typeof value.issuedAt !== 'string'
    || typeof value.nonce !== 'string'
    || !/^[A-Za-z0-9_-]{32,128}$/.test(value.nonce)
    || !isRecord(value.payload)
    || Object.keys(value.payload).some((key) => !payloadKeys.has(key))
  ) return undefined;
  const actualPayloadKeys = Object.keys(value.payload).sort().join();
  const requiredPayload = {
    'host.ready': 'bootstrapCode',
    'auth.changed': '',
    'navigate.requested': 'targetRouteId',
    'logout.requested': '',
    'theme.changed': 'theme',
  }[value.eventType as HostEventType];
  if (actualPayloadKeys !== requiredPayload) return undefined;
  const issuedAt = Date.parse(value.issuedAt);
  if (!Number.isFinite(issuedAt) || issuedAt < now - replayWindowMs || issuedAt > now + 60_000) return undefined;
  if (
    (value.payload.bootstrapCode !== undefined
      && (typeof value.payload.bootstrapCode !== 'string'
        || !/^hb_[A-Za-z0-9_-]{43,86}$/.test(value.payload.bootstrapCode)))
    || (value.payload.targetRouteId !== undefined
      && !['shell.home', 'shell.session'].includes(String(value.payload.targetRouteId)))
    || (value.payload.theme !== undefined
      && !['light', 'dark', 'system'].includes(String(value.payload.theme)))
  ) return undefined;
  return value as HostEnvelope;
}

function responseFor(
  envelope: HostEnvelope,
  accepted: boolean,
  errorCode: HostFailureCode | undefined,
  now: number,
  nextId: () => string,
): HostResponse {
  return Object.freeze({
    schemaVersion: 'HIP-1.0.0',
    eventType: accepted ? 'host.acknowledged' : 'host.failed',
    messageId: nextId(),
    correlationId: envelope.correlationId,
    requestMessageId: envelope.messageId,
    issuedAt: new Date(now).toISOString(),
    status: accepted ? 'accepted' : 'rejected',
    ...(errorCode === undefined ? {} : { errorCode }),
  });
}

function failedResponse(
  candidate: unknown,
  errorCode: HostFailureCode,
  now: number,
  nextId: () => string,
): HostResponse {
  const value = isRecord(candidate) ? candidate : {};
  const correlationId = typeof value.correlationId === 'string'
    && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value.correlationId)
    ? value.correlationId : 'invalid';
  const requestMessageId = typeof value.messageId === 'string'
    && /^[0-9a-f-]{36}$/.test(value.messageId) ? value.messageId : nextId();
  return Object.freeze({
    schemaVersion: 'HIP-1.0.0', eventType: 'host.failed', messageId: nextId(), correlationId,
    requestMessageId, issuedAt: new Date(now).toISOString(), status: 'rejected', errorCode,
  });
}

function isHttpsOrigin(value: string): boolean {
  try {
    const parsed = new URL(value);
    const octets = parsed.hostname.split('.').map((part) => Number.parseInt(part, 10));
    const deterministicLoopback = parsed.protocol === 'http:'
      && octets.length === 4
      && octets[0] === 127
      && octets[1] === 0 && octets[2] === 0 && octets[3] === 1;
    return (parsed.protocol === 'https:' || deterministicLoopback)
      && parsed.origin === value && parsed.username === '';
  } catch {
    return false;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && Object.getPrototypeOf(value) === Object.prototype;
}
