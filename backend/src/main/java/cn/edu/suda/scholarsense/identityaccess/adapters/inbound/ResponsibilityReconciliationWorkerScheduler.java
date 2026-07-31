package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationWorker;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls the durable full-reconciliation queue; correctness remains lease/fencing based. */
public final class ResponsibilityReconciliationWorkerScheduler {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ResponsibilityReconciliationWorker worker;
    private final String leaseOwner;

    public ResponsibilityReconciliationWorkerScheduler(
            ResponsibilityReconciliationWorker worker) {
        this.worker = java.util.Objects.requireNonNull(worker);
        this.leaseOwner =
                "responsibility-reconciliation-" + randomHex(8);
    }

    @Scheduled(
            initialDelayString =
                    "${scholarsense.responsibility-reconciliation.worker-poll:PT30S}",
            fixedDelayString =
                    "${scholarsense.responsibility-reconciliation.worker-poll:PT30S}")
    public void poll() {
        worker.runNext(leaseOwner);
    }

    private static String randomHex(int bytesCount) {
        byte[] bytes = new byte[bytesCount];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
