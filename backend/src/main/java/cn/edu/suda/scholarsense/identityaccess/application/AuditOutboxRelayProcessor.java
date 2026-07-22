package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.auditoperations.api.AuditIngressOutcome;
import cn.edu.suda.scholarsense.auditoperations.api.AuditIngressResult;
import cn.edu.suda.scholarsense.auditoperations.api.AuditLedgerIngressPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Producer-owned replay loop. Claim, center append and confirm are deliberately three transactions. */
public final class AuditOutboxRelayProcessor {
    private final IdentityAuditRelayWorkRepository work;
    private final AuditLedgerIngressPort center;
    private final AuditRelayClock clock;
    private final AuditRetryJitter jitter;
    private final int batchSize;
    private final Duration claimLease;

    public AuditOutboxRelayProcessor(
            IdentityAuditRelayWorkRepository work,
            AuditLedgerIngressPort center,
            AuditRelayClock clock,
            AuditRetryJitter jitter,
            int batchSize,
            Duration claimLease) {
        this.work = Objects.requireNonNull(work);
        this.center = Objects.requireNonNull(center);
        this.clock = Objects.requireNonNull(clock);
        this.jitter = Objects.requireNonNull(jitter);
        if (batchSize != 100 || !Duration.ofSeconds(60).equals(claimLease)) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_RELAY_POLICY_INVALID");
        }
        this.batchSize = batchSize;
        this.claimLease = claimLease;
    }

    public AuditRelayBatchResult runBatch() {
        Instant claimedAt = clock.now();
        List<AuditRelayClaim> claims = work.claimDue(batchSize, claimedAt, claimLease);
        int confirmed = 0;
        int retried = 0;
        int failed = 0;
        int fenced = 0;
        for (AuditRelayClaim claim : claims) {
            try {
                if (claim instanceof RejectedLocalAudit rejected) {
                    AuditIngressResult result = center.rejectContract(rejected.rejection());
                    if (work.fail(
                            rejected.eventId(), rejected.attempts(),
                            result.errorCode(), clock.now())) {
                        failed++;
                    } else {
                        fenced++;
                    }
                    continue;
                }
                ClaimedLocalAudit valid = (ClaimedLocalAudit) claim;
                AuditIngressResult result = center.ingest(valid.source());
                if (result.outcome() == AuditIngressOutcome.APPENDED
                        || result.outcome() == AuditIngressOutcome.EXACT_DUPLICATE) {
                    try {
                        if (work.confirm(
                                valid.source().eventId(), valid.attempts(), clock.now())) {
                            confirmed++;
                        } else {
                            fenced++;
                        }
                    } catch (RuntimeException crashWindow) {
                        // Center commit is already durable. Leave the source claim for lease-expiry replay.
                    }
                } else if (result.outcome() == AuditIngressOutcome.COLLISION || !result.retryable()) {
                    if (work.fail(
                            valid.source().eventId(), valid.attempts(), result.errorCode(), clock.now())) {
                        failed++;
                    } else {
                        fenced++;
                    }
                } else if (retry(valid, result.errorCode())) {
                    retried++;
                } else {
                    fenced++;
                }
            } catch (RuntimeException centerUnavailable) {
                if (claim instanceof ClaimedLocalAudit valid
                        && retry(valid, "AUDIT_LEDGER_UNAVAILABLE")) {
                    retried++;
                } else if (claim instanceof RejectedLocalAudit rejected
                        && work.retry(
                            rejected.eventId(), rejected.attempts(),
                            clock.now().plus(AuditRetryPolicy.delay(
                                    rejected.attempts(), jitter.next())),
                            "AUDIT_LEDGER_UNAVAILABLE")) {
                    retried++;
                } else {
                    fenced++;
                }
            }
        }
        return new AuditRelayBatchResult(claims.size(), confirmed, retried, failed, fenced);
    }

    private boolean retry(ClaimedLocalAudit claim, String errorCode) {
        Instant now = clock.now();
        Duration delay = AuditRetryPolicy.delay(claim.attempts(), jitter.next());
        return work.retry(
                claim.source().eventId(), claim.attempts(), now.plus(delay), errorCode);
    }
}
