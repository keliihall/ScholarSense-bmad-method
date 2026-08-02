package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessInvalidationPortsTest {
    @Test
    void useCasePortsRemainInterfacesAtTheApplicationBoundary() {
        assertTrue(AccessInvalidationProducerPort.class.isInterface());
        assertTrue(AccessInvalidationImpactResolverPort.class.isInterface());
        assertTrue(AccessInvalidationOutboxRelayPort.class.isInterface());
        assertTrue(AccessInvalidationConsumerPort.class.isInterface());
        assertTrue(AccessInvalidationWatermarkQueryPort.class.isInterface());
        assertTrue(AccessInvalidationFenceQueryPort.class.isInterface());
        assertTrue(AccessInvalidationReconciliationPort.class.isInterface());
        assertTrue(AccessInvalidationExpiryPort.class.isInterface());
    }

    @Test
    void legacyExpiryAdapterFailsClosedForBoundScheduling() {
        AccessInvalidationExpiryPort legacy =
                (jobId, lineageId, effectiveTo, traceId) -> null;

        var failure = assertThrows(
                IllegalStateException.class,
                () -> legacy.enqueue(
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000801"),
                        new AccessInvalidationLineageId(
                                "lin_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000802"),
                        1,
                        Instant.parse("2026-07-31T12:00:00Z"),
                        AccessInvalidationReason.RELATION_EXPIRED,
                        "0123456789abcdef0123456789abcdef",
                        Instant.parse("2026-07-31T11:00:00Z")));

        assertEquals(
                "ACCESS_INVALIDATION_BOUND_EXPIRY_ENQUEUE_REQUIRED",
                failure.getMessage());
    }

    @Test
    void legacyJobStoreFailsClosedForKeysetCheckpoint() {
        AccessInvalidationJobStorePort legacy =
                new AccessInvalidationJobStorePort() {
                    @Override
                    public List<AccessInvalidationJobLease> claim(
                            AccessInvalidationJobKind kind,
                            String leaseOwner,
                            Instant now,
                            int batchSize) {
                        return List.of();
                    }

                    @Override
                    public boolean isCurrentExpiry(
                            AccessInvalidationJobLease lease) {
                        return false;
                    }

                    @Override
                    public boolean isCurrentImpact(
                            AccessInvalidationJobLease lease) {
                        return false;
                    }

                    @Override
                    public void checkpoint(
                            AccessInvalidationJobLease lease,
                            long nextCursor,
                            boolean completed,
                            Instant updatedAt) {}

                    @Override
                    public void failed(
                            AccessInvalidationJobLease lease,
                            String reasonCode,
                            Instant failedAt,
                            Instant nextAttemptAt,
                            boolean quarantine) {}
                };

        var failure = assertThrows(
                IllegalStateException.class,
                () -> legacy.checkpoint(
                        null,
                        1,
                        "lin_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        false,
                        Instant.parse("2026-07-31T12:00:00Z")));

        assertEquals(
                "ACCESS_INVALIDATION_KEYSET_CHECKPOINT_REQUIRED",
                failure.getMessage());
    }
}
