package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationDecision;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Bounded durable observation worker with a lease recheck before every terminal write. */
public final class RecoveryObservationJobProcessor {
    private static final int BATCH_SIZE = 20;
    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final Duration OBSERVING_YIELD = Duration.ofSeconds(30);
    private static final Duration TECHNICAL_RETRY = Duration.ofSeconds(5);

    private final RecoveryObservationWorkPort work;
    private final RecoveryObservationTrustedTimePort time;
    private final String workerDigest;
    private final RecoveryObservationPolicy policy = new RecoveryObservationPolicy();

    public RecoveryObservationJobProcessor(
            RecoveryObservationWorkPort work,
            RecoveryObservationTrustedTimePort time,
            String workerDigest) {
        this.work = Objects.requireNonNull(work);
        this.time = Objects.requireNonNull(time);
        if (workerDigest == null || !workerDigest.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("RECOVERY_OBSERVATION_WORKER_INVALID");
        }
        this.workerDigest = workerDigest;
    }

    public RecoveryObservationWorkerResult runBatch() {
        List<RecoveryObservationCandidate> candidates = Objects.requireNonNull(
                work.findClaimable(BATCH_SIZE, now()));
        int claimed = 0, completed = 0, retries = 0, yielded = 0, failed = 0, fenced = 0;
        for (RecoveryObservationCandidate candidate : candidates) {
            RecoveryObservationClaim claim;
            try {
                claim = Objects.requireNonNull(work.claim(
                        candidate.jobId(), workerDigest, now(), LEASE));
                claimed++;
            } catch (RuntimeException staleOrLost) {
                fenced++;
                continue;
            }
            RecoveryObservationDecision decision = policy.evaluate(
                    claim.observation(), claim.currentFence(),
                    claim.allAffectedEligibilitiesRecovering(), now());
            Instant terminalNow = now();
            if (!work.isLeaseCurrent(
                    candidate.jobId(), claim.leaseGeneration(), terminalNow)) {
                fenced++;
                continue;
            }
            switch (decision.status()) {
                case READY, RELAPSED, POLICY_DRIFT -> {
                    if (work.complete(candidate.jobId(), claim.leaseGeneration(),
                            decision, terminalNow)) completed++;
                    else fenced++;
                }
                case DEPENDENCY_UNAVAILABLE -> {
                    if (work.retry(candidate.jobId(), claim.leaseGeneration(),
                            terminalNow.plus(TECHNICAL_RETRY), terminalNow)) retries++;
                    else fenced++;
                }
                case NOT_READY -> {
                    if (work.yield(candidate.jobId(), claim.leaseGeneration(),
                            terminalNow.plus(OBSERVING_YIELD), terminalNow)) yielded++;
                    else fenced++;
                }
            }
        }
        return new RecoveryObservationWorkerResult(
                claimed, completed, retries, yielded, failed, fenced);
    }

    private Instant now() {
        Instant value = Objects.requireNonNull(time.now());
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalStateException("INGESTION_QUALITY_TRUSTED_TIME_INVALID");
        }
        return value;
    }
}
