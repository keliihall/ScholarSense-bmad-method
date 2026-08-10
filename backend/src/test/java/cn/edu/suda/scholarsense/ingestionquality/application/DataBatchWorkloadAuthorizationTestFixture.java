package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

/** Explicit test-only workload authority; no identity value is a production default. */
final class DataBatchWorkloadAuthorizationTestFixture {
    private static final String TEST_ENVIRONMENT =
            "test-fixture-deployment-input-required";
    private static final String TEST_POLICY_VERSION =
            "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0";
    private static final String TEST_POLICY_DIGEST = "sha256:" + "7".repeat(64);
    private static final long TEST_GENERATION = 1;

    private DataBatchWorkloadAuthorizationTestFixture() {}

    static DataBatchWorkloadAuthorizationGuard guard(String principalRef) {
        Objects.requireNonNull(principalRef);
        return new DataBatchWorkloadAuthorizationGuard(
                new DataBatchWorkloadAuthorizationPort() {
                    @Override
                    public DataBatchWorkloadAuthorizationResult capture(
                            DataBatchWorkloadAuthorizationRequest request) {
                        return DataBatchWorkloadAuthorizationResult.allow(
                                evidence(principalRef, request), TEST_GENERATION);
                    }

                    @Override
                    public DataBatchWorkloadAuthorizationResult revalidate(
                            DataBatchWorkloadAuthorizationEvidence captured,
                            DataBatchWorkloadAuthorizationRequest request) {
                        return DataBatchWorkloadAuthorizationResult.allow(
                                captured, TEST_GENERATION);
                    }
                });
    }

    private static DataBatchWorkloadAuthorizationEvidence evidence(
            String principalRef,
            DataBatchWorkloadAuthorizationRequest request) {
        return new DataBatchWorkloadAuthorizationEvidence(
                TEST_ENVIRONMENT,
                principalRef,
                "spiffe://test.invalid/scholarsense/ingestion-quality/quality-worker",
                request.audience(),
                request.capabilities(),
                TEST_GENERATION,
                TEST_POLICY_VERSION,
                TEST_POLICY_DIGEST,
                request.currentTime().minusSeconds(1),
                request.currentTime().plusSeconds(60),
                null);
    }
}
