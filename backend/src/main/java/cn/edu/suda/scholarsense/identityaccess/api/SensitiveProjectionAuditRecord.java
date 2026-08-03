package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

/** Metadata-only projection audit. Field names and values are deliberately absent. */
public record SensitiveProjectionAuditRecord(
        String actorPseudonym,
        Set<String> rolePackages,
        String actionId,
        String objectTokenDigest,
        String objectClass,
        String purposeDigest,
        String policyVersion,
        String schemaVersion,
        long objectVersion,
        String keyStateVersion,
        int clearCount,
        int maskedCount,
        int hiddenCount,
        Map<String, FieldVisibility> fieldClassSummary,
        String result,
        Instant trustedTime,
        String traceId) {
    public SensitiveProjectionAuditRecord {
        require(actorPseudonym, "[A-Za-z0-9][A-Za-z0-9._-]{7,127}", "FIELD_PROJECTION_AUDIT_ACTOR_INVALID");
        rolePackages = Set.copyOf(rolePackages);
        require(actionId, "[a-z][a-z0-9.-]{2,127}", "FIELD_PROJECTION_AUDIT_ACTION_INVALID");
        require(objectTokenDigest, "[0-9a-f]{64}", "FIELD_PROJECTION_AUDIT_OBJECT_TOKEN_INVALID");
        require(objectClass, "[A-Za-z][A-Za-z0-9]{2,63}", "FIELD_PROJECTION_AUDIT_OBJECT_INVALID");
        require(purposeDigest, "[0-9a-f]{64}", "FIELD_PROJECTION_AUDIT_PURPOSE_INVALID");
        require(policyVersion, "[A-Z][A-Z0-9.-]{2,63}", "FIELD_PROJECTION_AUDIT_POLICY_INVALID");
        require(schemaVersion, "[A-Z][A-Z0-9.-]{2,127}", "FIELD_PROJECTION_AUDIT_SCHEMA_INVALID");
        if (objectVersion < 1 || clearCount < 0 || maskedCount < 0 || hiddenCount < 0) {
            throw new IllegalArgumentException("FIELD_PROJECTION_AUDIT_COUNT_INVALID");
        }
        fieldClassSummary = Map.copyOf(fieldClassSummary);
        require(keyStateVersion, "[a-z0-9][a-z0-9._-]{2,63}", "FIELD_PROJECTION_AUDIT_KEY_STATE_INVALID");
        require(result, "[A-Z][A-Z0-9_]{2,63}", "FIELD_PROJECTION_AUDIT_RESULT_INVALID");
        Objects.requireNonNull(trustedTime, "trustedTime");
        require(traceId, "[0-9a-f]{32}", "FIELD_PROJECTION_AUDIT_TRACE_INVALID");
    }

    private static void require(String value, String pattern, String code) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(code);
        }
    }
}
