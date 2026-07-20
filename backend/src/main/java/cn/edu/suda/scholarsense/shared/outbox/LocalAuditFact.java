package cn.edu.suda.scholarsense.shared.outbox;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Framework-free, module-local immutable audit fact. Delivery state belongs to the outbox. */
public record LocalAuditFact(
        UUID auditId,
        String schemaVersion,
        String producerModule,
        ActorType actorType,
        String actorSearchToken,
        List<String> roleIds,
        Map<String, Object> authorizationContext,
        String action,
        String objectType,
        String objectSearchToken,
        String outcome,
        String reasonCode,
        String purpose,
        String projectionScope,
        Instant occurredAt,
        Instant recordedAt,
        TimeSourceProfile timeSourceProfile,
        String sourceIpSearchToken,
        String tokenizationProfileVersion,
        String keyVersion,
        String traceId,
        String aggregateType,
        String aggregateIdSearchToken,
        Long aggregateVersion,
        String idempotencyKeyDigest,
        Map<String, String> policyVersions,
        String retentionScheduleVersion) {
    public LocalAuditFact {
        requireUuidV7(auditId, "AUDIT_ID_INVALID");
        require(schemaVersion, "LOCAL-AUDIT-FACT-1.0.0", "AUDIT_SCHEMA_VERSION_INVALID");
        requireCode(producerModule, "[a-z][a-z0-9-]{2,63}", "AUDIT_PRODUCER_INVALID");
        Objects.requireNonNull(actorType, "actorType");
        requireSearchToken(actorSearchToken, "ast", actorType == ActorType.USER);
        roleIds = List.copyOf(roleIds);
        roleIds.forEach(value -> requireCode(
                value, "[A-Z][A-Z0-9_-]{1,31}", "AUDIT_ROLE_ID_INVALID"));
        authorizationContext = immutableAuthorizationFields(authorizationContext);
        requireCode(action, "[a-z][a-z0-9.-]{2,127}", "AUDIT_ACTION_INVALID");
        requireOptionalCode(objectType, "[a-z][a-z0-9.-]{1,63}", "AUDIT_OBJECT_TYPE_INVALID");
        requireSearchToken(objectSearchToken, "ost", false);
        if ((objectType == null) != (objectSearchToken == null)) {
            throw new IllegalArgumentException("AUDIT_OBJECT_CONTEXT_INCOMPLETE");
        }
        if (!List.of("accepted", "rejected").contains(outcome)) {
            throw new IllegalArgumentException("AUDIT_OUTCOME_INVALID");
        }
        requireCode(reasonCode, "[A-Z][A-Z0-9_]{2,127}", "AUDIT_REASON_INVALID");
        requireOptionalCode(purpose, "[A-Z][A-Z0-9_.-]{1,63}", "AUDIT_PURPOSE_INVALID");
        requireOptionalCode(
                projectionScope, "[A-Z][A-Z0-9_.-]{1,63}", "AUDIT_PROJECTION_SCOPE_INVALID");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (recordedAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("AUDIT_TIME_ORDER_INVALID");
        }
        Objects.requireNonNull(timeSourceProfile, "timeSourceProfile");
        requireSearchToken(sourceIpSearchToken, "ipt", false);
        require(tokenizationProfileVersion, "AUDIT-TOKENIZATION-1.0.0", "AUDIT_TOKENIZATION_PROFILE_INVALID");
        requireCode(keyVersion, "k[0-9]+", "AUDIT_KEY_VERSION_INVALID");
        requireCode(traceId, "[0-9a-f]{32}", "AUDIT_TRACE_ID_INVALID");
        requireOptionalCode(aggregateType, "[a-z][a-z0-9.-]{1,63}", "AUDIT_AGGREGATE_TYPE_INVALID");
        if (aggregateVersion != null && aggregateVersion < 1) {
            throw new IllegalArgumentException("AUDIT_AGGREGATE_VERSION_INVALID");
        }
        requireSearchToken(aggregateIdSearchToken, "agt", false);
        if ((aggregateType == null) != (aggregateIdSearchToken == null)
                || aggregateType == null && aggregateVersion != null) {
            throw new IllegalArgumentException("AUDIT_AGGREGATE_CONTEXT_INCOMPLETE");
        }
        if (idempotencyKeyDigest != null && !idempotencyKeyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("AUDIT_IDEMPOTENCY_DIGEST_INVALID");
        }
        policyVersions = Map.copyOf(policyVersions);
        policyVersions.forEach((key, value) -> {
            requireCode(key, "[a-z][A-Za-z0-9]{2,63}", "AUDIT_POLICY_KEY_INVALID");
            requireCode(value, "[A-Z][A-Z0-9.-]{2,63}", "AUDIT_POLICY_VERSION_INVALID");
        });
        if (actorType == ActorType.ANONYMOUS
                && (actorSearchToken != null || !roleIds.isEmpty()
                        || objectType != null || objectSearchToken != null
                        || aggregateType != null || aggregateIdSearchToken != null
                        || !"not-applicable".equals(authorizationContext.get("decision"))
                        || authorizationContext.get("policyVersion") != null
                        || !((List<?>) authorizationContext.get("scopeCodes")).isEmpty()
                        || !((List<?>) authorizationContext.get("grantSearchTokens")).isEmpty())) {
            throw new IllegalArgumentException("AUDIT_ANONYMOUS_CONTEXT_FORGED");
        }
        require(retentionScheduleVersion, "RS-1.0.0", "AUDIT_RETENTION_VERSION_INVALID");
    }

    private static void require(String value, String expected, String code) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(code);
        }
    }

    private static void requireCode(String value, String pattern, String code) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(code);
        }
    }

    private static void requireOptionalCode(String value, String pattern, String code) {
        if (value != null) {
            requireCode(value, pattern, code);
        }
    }

    private static void requireSearchToken(String value, String prefix, boolean required) {
        if (value == null && !required) {
            return;
        }
        if (value == null || !value.matches(prefix + "_v1_k[0-9]+_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_INVALID");
        }
    }

    static void requireUuidV7(UUID value, String code) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException(code);
        }
    }

    private static Map<String, Object> immutableAuthorizationFields(Map<String, Object> fields) {
        Objects.requireNonNull(fields, "authorizationContext");
        Set<String> required = Set.of(
                "decision", "policyVersion", "scopeCodes", "grantSearchTokens", "notApplicableReason");
        if (!fields.keySet().equals(required)
                || !(fields.get("decision") instanceof String decision)
                || !Set.of("allow", "deny", "not-applicable").contains(decision)
                || !(fields.get("scopeCodes") instanceof List<?>)
                || !(fields.get("grantSearchTokens") instanceof List<?>)) {
            throw new IllegalArgumentException("AUDIT_AUTHORIZATION_CONTEXT_INVALID");
        }
        for (Object scope : (List<?>) fields.get("scopeCodes")) {
            if (!(scope instanceof String value) || !value.matches("[A-Z][A-Z0-9_.-]{1,63}")) {
                throw new IllegalArgumentException("AUDIT_AUTHORIZATION_SCOPE_INVALID");
            }
        }
        for (Object grant : (List<?>) fields.get("grantSearchTokens")) {
            if (!(grant instanceof String value)
                    || !value.matches("gst_v1_k[0-9]+_[0-9a-f]{64}")) {
                throw new IllegalArgumentException("AUDIT_AUTHORIZATION_GRANT_INVALID");
            }
        }
        Object policyVersion = fields.get("policyVersion");
        Object notApplicableReason = fields.get("notApplicableReason");
        if (("not-applicable".equals(decision)) != (notApplicableReason != null)
                || (!"not-applicable".equals(decision) && !(policyVersion instanceof String))
                || "not-applicable".equals(decision)
                    && (policyVersion != null
                        || !((List<?>) fields.get("scopeCodes")).isEmpty()
                        || !((List<?>) fields.get("grantSearchTokens")).isEmpty())) {
            throw new IllegalArgumentException("AUDIT_AUTHORIZATION_CONTEXT_INCOMPLETE");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        fields.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "authorizationContext key"),
                value instanceof List<?> list ? List.copyOf(list) : value));
        return Collections.unmodifiableMap(copy);
    }
}
