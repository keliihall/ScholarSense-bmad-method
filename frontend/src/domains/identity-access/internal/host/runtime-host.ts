import type { Pinia } from 'pinia';
import type { Router } from 'vue-router';

import { queryClient } from '../../../../app/state/query-client';
import { VolatileClientState } from '../../../../app/state/volatile-client-state';
import { HostBridge } from './host-bridge';
import type { HostFailureCode } from './host-bridge';
import { IdentityLifecycleCoordinator } from '../session/identity-lifecycle';
import {
  IdentitySessionClient, newSessionCommandIdempotencyKey,
} from '../session/identity-session-client';
import type { HostInputRejectionCode } from '../session/identity-session-client';
import { useIdentityState } from '../session/identity-state';

type HostRuntime = Readonly<{
  schemaVersion: 'HIP-1.0.0';
  portalOrigin: string;
}>;

let activeBridge: HostBridge | undefined;

export async function startRuntimeHostBridge(router: Router, pinia: Pinia): Promise<void> {
  if (window.parent === window || activeBridge !== undefined) return;
  const identity = useIdentityState(pinia);
  const client = new IdentitySessionClient();
  let pendingBridge: HostBridge | undefined;
  try {
    const runtime = await loadRuntime();
    let lifecycle: IdentityLifecycleCoordinator | undefined;
    const bridge = new HostBridge(
      window as unknown as ConstructorParameters<typeof HostBridge>[0],
      runtime.portalOrigin,
      {
        onHostReady: async (context) => context.commit(() => identity.setHostReady(true)),
        onAuthChanged: async (context) => {
          const boundary = requiredLifecycle(lifecycle);
          const hostWasReady = identity.hostReady;
          boundary.clear('host-session-invalid');
          try {
            const current = await client.current(context.signal);
            context.commit(() => {
              identity.acceptSession(current);
              identity.setHostReady(hostWasReady);
            });
          } catch (failure) {
            if (!context.signal.aborted) {
              identity.fail(safeFailureCode(failure));
              await router.replace({ name: 'shell-recovery', query: { reason: identity.shellState } });
            }
            throw failure;
          }
        },
        onNavigateRequested: async (route, context) => {
          const current = await client.current(context.signal);
          context.commit(() => identity.acceptSession(current));
          context.assertActive();
          await router.push(route === 'shell.session' ? '/session' : '/');
        },
        onLogoutRequested: async (context) => {
          const current = identity.session ?? await client.current(context.signal);
          const csrf = await client.csrfProof(context.signal);
          await client.logout(
            current.sessionVersion, csrf, newSessionCommandIdempotencyKey(), context.signal,
          );
          context.commit(() => requiredLifecycle(lifecycle).clear('logout'));
          context.assertActive();
          await router.replace({ name: 'shell-recovery', query: { reason: 'session-expired' } });
        },
        onThemeChanged: async (theme, context) => {
          context.commit(() => { document.documentElement.dataset.portalTheme = theme; });
        },
        onBootstrapCode: async (code, context) => {
          const csrf = await client.csrfProof(context.signal);
          await client.exchangeBootstrap(code, runtime.portalOrigin, csrf, context.signal);
        },
        onRejected: (code) => {
          reportSafeHostFailure(code);
          if (isHostInputRejectionCode(code)) void auditHostRejection(client, code);
        },
      },
    );
    pendingBridge = bridge;
    lifecycle = new IdentityLifecycleCoordinator(
      new VolatileClientState(queryClient), () => identity.clear(), bridge,
    );
    activeBridge = bridge;
    bridge.start();
    window.setTimeout(() => {
      if (!identity.hostReady) {
        bridge.stop();
        if (activeBridge === bridge) activeBridge = undefined;
        void showHostDegraded(identity, router);
      }
    }, 5_000);
    const challenge = await client.issueHostBootstrap(await client.csrfProof());
    if (challenge.origin !== runtime.portalOrigin) throw new Error('HOST_ORIGIN_FORBIDDEN');
    bridge.sendChallenge(challenge);
  } catch {
    pendingBridge?.stop();
    if (activeBridge === pendingBridge) activeBridge = undefined;
    await showHostDegraded(identity, router);
  }
}

async function showHostDegraded(
  identity: ReturnType<typeof useIdentityState>,
  router: Router,
): Promise<void> {
  identity.degradeHost();
  await router.replace({ name: 'shell-recovery', query: { reason: 'host-degraded' } });
}

function requiredLifecycle(
  lifecycle: IdentityLifecycleCoordinator | undefined,
): IdentityLifecycleCoordinator {
  if (lifecycle === undefined) throw new Error('HOST_LIFECYCLE_UNAVAILABLE');
  return lifecycle;
}

function safeFailureCode(failure: unknown): string {
  return failure instanceof Error && /^[A-Z0-9_]{3,128}$/.test(failure.message)
    ? failure.message : 'IDENTITY_DEPENDENCY_UNAVAILABLE';
}

async function loadRuntime(): Promise<HostRuntime> {
  const response = await globalThis.fetch('/api/v1/identity-runtime', {
    credentials: 'include', headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new Error('HOST_RUNTIME_UNAVAILABLE');
  const value: unknown = await response.json();
  if (!isRecord(value)
    || Object.keys(value).sort().join() !== ['portalOrigin', 'schemaVersion'].join()
    || value.schemaVersion !== 'HIP-1.0.0'
    || typeof value.portalOrigin !== 'string') {
    throw new Error('HOST_RUNTIME_INVALID');
  }
  return value as HostRuntime;
}

function reportSafeHostFailure(code: HostFailureCode): void {
  window.dispatchEvent(new CustomEvent('scholarsense:host-failure', {
    detail: Object.freeze({ code }),
  }));
}

async function auditHostRejection(
  client: IdentitySessionClient,
  code: HostInputRejectionCode,
): Promise<void> {
  try {
    const csrf = await client.csrfProof();
    await client.reportHostInputRejection(code, csrf);
  } catch {
    // Rejection telemetry must never weaken the local fail-closed decision.
  }
}

function isHostInputRejectionCode(code: HostFailureCode): code is HostInputRejectionCode {
  return code === 'HOST_ORIGIN_FORBIDDEN'
    || code === 'HOST_SOURCE_FORBIDDEN'
    || code === 'HOST_MESSAGE_INVALID'
    || code === 'HOST_MESSAGE_REPLAYED';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
