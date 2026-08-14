package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QualityFuseWorkloadAuthorizationGuardTest {
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");

    @Test
    void capturesAndRevalidatesAuthoritativeMtlsEvidenceAndGeneration() {
        RecordingPort port = new RecordingPort();
        QualityFuseWorkloadAuthorizationGuard guard =
                new QualityFuseWorkloadAuthorizationGuard(port, "test");

        DataBatchWorkloadAuthorizationEvidence captured = guard.capture(NOW);
        guard.revalidate(captured, NOW.plusSeconds(1));

        assertEquals("workload:eligibility-consumer", captured.principalRef());
        assertEquals("spiffe://scholarsense/ingestion-quality/eligibility-consumer",
                captured.mtlsSanUriRef());
        assertEquals(QualityFuseWorkloadAuthorizationGuard.AUDIENCE,
                port.captureRequest.audience());
        assertEquals(Set.of(QualityFuseWorkloadAuthorizationGuard.CAPABILITY),
                port.captureRequest.capabilities());
        assertEquals(captured, port.revalidated);
        assertEquals(NOW.plusSeconds(1), port.revalidateRequest.currentTime());
    }

    @Test
    void denialAndProviderFailureFailClosed() {
        RecordingPort port = new RecordingPort();
        QualityFuseWorkloadAuthorizationGuard guard =
                new QualityFuseWorkloadAuthorizationGuard(port, "test");
        port.capture = DataBatchWorkloadAuthorizationResult.deny(8);
        assertEquals("INGESTION_QUALITY_FORBIDDEN", assertThrows(
                IngestionQualityApplicationException.class,
                () -> guard.capture(NOW)).code());

        port.capture = DataBatchWorkloadAuthorizationResult.dependencyUnavailable();
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", assertThrows(
                IngestionQualityApplicationException.class,
                () -> guard.capture(NOW)).code());
    }

    private static DataBatchWorkloadAuthorizationEvidence evidence() {
        return new DataBatchWorkloadAuthorizationEvidence(
                "test", "workload:eligibility-consumer",
                "spiffe://scholarsense/ingestion-quality/eligibility-consumer",
                QualityFuseWorkloadAuthorizationGuard.AUDIENCE,
                Set.of(QualityFuseWorkloadAuthorizationGuard.CAPABILITY), 7,
                QualityFuseWorkloadAuthorizationGuard.POLICY_VERSION,
                "sha256:" + "a".repeat(64), NOW.minusSeconds(1),
                NOW.plusSeconds(300), null);
    }

    private static final class RecordingPort implements DataBatchWorkloadAuthorizationPort {
        private DataBatchWorkloadAuthorizationResult capture =
                DataBatchWorkloadAuthorizationResult.allow(evidence(), 7);
        private DataBatchWorkloadAuthorizationRequest captureRequest;
        private DataBatchWorkloadAuthorizationRequest revalidateRequest;
        private DataBatchWorkloadAuthorizationEvidence revalidated;

        @Override
        public DataBatchWorkloadAuthorizationResult capture(
                DataBatchWorkloadAuthorizationRequest request) {
            captureRequest = request;
            return capture;
        }

        @Override
        public DataBatchWorkloadAuthorizationResult revalidate(
                DataBatchWorkloadAuthorizationEvidence captured,
                DataBatchWorkloadAuthorizationRequest request) {
            revalidated = captured;
            revalidateRequest = request;
            return DataBatchWorkloadAuthorizationResult.allow(captured, 7);
        }
    }
}
