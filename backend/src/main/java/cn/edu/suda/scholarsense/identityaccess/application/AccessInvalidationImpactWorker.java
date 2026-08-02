package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationCause;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobKind;
import java.time.Duration;
import java.time.Instant;

public final class AccessInvalidationImpactWorker {
    private static final int MAX_ATTEMPTS = 8;
    private final AccessInvalidationJobStorePort jobs;
    private final AccessInvalidationStorePort store;
    private final AccessInvalidationImpactResolverPort resolver;
    private final AccessInvalidationEventCodecPort events;
    private final AccessInvalidationIdPort identifiers;
    private final IdentitySyncTransactionPort transactions;
    private final AccessInvalidationAuditPort audit;

    public AccessInvalidationImpactWorker(
            AccessInvalidationJobStorePort jobs,
            AccessInvalidationStorePort store,
            AccessInvalidationImpactResolverPort resolver,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            IdentitySyncTransactionPort transactions) {
        this(
                jobs,
                store,
                resolver,
                events,
                identifiers,
                transactions,
                ignored -> {});
    }

    public AccessInvalidationImpactWorker(
            AccessInvalidationJobStorePort jobs,
            AccessInvalidationStorePort store,
            AccessInvalidationImpactResolverPort resolver,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            IdentitySyncTransactionPort transactions,
            AccessInvalidationAuditPort audit) {
        this.jobs = jobs;
        this.store = store;
        this.resolver = resolver;
        this.events = events;
        this.identifiers = identifiers;
        this.transactions = transactions;
        this.audit = audit;
    }

    public int run(
            String leaseOwner,
            int jobBatchSize,
            int fanOutBatchSize,
            Instant now) {
        var claims = jobs.claim(
                AccessInvalidationJobKind.IMPACT,
                leaseOwner,
                now,
                jobBatchSize);
        for (var claim : claims) {
            try {
                transactions.execute(() -> {
                    if (!jobs.isCurrentImpact(claim)) {
                        jobs.checkpoint(
                                claim,
                                claim.job().cursor(),
                                claim.cursorKey(),
                                true,
                                now);
                        return null;
                    }
                    var causeFact = store.find(claim.causeEventId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "ACCESS_INVALIDATION_CAUSE_FACT_MISSING"));
                    var cause = new AccessInvalidationCause(
                            causeFact.eventId(),
                            causeFact.lineageId(),
                            causeFact.reasonCode(),
                            causeFact.sourceVector(),
                            causeFact.effectiveAt(),
                            causeFact.traceId());
                    var facts = resolver.resolve(
                            cause,
                            fanOutBatchSize,
                            claim.job().cursor(),
                            claim.cursorKey());
                    for (var fact : facts) {
                        long expectedVersion =
                                fact.aggregateVersion() - 1;
                        store.append(new AccessInvalidationAppendCommand(
                                fact,
                                identifiers.next(now),
                                expectedVersion,
                                store.fencingToken(
                                        fact.lineageId().value()),
                                events.encode(fact),
                                now));
                        audit.factAppended(fact);
                    }
                    String nextCursorKey = facts.isEmpty()
                            ? claim.cursorKey()
                            : facts.getLast().lineageId().value();
                    jobs.checkpoint(
                            claim,
                            claim.job().cursor() + facts.size(),
                            nextCursorKey,
                            facts.size() < fanOutBatchSize,
                            now);
                    return null;
                });
            } catch (RuntimeException failure) {
                failSafely(claim, now);
            }
        }
        return claims.size();
    }

    private void failSafely(
            AccessInvalidationJobLease claim, Instant now) {
        boolean quarantine = claim.attemptNo() >= MAX_ATTEMPTS;
        try {
            jobs.failed(
                    claim,
                    "ACCESS_INVALIDATION_IMPACT_RESOLUTION_FAILED",
                    now,
                    now.plus(backoff(claim.attemptNo())),
                    quarantine);
        } catch (RuntimeException staleLease) {
            // A reclaimed lease is already owned by another worker. Its
            // failure update must not prevent this batch's other claims.
        }
    }

    private static Duration backoff(long attempt) {
        return Duration.ofSeconds(
                Math.min(900, 1L << Math.min(10, attempt)));
    }
}
