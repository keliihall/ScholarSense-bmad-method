package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Independently retries alert transport. This automatic delivery path deliberately does not call
 * audit ingestion, preventing an alert-about-alert recursive audit loop.
 */
public final class AuditAlertRelayProcessor {
    private final AuditAlertWorkRepository work;
    private final AuditAlertTransport transport;
    private final AuditClock clock;
    private final AuditAlertRetryJitter jitter;
    private final LowCardinalityAuditMetrics metrics;
    private final int batchSize;
    private final Duration claimLease;

    public AuditAlertRelayProcessor(
            AuditAlertWorkRepository work,
            AuditAlertTransport transport,
            AuditClock clock,
            AuditAlertRetryJitter jitter,
            LowCardinalityAuditMetrics metrics,
            int batchSize,
            Duration claimLease) {
        this.work = Objects.requireNonNull(work);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        this.jitter = Objects.requireNonNull(jitter);
        this.metrics = Objects.requireNonNull(metrics);
        if (batchSize != 100 || !Duration.ofSeconds(60).equals(claimLease)) {
            throw new IllegalArgumentException("AUDIT_ALERT_RELAY_POLICY_INVALID");
        }
        this.batchSize = batchSize;
        this.claimLease = claimLease;
    }

    public AuditAlertBatchResult runBatch() {
        Instant now = clock.now();
        var claims = work.claimDue(batchSize, now, claimLease);
        int confirmed = 0;
        int retried = 0;
        int fenced = 0;
        for (ClaimedAuditAlert claim : claims) {
            try {
                transport.send(claim.safePayload());
                if (work.confirm(claim.alertId(), claim.attempts(), clock.now())) {
                    confirmed++;
                    metrics.record("audit.alert.delivery", Map.of("outcome", "confirmed"));
                } else {
                    fenced++;
                    metrics.record("audit.alert.delivery", Map.of("outcome", "fenced"));
                }
            } catch (RuntimeException unavailable) {
                Duration delay = retryDelay(claim.attempts(), jitter.next());
                if (work.retry(
                        claim.alertId(), claim.attempts(), clock.now().plus(delay),
                        "AUDIT_ALERT_TRANSPORT_UNAVAILABLE")) {
                    retried++;
                    metrics.record("audit.alert.delivery", Map.of("outcome", "retried"));
                } else {
                    fenced++;
                    metrics.record("audit.alert.delivery", Map.of("outcome", "fenced"));
                }
            }
        }
        return new AuditAlertBatchResult(claims.size(), confirmed, retried, fenced);
    }

    static Duration retryDelay(int attempts, double jitter) {
        if (attempts < 1 || jitter < 0.5 || jitter > 1.0 || !Double.isFinite(jitter)) {
            throw new IllegalArgumentException("AUDIT_ALERT_RETRY_POLICY_INVALID");
        }
        long cap = Math.min(60L, 1L << Math.min(30, attempts - 1));
        return Duration.ofMillis((long) (cap * 1000L * jitter));
    }
}
