package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import java.time.Duration;
import java.time.Instant;

public final class AccessInvalidationExpiryWorker {
    private static final int MAX_ATTEMPTS = 8;
    private final AccessInvalidationJobStorePort jobs;
    private final AccessInvalidationStorePort store;
    private final AccessInvalidationEventCodecPort events;
    private final AccessInvalidationIdPort identifiers;
    private final IdentitySyncTransactionPort transactions;
    private final AccessInvalidationAuditPort audit;

    public AccessInvalidationExpiryWorker(
            AccessInvalidationJobStorePort jobs,
            AccessInvalidationStorePort store,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            IdentitySyncTransactionPort transactions) {
        this(
                jobs,
                store,
                events,
                identifiers,
                transactions,
                ignored -> {});
    }

    public AccessInvalidationExpiryWorker(
            AccessInvalidationJobStorePort jobs,
            AccessInvalidationStorePort store,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            IdentitySyncTransactionPort transactions,
            AccessInvalidationAuditPort audit) {
        this.jobs = jobs;
        this.store = store;
        this.events = events;
        this.identifiers = identifiers;
        this.transactions = transactions;
        this.audit = audit;
    }

    public int run(
            String leaseOwner, int jobBatchSize, Instant now) {
        var claims = jobs.claim(
                AccessInvalidationJobKind.EXPIRY,
                leaseOwner,
                now,
                jobBatchSize);
        for (var claim : claims) {
            try {
                transactions.execute(() -> {
                    if (!jobs.isCurrentExpiry(claim)) {
                        jobs.checkpoint(
                                claim,
                                claim.job().cursor(),
                                true,
                                now);
                        return null;
                    }
                    AccessInvalidationFact previous = store.latest(
                                    claim.job().lineageId().value())
                            .orElseThrow(() -> new IllegalStateException(
                                    "ACCESS_INVALIDATION_EXPIRY_HEAD_MISSING"));
                    if (!previous.eventId().equals(
                                    claim.causeEventId())
                            || previous.aggregateVersion()
                                    != claim.job().cursor()) {
                        jobs.checkpoint(
                                claim,
                                claim.job().cursor(),
                                true,
                                now);
                        return null;
                    }
                    if (previous.changeKind()
                                    == AccessInvalidationChangeKind.EXPIRED
                            && !previous.effectiveAt()
                                    .isBefore(claim.job().dueAt())) {
                        jobs.checkpoint(
                                claim,
                                claim.job().cursor(),
                                true,
                                now);
                        return null;
                    }
                    AccessInvalidationFact expired = new AccessInvalidationFact(
                            identifiers.next(now),
                            previous.traceId(),
                            AccessInvalidationChangeKind.EXPIRED,
                            AccessInvalidationReason.RELATION_EXPIRED,
                            previous.lineageId(),
                            previous.eventId(),
                            null,
                            previous.aggregateType(),
                            previous.aggregateId(),
                            previous.aggregateVersion() + 1,
                            previous.invalidationVersion() + 1,
                            claim.job().dueAt(),
                            previous.sourceVector(),
                            previous.subjectSnapshot(),
                            new AccessInvalidationAuthorizationSnapshot(
                                    AccessInvalidationAuthorizationState
                                            .INVALIDATED,
                                    previous.authorizationSnapshot()
                                            .accountActive(),
                                    previous.authorizationSnapshot()
                                            .r1EmploymentValid(),
                                    previous.authorizationSnapshot()
                                            .collegeActive(),
                                    false,
                                    "RFP-1.0.0"),
                            new AccessInvalidationRetention(
                                    "restricted",
                                    "RS-1.0.0",
                                    claim.job().dueAt()
                                            .plus(Duration.ofDays(2190)),
                                    previous.retention().legalHold()),
                            previous.payloadDigest());
                    store.append(new AccessInvalidationAppendCommand(
                            expired,
                            identifiers.next(now),
                            previous.aggregateVersion(),
                            store.fencingToken(
                                    expired.lineageId().value()),
                            events.encode(expired),
                            now));
                    audit.factAppended(expired);
                    jobs.checkpoint(
                            claim,
                            claim.job().cursor(),
                            true,
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
                    "ACCESS_INVALIDATION_EXPIRY_FAILED",
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
