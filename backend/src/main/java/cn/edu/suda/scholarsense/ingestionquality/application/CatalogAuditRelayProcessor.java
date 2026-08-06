package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.outbox.AuditIngressOutcome;
import cn.edu.suda.scholarsense.shared.outbox.AuditIngressResult;
import cn.edu.suda.scholarsense.shared.outbox.AuditLedgerIngressPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Producer-owned relay; the central ledger is reached only through the shared public port. */
public final class CatalogAuditRelayProcessor {
    private static final int BATCH_SIZE = 100;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private final CatalogAuditRelayWorkPort work;
    private final AuditLedgerIngressPort center;
    private final Clock clock;

    public CatalogAuditRelayProcessor(
            CatalogAuditRelayWorkPort work, AuditLedgerIngressPort center, Clock clock) {
        this.work = Objects.requireNonNull(work);
        this.center = Objects.requireNonNull(center);
        this.clock = Objects.requireNonNull(clock);
    }

    public CatalogAuditRelayResult runBatch() {
        List<CatalogAuditRelayClaim> claims = work.claimDue(BATCH_SIZE, clock.instant(), LEASE);
        int delivered = 0, retried = 0, failed = 0, fenced = 0;
        for (CatalogAuditRelayClaim claim : claims) {
            try {
                AuditIngressResult result = center.ingest(claim.source());
                if (result.outcome() == AuditIngressOutcome.APPENDED
                        || result.outcome() == AuditIngressOutcome.EXACT_DUPLICATE) {
                    if (work.confirm(claim.source().eventId(), claim.attempts(), clock.instant())) delivered++;
                    else fenced++;
                } else if (result.outcome() == AuditIngressOutcome.COLLISION || !result.retryable()) {
                    if (work.fail(claim.source().eventId(), claim.attempts(), clock.instant(), result.errorCode())) failed++;
                    else fenced++;
                } else if (work.retry(claim.source().eventId(), claim.attempts(), retryAt(claim), result.errorCode())) {
                    retried++;
                } else fenced++;
            } catch (RuntimeException unavailable) {
                if (work.retry(claim.source().eventId(), claim.attempts(), retryAt(claim), "AUDIT_LEDGER_UNAVAILABLE")) retried++;
                else fenced++;
            }
        }
        return new CatalogAuditRelayResult(claims.size(), delivered, retried, failed, fenced);
    }

    private Instant retryAt(CatalogAuditRelayClaim claim) {
        long seconds = Math.min(3600, 1L << Math.min(12, claim.attempts()));
        return clock.instant().plusSeconds(seconds);
    }
}
