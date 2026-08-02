package cn.edu.suda.scholarsense.identityaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessInvalidationDomainTest {
    private static final Instant EFFECTIVE_AT =
            Instant.parse("2026-07-31T10:00:00Z");
    private static final UUID ROOT_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000001");
    private static final UUID SUCCESSOR_ID =
            UUID.fromString("019c0000-0000-7000-8000-000000000002");
    private static final AccessInvalidationLineageId LINEAGE_ID =
            new AccessInvalidationLineageId(
                    "lin_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    @Test
    void factRequiresUuidV7DirectLineageAndMatchingContinuousVersions() {
        var root = fact(
                ROOT_ID,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                null,
                1);
        var successor = fact(
                SUCCESSOR_ID,
                AccessInvalidationChangeKind.REVOKED,
                AccessInvalidationReason.DIRECT_RESPONSIBILITY_CHANGE,
                ROOT_ID,
                2);

        assertEquals(1, root.aggregateVersion());
        assertTrue(new AccessInvalidationLineageHead(
                LINEAGE_ID, ROOT_ID, 1).accepts(successor));
        assertThrows(IllegalArgumentException.class, () -> fact(
                SUCCESSOR_ID,
                AccessInvalidationChangeKind.REVOKED,
                AccessInvalidationReason.DIRECT_RESPONSIBILITY_CHANGE,
                null,
                2));
        assertThrows(IllegalArgumentException.class, () ->
                new AccessInvalidationFact(
                        SUCCESSOR_ID,
                        "f".repeat(32),
                        AccessInvalidationChangeKind.REVOKED,
                        AccessInvalidationReason.DIRECT_RESPONSIBILITY_CHANGE,
                        LINEAGE_ID,
                        ROOT_ID,
                        null,
                        AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                        LINEAGE_ID.value(),
                        2,
                        3,
                        EFFECTIVE_AT,
                        sourceVector(),
                        subjectSnapshot(),
                        invalidatedSnapshot(),
                        retention(),
                        "a".repeat(64)));
    }

    @Test
    void recoveryMustAppendAControlledRevalidatedFact() {
        assertThrows(IllegalArgumentException.class, () -> fact(
                SUCCESSOR_ID,
                AccessInvalidationChangeKind.REVALIDATED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                ROOT_ID,
                2));
        assertEquals(
                AccessInvalidationAuthorizationState.REVALIDATED,
                new AccessInvalidationFact(
                        SUCCESSOR_ID,
                        "f".repeat(32),
                        AccessInvalidationChangeKind.REVALIDATED,
                        AccessInvalidationReason.RECONCILIATION_RECOVERED,
                        LINEAGE_ID,
                        ROOT_ID,
                        null,
                        AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                        LINEAGE_ID.value(),
                        2,
                        2,
                        EFFECTIVE_AT,
                        sourceVector(),
                        subjectSnapshot(),
                        revalidatedSnapshot(),
                        retention(),
                        "a".repeat(64))
                        .authorizationSnapshot()
                        .currentState());
    }

    @Test
    void watermarkClassifiesOnlyTheCurrentRoute() {
        var route = new AccessInvalidationConsumerRoute(
                "authorization-current-scope",
                "identity-access",
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE_ID);
        var watermark = new AccessInvalidationConsumerWatermark(
                route, 1, ROOT_ID, "a".repeat(64), EFFECTIVE_AT);

        assertEquals(
                AccessInvalidationDeliveryDecision.APPLIED,
                watermark.classify(
                        2, SUCCESSOR_ID, ROOT_ID, "b".repeat(64)));
        assertEquals(
                AccessInvalidationDeliveryDecision.CONFLICT,
                watermark.classify(
                        2, SUCCESSOR_ID, SUCCESSOR_ID, "b".repeat(64)));
        assertEquals(
                AccessInvalidationDeliveryDecision.DUPLICATE,
                watermark.classify(
                        1, ROOT_ID, null, "a".repeat(64)));
        assertEquals(
                AccessInvalidationDeliveryDecision.CONFLICT,
                watermark.classify(
                        1, ROOT_ID, null, "b".repeat(64)));
        assertEquals(
                AccessInvalidationDeliveryDecision.OLD_IGNORED,
                watermark.classify(
                        0,
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000099"),
                        null,
                        "c".repeat(64)));
        assertEquals(
                AccessInvalidationDeliveryDecision.GAP_BACKFILL_REQUIRED,
                watermark.classify(
                        3,
                        SUCCESSOR_ID,
                        SUCCESSOR_ID,
                        "b".repeat(64)));
    }

    @Test
    void dependencyPartitionsAcceptRuntimeAndSchemaFormsExactly() {
        assertEquals(
                "identity-authority|7",
                new AccessInvalidationDependencyWatermark(
                                "identity-authority", "7", 1)
                        .route());
        assertEquals(
                "responsibility-authority|7-zone",
                new AccessInvalidationDependencyWatermark(
                                "responsibility-authority", "7-zone", 1)
                        .route());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessInvalidationDependencyWatermark(
                        "identity-authority", "a--b", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessInvalidationDependencyWatermark(
                        "identity-authority", "a".repeat(65), 1));
    }

    @Test
    void plannedConsumerCannotClaimRuntimeEvidenceOrEnterCompletionDenominator() {
        var current = AccessInvalidationConsumerRegistration.activeRequired(
                "authorization-current-scope",
                "identity-access",
                "1.6c",
                "1.6c",
                EFFECTIVE_AT,
                0);
        var future = AccessInvalidationConsumerRegistration.planned(
                "reporting-export",
                "reporting",
                "3.14c");

        var status = AccessInvalidationPropagationStatus.assess(
                LINEAGE_ID,
                2,
                List.of(current, future),
                Set.of("authorization-current-scope"),
                true);

        assertTrue(status.complete());
        assertEquals(
                Set.of("authorization-current-scope"),
                status.requiredConsumerIds());
        assertEquals(Set.of("reporting-export"), status.visiblePlannedConsumerIds());
        assertThrows(IllegalArgumentException.class, () ->
                new AccessInvalidationConsumerRegistration(
                        "reporting-export",
                        "reporting",
                        "3.14c",
                        "owner-story-activation",
                        AccessInvalidationConsumerLifecycle.PLANNED_NOT_INSTALLED,
                        true,
                        EFFECTIVE_AT,
                        0L,
                        AccessInvalidationRuntimeEvidenceClaim.CURRENT_RUNTIME));
    }

    @Test
    void jobTransitionsRequireMatchingFenceAndNeverMoveBackwards() {
        var job = AccessInvalidationJob.pending(
                UUID.fromString("019c0000-0000-7000-8000-000000000020"),
                AccessInvalidationJobKind.EXPIRY,
                LINEAGE_ID,
                EFFECTIVE_AT,
                "f".repeat(32));
        var running = job.claim("worker-a", 7, EFFECTIVE_AT.plusSeconds(1));

        assertEquals(AccessInvalidationJobState.RUNNING, running.state());
        assertThrows(IllegalArgumentException.class, () ->
                running.complete(6, EFFECTIVE_AT.plusSeconds(2)));
        var complete = running.complete(7, EFFECTIVE_AT.plusSeconds(2));
        assertEquals(AccessInvalidationJobState.COMPLETED, complete.state());
        assertFalse(complete.canClaim(EFFECTIVE_AT.plusSeconds(3)));
    }

    private static AccessInvalidationFact fact(
            UUID eventId,
            AccessInvalidationChangeKind changeKind,
            AccessInvalidationReason reason,
            UUID supersedesId,
            long version) {
        return new AccessInvalidationFact(
                eventId,
                "f".repeat(32),
                changeKind,
                reason,
                LINEAGE_ID,
                supersedesId,
                null,
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                LINEAGE_ID.value(),
                version,
                version,
                EFFECTIVE_AT,
                sourceVector(),
                subjectSnapshot(),
                changeKind == AccessInvalidationChangeKind.REVALIDATED
                        ? revalidatedSnapshot()
                        : invalidatedSnapshot(),
                retention(),
                "a".repeat(64));
    }

    private static AccessInvalidationSourceVector sourceVector() {
        return new AccessInvalidationSourceVector(
                "SRC-P0-RESPONSIBILITY-001",
                9,
                9,
                List.of(
                        new AccessInvalidationDependencyWatermark(
                                "identity-authority", "sandbox-0", 42),
                        new AccessInvalidationDependencyWatermark(
                                "responsibility-authority", "sandbox-0", 9)));
    }

    private static AccessInvalidationSubjectSnapshot subjectSnapshot() {
        return new AccessInvalidationSubjectSnapshot(
                "subtok_" + "a".repeat(40),
                "scptok_" + "b".repeat(40),
                "c".repeat(64),
                "ACCESS-INVALIDATION-TOKENIZATION-1.0.0");
    }

    private static AccessInvalidationAuthorizationSnapshot invalidatedSnapshot() {
        return new AccessInvalidationAuthorizationSnapshot(
                AccessInvalidationAuthorizationState.INVALIDATED,
                true,
                true,
                true,
                false,
                "RFP-1.0.0");
    }

    private static AccessInvalidationAuthorizationSnapshot revalidatedSnapshot() {
        return new AccessInvalidationAuthorizationSnapshot(
                AccessInvalidationAuthorizationState.REVALIDATED,
                true,
                true,
                true,
                true,
                "RFP-1.0.0");
    }

    private static AccessInvalidationRetention retention() {
        return new AccessInvalidationRetention(
                "restricted",
                "RS-1.0.0",
                EFFECTIVE_AT.plusSeconds(86400),
                false);
    }
}
