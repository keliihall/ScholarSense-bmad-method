import { describe, expect, it, vi } from 'vitest';

import { HostBridge } from '../../src/domains/identity-access';
import type {
  HostBridgeHandlers, HostChallenge, HostEnvelope, HostResponse,
} from '../../src/domains/identity-access';

const NOW = Date.parse('2026-07-20T00:00:00Z');
const ORIGIN = 'https://portal.stage.invalid';

function envelope(overrides: Partial<HostEnvelope> = {}): HostEnvelope {
  return {
    schemaVersion: 'HIP-1.0.0',
    eventType: 'navigate.requested',
    messageId: '018f7b87-ee53-7942-9aec-d5948b86b811',
    correlationId: 'correlation-1',
    issuedAt: new Date(NOW).toISOString(),
    nonce: 'abcdefghijklmnopqrstuvwxyzABCDEF',
    payload: { targetRouteId: 'shell.session' },
    ...overrides,
  };
}

function fixture() {
  const responses: Array<{ response: HostResponse; targetOrigin: string }> = [];
  const challenges: Array<{ challenge: HostChallenge; targetOrigin: string }> = [];
  let listener: ((event: { data: unknown; origin: string; source: unknown }) => void) | undefined;
  const parent = { postMessage: (message: HostResponse | HostChallenge, targetOrigin: string) => {
    if (message.eventType === 'host.challenge') challenges.push({ challenge: message, targetOrigin });
    else responses.push({ response: message, targetOrigin });
  } };
  const browser = {
    parent,
    addEventListener: (_type: 'message', value: typeof listener) => { listener = value; },
    removeEventListener: (_type: 'message', _value: typeof listener) => { listener = undefined; },
  };
  const handlers = {
    onHostReady: vi.fn<HostBridgeHandlers['onHostReady']>().mockResolvedValue(undefined),
    onAuthChanged: vi.fn<HostBridgeHandlers['onAuthChanged']>().mockResolvedValue(undefined),
    onNavigateRequested: vi.fn<HostBridgeHandlers['onNavigateRequested']>()
      .mockResolvedValue(undefined),
    onLogoutRequested: vi.fn<HostBridgeHandlers['onLogoutRequested']>().mockResolvedValue(undefined),
    onThemeChanged: vi.fn<HostBridgeHandlers['onThemeChanged']>().mockResolvedValue(undefined),
    onBootstrapCode: vi.fn<HostBridgeHandlers['onBootstrapCode']>().mockResolvedValue(undefined),
    onRejected: vi.fn<HostBridgeHandlers['onRejected']>(),
  } satisfies HostBridgeHandlers;
  const bridge = new HostBridge(browser, ORIGIN, handlers, () => NOW,
    () => '018f7b87-ee53-7942-9aec-d5948b86b812');
  bridge.start();
  const emit = (data: unknown, origin = ORIGIN, source: unknown = parent) => {
    listener?.({ data, origin, source });
    return new Promise((resolve) => setTimeout(resolve, 0));
  };
  return { bridge, handlers, responses, challenges, parent, emit };
}

describe('host bridge', () => {
  it('sends one opaque host challenge only to the exact portal origin', () => {
    const current = fixture();
    current.bridge.sendChallenge({
      bootstrapCode: 'hb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
      audience: 'scholarsense-web',
      origin: ORIGIN,
      expiresAt: '2026-07-20T00:01:00Z',
    });
    expect(current.challenges).toHaveLength(1);
    expect(current.challenges[0]?.targetOrigin).toBe(ORIGIN);
    expect(current.challenges[0]?.challenge.payload).toEqual({
      bootstrapCode: 'hb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ',
      audience: 'scholarsense-web',
      origin: ORIGIN,
      expiresAt: '2026-07-20T00:01:00Z',
    });
  });

  it('uses the exact target origin and acknowledges one allowed navigation', async () => {
    const { emit, responses, handlers } = fixture();
    await emit(envelope());
    expect(handlers.onNavigateRequested).toHaveBeenCalledExactlyOnceWith(
      'shell.session', expect.objectContaining({ messageId: envelope().messageId }),
    );
    expect(responses).toHaveLength(1);
    expect(responses[0]?.targetOrigin).toBe(ORIGIN);
    expect(responses[0]?.response.status).toBe('accepted');
  });

  it('rejects forged origin/source without reflecting data to the attacker', async () => {
    const first = fixture();
    await first.emit(envelope(), 'https://portal.stage.invalid.attacker.example');
    expect(first.handlers.onRejected).toHaveBeenCalledWith('HOST_ORIGIN_FORBIDDEN');
    expect(first.responses).toHaveLength(0);

    const second = fixture();
    await second.emit(envelope(), ORIGIN, {});
    expect(second.handlers.onRejected).toHaveBeenCalledWith('HOST_SOURCE_FORBIDDEN');
    expect(second.responses).toHaveLength(0);
  });

  it('rejects unknown fields, old messages, null-like origins and unknown events', async () => {
    for (const candidate of [
      { ...envelope(), unexpectedField: 'forbidden' },
      envelope({ issuedAt: new Date(NOW - 300_001).toISOString() }),
      envelope({ eventType: 'unknown' as HostEnvelope['eventType'] }),
    ]) {
      const current = fixture();
      await current.emit(candidate);
      expect(current.responses[0]?.response.errorCode).toBe('HOST_MESSAGE_INVALID');
      expect(current.handlers.onNavigateRequested).not.toHaveBeenCalled();
    }
  });

  it('requires host.ready to carry the bootstrap proof and exchanges it before readiness', async () => {
    const invalid = fixture();
    await invalid.emit(envelope({ eventType: 'host.ready', payload: {} }));
    expect(invalid.responses[0]?.response.errorCode).toBe('HOST_MESSAGE_INVALID');
    expect(invalid.handlers.onHostReady).not.toHaveBeenCalled();

    const current = fixture();
    const bootstrapCode = 'hb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ';
    await current.emit(envelope({ eventType: 'host.ready', payload: { bootstrapCode } }));
    expect(current.handlers.onBootstrapCode).toHaveBeenCalledExactlyOnceWith(
      bootstrapCode, expect.objectContaining({ messageId: envelope().messageId }),
    );
    expect(current.handlers.onHostReady).toHaveBeenCalledOnce();
    expect(current.handlers.onBootstrapCode.mock.invocationCallOrder[0])
      .toBeLessThan(current.handlers.onHostReady.mock.invocationCallOrder[0]!);
  });

  it('reuses an identical response without repeating side effects and rejects nonce substitution', async () => {
    const current = fixture();
    await current.emit(envelope());
    await current.emit(envelope());
    expect(current.handlers.onNavigateRequested).toHaveBeenCalledTimes(1);
    expect(current.responses[1]?.response).toBe(current.responses[0]?.response);

    await current.emit(envelope({ messageId: '018f7b87-ee53-7942-9aec-d5948b86b813' }));
    expect(current.handlers.onNavigateRequested).toHaveBeenCalledTimes(1);
    expect(current.responses[2]?.response.errorCode).toBe('HOST_MESSAGE_REPLAYED');
  });

  it('clears replay state on identity lifecycle teardown', async () => {
    const current = fixture();
    await current.emit(envelope());
    current.bridge.clearReplayCache();
    await current.emit(envelope());
    expect(current.handlers.onNavigateRequested).toHaveBeenCalledTimes(2);
  });

  it('reserves messageId and nonce before awaiting so in-flight replay shares one side effect', async () => {
    let release!: () => void;
    const blocked = new Promise<void>((resolve) => { release = resolve; });
    const current = fixture();
    current.handlers.onNavigateRequested.mockImplementation(async () => blocked);

    const first = current.emit(envelope());
    const replay = current.emit(envelope());
    await Promise.resolve();
    expect(current.handlers.onNavigateRequested).toHaveBeenCalledTimes(1);
    release();
    await Promise.all([first, replay]);
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(current.handlers.onNavigateRequested).toHaveBeenCalledTimes(1);
    expect(current.responses).toHaveLength(2);
    expect(current.responses[1]?.response).toBe(current.responses[0]?.response);
  });

  it('expires the commit token at five seconds and blocks a late side-effect commit', async () => {
    vi.useFakeTimers();
    try {
      let release!: () => void;
      const blocked = new Promise<void>((resolve) => { release = resolve; });
      const sideEffect = vi.fn();
      const current = fixture();
      current.handlers.onNavigateRequested.mockImplementation(async (_route, context) => {
        await blocked;
        context.commit(sideEffect);
      });

      const received = current.emit(envelope());
      await vi.advanceTimersByTimeAsync(5_000);
      await received;
      expect(current.responses[0]?.response.errorCode).toBe('IDENTITY_DEPENDENCY_UNAVAILABLE');

      release();
      await Promise.resolve();
      await Promise.resolve();
      expect(sideEffect).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });
});
