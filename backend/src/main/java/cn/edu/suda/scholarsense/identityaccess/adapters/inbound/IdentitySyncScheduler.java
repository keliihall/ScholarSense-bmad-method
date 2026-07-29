package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJobService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncWorker;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;

/** A trigger only: jobs, attempts, leases, fencing and watermarks remain persisted. */
public final class IdentitySyncScheduler {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final IdentitySyncJobService jobs;
    private final IdentitySyncWorker worker;
    private final CheckpointKey key;
    private final int retryBudget;
    private final String leaseOwner;

    public IdentitySyncScheduler(
            IdentitySyncJobService jobs,
            IdentitySyncWorker worker,
            CheckpointKey key,
            int retryBudget) {
        this.jobs = jobs;
        this.worker = worker;
        this.key = key;
        this.retryBudget = retryBudget;
        this.leaseOwner = "identity-sync-" + randomHex(8);
    }

    @Scheduled(
            initialDelayString = "${scholarsense.identity-sync.poll-interval}",
            fixedDelayString = "${scholarsense.identity-sync.poll-interval}")
    public void poll() {
        jobs.ensureRequested(key, retryBudget, randomHex(16));
        worker.runNext(leaseOwner);
    }

    private static String randomHex(int bytesCount) {
        byte[] bytes = new byte[bytesCount];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
