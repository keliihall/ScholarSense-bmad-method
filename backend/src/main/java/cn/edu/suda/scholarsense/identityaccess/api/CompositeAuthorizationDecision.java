package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Public Java decision; HTTP adapters must map its reason to the approved safe envelope. */
public record CompositeAuthorizationDecision(
        CompositeAuthorizationOutcome outcome,
        String reasonCode,
        Set<String> applicableRolePackages,
        Set<String> scopeAnchorSummary,
        Map<String, FieldVisibility> fieldProjectionSummary,
        Set<String> clearConditionalFields,
        String policyVersion,
        long objectVersion,
        Instant evaluatedAt,
        CompositeAuthorizationDecisionToken decisionToken) {
    public CompositeAuthorizationDecision {
        Objects.requireNonNull(outcome, "outcome");
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_REASON_INVALID");
        }
        applicableRolePackages = Set.copyOf(applicableRolePackages);
        scopeAnchorSummary = Set.copyOf(scopeAnchorSummary);
        fieldProjectionSummary = Map.copyOf(fieldProjectionSummary);
        clearConditionalFields = Set.copyOf(clearConditionalFields);
        if (!"RFP-1.0.0".equals(policyVersion) || objectVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_DECISION_VERSION_INVALID");
        }
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(decisionToken, "decisionToken");
    }
}
