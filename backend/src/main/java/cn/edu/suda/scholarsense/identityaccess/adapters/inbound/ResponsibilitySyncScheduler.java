package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJobService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncWorker;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;

/** Persisted responsibility incremental trigger on its projection-scoped route. */
public final class ResponsibilitySyncScheduler {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final IdentitySyncJobService jobs;
    private final ResponsibilitySyncWorker worker;
    private final CheckpointKey key;
    private final int retryBudget;
    private final String leaseOwner;

    public ResponsibilitySyncScheduler(
            IdentitySyncJobService jobs,
            ResponsibilitySyncWorker worker,
            CheckpointKey key,
            int retryBudget) {
        this.jobs = java.util.Objects.requireNonNull(jobs);
        this.worker = java.util.Objects.requireNonNull(worker);
        this.key = java.util.Objects.requireNonNull(key);
        this.retryBudget = retryBudget;
        this.leaseOwner = "responsibility-sync-" + randomHex(8);
    }

    @Scheduled(
            initialDelayString =
                    "${scholarsense.responsibility-sync.poll-interval:30000}",
            fixedDelayString =
                    "${scholarsense.responsibility-sync.poll-interval:30000}")
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
