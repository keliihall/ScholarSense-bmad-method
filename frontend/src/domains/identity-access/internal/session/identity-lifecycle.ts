import type { LifecycleBoundary, VolatileClientState } from '../../../../app/state/volatile-client-state';
import type { HostBridge } from '../host/host-bridge';

/** One teardown path for query data, drafts, identity projection, continuation proof and host replay. */
export class IdentityLifecycleCoordinator {
  public constructor(
    private readonly volatileState: VolatileClientState,
    private readonly clearIdentityProjection: () => void,
    private readonly hostBridge: Pick<HostBridge, 'clearReplayCache'>,
  ) {}

  public clear(event: LifecycleBoundary): void {
    this.volatileState.handleLifecycle(event);
    this.clearIdentityProjection();
    this.hostBridge.clearReplayCache();
  }
}
