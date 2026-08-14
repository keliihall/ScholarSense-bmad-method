package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Captures and rechecks authoritative workload identity evidence for quality-fuse writes. */
public final class QualityFuseWorkloadAuthorizationGuard {
    public static final String AUDIENCE =
            "urn:scholarsense:ingestion-quality:quality-fuse";
    public static final String CAPABILITY = "quality-fuse.apply";
    public static final String POLICY_VERSION =
            "QUALITY-FUSE-WORKLOAD-AUTHORIZATION-1.0.0";

    private static final String FORBIDDEN = "INGESTION_QUALITY_FORBIDDEN";
    private static final String UNAVAILABLE = "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE";

    private final DataBatchWorkloadAuthorizationPort authorization;
    private final String environment;

    public QualityFuseWorkloadAuthorizationGuard(
            DataBatchWorkloadAuthorizationPort authorization,
            String environment) {
        this.authorization = Objects.requireNonNull(authorization);
        if (environment == null || environment.isBlank() || environment.length() > 128) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        this.environment = environment;
    }

    public DataBatchWorkloadAuthorizationEvidence capture(Instant currentTime) {
        DataBatchWorkloadAuthorizationRequest request = request(currentTime);
        DataBatchWorkloadAuthorizationResult result;
        try {
            result = Objects.requireNonNull(authorization.capture(request));
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IngestionQualityApplicationException(UNAVAILABLE, failure);
        }
        DataBatchWorkloadAuthorizationEvidence evidence = allowed(result);
        if (!matches(evidence, request)
                || result.currentAuthorizationGeneration()
                    != evidence.authorizationGeneration()
                || !active(evidence, currentTime)) {
            throw new IngestionQualityApplicationException(FORBIDDEN);
        }
        return evidence;
    }

    public void revalidate(
            DataBatchWorkloadAuthorizationEvidence captured,
            Instant currentTime) {
        Objects.requireNonNull(captured);
        DataBatchWorkloadAuthorizationRequest request = request(currentTime);
        DataBatchWorkloadAuthorizationResult result;
        try {
            result = Objects.requireNonNull(
                    authorization.revalidate(captured, request));
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IngestionQualityApplicationException(UNAVAILABLE, failure);
        }
        DataBatchWorkloadAuthorizationEvidence current = allowed(result);
        if (!current.equals(captured)
                || result.currentAuthorizationGeneration()
                    != captured.authorizationGeneration()) {
            throw new IngestionQualityApplicationException(UNAVAILABLE);
        }
        if (!matches(current, request) || !active(current, currentTime)) {
            throw new IngestionQualityApplicationException(FORBIDDEN);
        }
    }

    private DataBatchWorkloadAuthorizationRequest request(Instant currentTime) {
        return new DataBatchWorkloadAuthorizationRequest(
                DataBatchCommandType.EVALUATE, AUDIENCE, Set.of(CAPABILITY),
                Objects.requireNonNull(currentTime));
    }

    private static DataBatchWorkloadAuthorizationEvidence allowed(
            DataBatchWorkloadAuthorizationResult result) {
        if (result.status() == DataBatchWorkloadAuthorizationResult.Status.DENY) {
            throw new IngestionQualityApplicationException(FORBIDDEN);
        }
        if (result.status() != DataBatchWorkloadAuthorizationResult.Status.ALLOW) {
            throw new IngestionQualityApplicationException(UNAVAILABLE);
        }
        return Objects.requireNonNull(result.evidence());
    }

    private boolean matches(
            DataBatchWorkloadAuthorizationEvidence evidence,
            DataBatchWorkloadAuthorizationRequest request) {
        return evidence.environment().equals(environment)
                && evidence.audience().equals(request.audience())
                && evidence.capabilities().equals(request.capabilities())
                && evidence.policyVersion().equals(POLICY_VERSION)
                && evidence.mtlsSanUriRef().startsWith("spiffe://");
    }

    private static boolean active(
            DataBatchWorkloadAuthorizationEvidence evidence,
            Instant currentTime) {
        return !currentTime.isBefore(evidence.effectiveAt())
                && currentTime.isBefore(evidence.expiresAt())
                && (evidence.revokedAt() == null
                    || currentTime.isBefore(evidence.revokedAt()));
    }
}
