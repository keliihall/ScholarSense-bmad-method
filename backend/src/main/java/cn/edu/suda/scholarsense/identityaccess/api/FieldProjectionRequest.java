package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.List;
import java.util.Objects;

/** Internal transport-neutral request composed from current authorization and owner evidence. */
public record FieldProjectionRequest(
        CompositeAuthorizationRequest authorizationRequest,
        FieldProjectionObjectClass objectClass,
        FieldProjectionObjectEvidence objectEvidence,
        List<FieldProjectionValueReference> valueReferences) {
    public FieldProjectionRequest {
        Objects.requireNonNull(authorizationRequest, "authorizationRequest");
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(objectEvidence, "objectEvidence");
        valueReferences = List.copyOf(valueReferences);
        if (valueReferences.isEmpty()) {
            throw new IllegalArgumentException("FIELD_PROJECTION_VALUES_REQUIRED");
        }
        validateAuthorizationBinding(authorizationRequest, objectClass, objectEvidence.purpose());
    }

    private static void validateAuthorizationBinding(
            CompositeAuthorizationRequest authorization,
            FieldProjectionObjectClass projectionObject,
            String purpose) {
        boolean objectMatches = switch (projectionObject) {
            case AUDIT_SEARCH_RECORD ->
                    "AGGREGATE_REPORT".equals(authorization.objectClass())
                            || "TELEMETRY".equals(authorization.objectClass());
            case SUBJECT_MAPPING_EXCEPTION ->
                    "SUBJECT_MAPPING_EXCEPTION".equals(authorization.objectClass());
            case TRANSFER_ORDER -> "TRANSFER_ORDER".equals(authorization.objectClass());
        };
        if (!objectMatches) {
            throw new IllegalArgumentException("FIELD_PROJECTION_AUTHORIZATION_OBJECT_MISMATCH");
        }

        boolean purposeMatches = switch (projectionObject) {
            case AUDIT_SEARCH_RECORD ->
                    authorization.actionId().equals(purpose)
                            && ("AGGREGATE_REPORT".equals(authorization.objectClass())
                                    && "audit.search-business-metadata".equals(purpose)
                                || "TELEMETRY".equals(authorization.objectClass())
                                    && "audit.search-technical-metadata".equals(purpose));
            case SUBJECT_MAPPING_EXCEPTION ->
                    "data-quality.repair".equals(authorization.actionId())
                            && "subject-mapping-repair".equals(purpose);
            case TRANSFER_ORDER -> authorization.actionId().equals(purpose);
        };
        if (!purposeMatches) {
            throw new IllegalArgumentException("FIELD_PROJECTION_AUTHORIZATION_PURPOSE_MISMATCH");
        }
    }
}
