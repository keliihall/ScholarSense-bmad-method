package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

/** Performs external I/O only after a committed outbox claim, then fences its finalizer. */
public final class QualityTaskRelayProcessor {
    static final long MAX_ATTEMPTS = 8;
    private static final String UNAVAILABLE = "QUALITY_TASK_TARGET_UNAVAILABLE";
    private static final String EXHAUSTED = "QUALITY_TASK_RELAY_ATTEMPTS_EXHAUSTED";

    private final QualityTaskRelayWorkPort work;
    private final QualityTaskTargetPort target;

    public QualityTaskRelayProcessor(
            QualityTaskRelayWorkPort work, QualityTaskTargetPort target) {
        this.work = Objects.requireNonNull(work);
        this.target = Objects.requireNonNull(target);
    }

    public QualityTaskRelayResult runOnce() {
        QualityTaskRelayClaim claim = work.claimNext();
        if (claim == null) return QualityTaskRelayResult.IDLE;
        try (QualityTaskRelayWorkPort.SendPermit permit = work.acquireSendPermit(claim)) {
            if (!permit.authorized()) return QualityTaskRelayResult.FENCED;
            QualityTaskTargetResult targetResult;
            try {
                targetResult = Objects.requireNonNull(target.deliver(new QualityTaskTargetRequest(
                        claim.eventId(), claim.deliveryKey(), claim.routeSequence(),
                        claim.payload(), claim.payloadDigest())));
            } catch (RuntimeException unavailable) {
                targetResult = QualityTaskTargetResult.retryable(UNAVAILABLE);
            }
            boolean accepted;
            QualityTaskRelayResult result;
            switch (targetResult.outcome()) {
                case CONFIRMED -> {
                    accepted = permit.confirm(targetResult.receiptId());
                    result = QualityTaskRelayResult.CONFIRMED;
                }
                case RETRYABLE -> {
                    if (claim.attempt() >= MAX_ATTEMPTS) {
                        accepted = permit.fail(EXHAUSTED);
                        result = QualityTaskRelayResult.FAILED;
                    } else {
                        accepted = permit.retry(targetResult.errorCode());
                        result = QualityTaskRelayResult.RETRY_SCHEDULED;
                    }
                }
                case PERMANENT_FAILURE -> {
                    accepted = permit.fail(targetResult.errorCode());
                    result = QualityTaskRelayResult.FAILED;
                }
                default -> throw new IllegalStateException(
                        "INGESTION_QUALITY_TASK_TARGET_INVALID");
            }
            return accepted ? result : QualityTaskRelayResult.FENCED;
        }
    }
}
