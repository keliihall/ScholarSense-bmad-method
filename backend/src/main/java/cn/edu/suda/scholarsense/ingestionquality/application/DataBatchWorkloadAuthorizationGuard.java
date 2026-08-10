package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Enforces the frozen workload audience/capability contract and final-commit revalidation. */
public final class DataBatchWorkloadAuthorizationGuard {
    public static final String COMMAND_AUDIENCE =
            "urn:scholarsense:ingestion-quality:data-batch-commands";
    public static final String POLICY_VERSION =
            "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0";

    private static final String FORBIDDEN = "INGESTION_QUALITY_FORBIDDEN";
    private static final String DEPENDENCY_UNAVAILABLE =
            "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE";
    private static final Map<DataBatchCommandType, String> CAPABILITIES = Map.of(
            DataBatchCommandType.RECEIVE, "data-batch.receive",
            DataBatchCommandType.SEAL, "data-batch.seal",
            DataBatchCommandType.EVALUATE, "data-batch.evaluate",
            DataBatchCommandType.PUBLISH, "data-batch.publish");

    private final DataBatchWorkloadAuthorizationPort authorization;

    public DataBatchWorkloadAuthorizationGuard(
            DataBatchWorkloadAuthorizationPort authorization) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public DataBatchWorkloadAuthorizationEvidence capture(
            DataBatchCommandType commandType,
            String claimedPrincipalRef,
            Instant currentTime) {
        DataBatchWorkloadAuthorizationRequest request = request(commandType, currentTime);
        DataBatchWorkloadAuthorizationResult result;
        try {
            result = Objects.requireNonNull(authorization.capture(request));
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    DEPENDENCY_UNAVAILABLE, unavailable);
        }
        DataBatchWorkloadAuthorizationEvidence evidence = requireAllowed(result);
        if (!evidence.principalRef().equals(claimedPrincipalRef)
                || !matchesRequest(evidence, request)
                || result.currentAuthorizationGeneration()
                != evidence.authorizationGeneration()
                || !isActive(evidence, currentTime)) {
            throw new IngestionQualityApplicationException(FORBIDDEN);
        }
        return evidence;
    }

    public void revalidate(
            DataBatchWorkloadAuthorizationEvidence captured,
            DataBatchCommandType commandType,
            Instant currentTime) {
        Objects.requireNonNull(captured, "captured");
        DataBatchWorkloadAuthorizationRequest request = request(commandType, currentTime);
        DataBatchWorkloadAuthorizationResult result;
        try {
            result = Objects.requireNonNull(
                    authorization.revalidate(captured, request));
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    DEPENDENCY_UNAVAILABLE, unavailable);
        }
        DataBatchWorkloadAuthorizationEvidence current = requireAllowed(result);
        if (!current.equals(captured)
                || result.currentAuthorizationGeneration()
                != captured.authorizationGeneration()) {
            throw new IngestionQualityApplicationException(DEPENDENCY_UNAVAILABLE);
        }
        if (!matchesRequest(current, request) || !isActive(current, currentTime)) {
            throw new IngestionQualityApplicationException(FORBIDDEN);
        }
    }

    private static DataBatchWorkloadAuthorizationRequest request(
            DataBatchCommandType commandType, Instant currentTime) {
        Objects.requireNonNull(commandType, "commandType");
        String capability = Objects.requireNonNull(CAPABILITIES.get(commandType));
        return new DataBatchWorkloadAuthorizationRequest(
                commandType, COMMAND_AUDIENCE, Set.of(capability), currentTime);
    }

    private static DataBatchWorkloadAuthorizationEvidence requireAllowed(
            DataBatchWorkloadAuthorizationResult result) {
        if (result.status() == DataBatchWorkloadAuthorizationResult.Status.DENY) {
            throw new IngestionQualityApplicationException(FORBIDDEN);
        }
        if (result.status()
                != DataBatchWorkloadAuthorizationResult.Status.ALLOW) {
            throw new IngestionQualityApplicationException(DEPENDENCY_UNAVAILABLE);
        }
        return Objects.requireNonNull(result.evidence());
    }

    private static boolean matchesRequest(
            DataBatchWorkloadAuthorizationEvidence evidence,
            DataBatchWorkloadAuthorizationRequest request) {
        return evidence.audience().equals(request.audience())
                && evidence.capabilities().equals(request.capabilities())
                && evidence.policyVersion().equals(POLICY_VERSION);
    }

    private static boolean isActive(
            DataBatchWorkloadAuthorizationEvidence evidence, Instant currentTime) {
        return !currentTime.isBefore(evidence.effectiveAt())
                && currentTime.isBefore(evidence.expiresAt())
                && (evidence.revokedAt() == null
                || currentTime.isBefore(evidence.revokedAt()));
    }
}
