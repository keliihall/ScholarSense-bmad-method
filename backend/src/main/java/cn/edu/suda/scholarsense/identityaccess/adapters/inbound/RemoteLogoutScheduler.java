package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.RemoteLogoutProcessor;
import org.springframework.scheduling.annotation.Scheduled;

/** Time-triggered inbound adapter; work claiming remains shared-store and skip-locked. */
public final class RemoteLogoutScheduler {
    private static final int MAX_BATCH = 32;
    private final RemoteLogoutProcessor processor;

    public RemoteLogoutScheduler(RemoteLogoutProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${scholarsense.identity.remote-logout-delay:PT5S}")
    public void drainDueWork() {
        for (int processed = 0; processed < MAX_BATCH && processor.processOne(); processed++) {
            // Bounded batches keep scheduling fair while draining due shared-store work.
        }
    }
}
