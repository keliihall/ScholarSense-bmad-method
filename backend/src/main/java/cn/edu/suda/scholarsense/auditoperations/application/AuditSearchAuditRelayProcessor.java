package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Duration;
import java.util.Objects;

/** Producer-owned relay. It never emits another read audit fact while delivering one. */
public final class AuditSearchAuditRelayProcessor {
    private final AuditSearchRelayWorkPort work;
    private final AuditCenterIngressPort center;
    private final AuditSearchRelayClock clock;
    private final AuditSearchRelayJitter jitter;
    private final int batchSize;
    private final Duration lease;

    public AuditSearchAuditRelayProcessor(
            AuditSearchRelayWorkPort work,
            AuditCenterIngressPort center,
            AuditSearchRelayClock clock,
            AuditSearchRelayJitter jitter,
            int batchSize,
            Duration lease) {
        this.work = Objects.requireNonNull(work);
        this.center = Objects.requireNonNull(center);
        this.clock = Objects.requireNonNull(clock);
        this.jitter = Objects.requireNonNull(jitter);
        if (batchSize != 100 || !Duration.ofSeconds(60).equals(lease)) {
            throw new IllegalArgumentException("AUDIT_SEARCH_RELAY_POLICY_INVALID");
        }
        this.batchSize = batchSize;
        this.lease = lease;
    }

    public AuditSearchRelayBatchResult runBatch() {
        var claims = work.claimDue(batchSize, clock.now(), lease);
        int confirmed = 0;
        int retried = 0;
        int failed = 0;
        int fenced = 0;
        for (var claim : claims) {
            try {
                var result = center.ingest(claim.source());
                if (result.outcome() == AuditCenterIngressOutcome.APPENDED
                        || result.outcome() == AuditCenterIngressOutcome.EXACT_DUPLICATE) {
                    if (work.confirm(claim.source().eventId(), claim.attempts(), clock.now())) confirmed++;
                    else fenced++;
                } else if (result.outcome() == AuditCenterIngressOutcome.COLLISION || !result.retryable()) {
                    if (work.fail(claim.source().eventId(), claim.attempts(), clock.now(), result.errorCode())) failed++;
                    else fenced++;
                } else if (retry(claim, result.errorCode())) retried++;
                else fenced++;
            } catch (RuntimeException unavailable) {
                if (retry(claim, "AUDIT_LEDGER_UNAVAILABLE")) retried++;
                else fenced++;
            }
        }
        return new AuditSearchRelayBatchResult(claims.size(), confirmed, retried, failed, fenced);
    }

    private boolean retry(AuditSearchRelayClaim claim, String code) {
        long cap = Math.min(60, 1L << Math.min(5, Math.max(0, claim.attempts() - 1)));
        double factor = Math.max(0.5, Math.min(1.0, jitter.next()));
        var next = clock.now().plusMillis(Math.max(1, (long) (cap * 1_000 * factor)));
        return work.retry(claim.source().eventId(), claim.attempts(), next, code);
    }
}
