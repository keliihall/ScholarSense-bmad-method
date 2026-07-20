/** Public entry for the identity-access frontend domain. Internal files must not be imported cross-domain. */
export { HostBridge } from './internal/host/host-bridge';
export type {
  HostBridgeHandlers,
  HostChallenge,
  HostEnvelope,
  HostEventType,
  HostFailureCode,
  HostResponse,
} from './internal/host/host-bridge';
export { IdentitySessionClient } from './internal/session/identity-session-client';
export type {
  CsrfProof,
  CurrentIdentitySession,
  ReauthenticationTarget,
} from './internal/session/identity-session-client';
export { useIdentityState } from './internal/session/identity-state';
export type { ShellState } from './internal/session/identity-state';
export { IdentityLifecycleCoordinator } from './internal/session/identity-lifecycle';
export { startRuntimeHostBridge } from './internal/host/runtime-host';
