package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Identity-owned authorization evidence mapped into the common audit fact contract. */
public record IdentityAuditAuthorizationContext(
        String decision,
        String policyVersion,
        List<String> scopeCodes,
        List<String> grantSearchTokens,
        String notApplicableReason) {
    public IdentityAuditAuthorizationContext {
        if (!List.of("allow", "deny", "not-applicable").contains(decision)) {
            throw new IllegalArgumentException("AUDIT_AUTHORIZATION_DECISION_INVALID");
        }
        scopeCodes = List.copyOf(scopeCodes);
        grantSearchTokens = List.copyOf(grantSearchTokens);
        if (("not-applicable".equals(decision)) != (notApplicableReason != null)) {
            throw new IllegalArgumentException("AUDIT_AUTHORIZATION_CONTEXT_INCOMPLETE");
        }
        if (!"not-applicable".equals(decision) && policyVersion == null) {
            throw new IllegalArgumentException("AUDIT_AUTHORIZATION_CONTEXT_INCOMPLETE");
        }
        if ("not-applicable".equals(decision)
                && (policyVersion != null || !scopeCodes.isEmpty() || !grantSearchTokens.isEmpty())) {
            throw new IllegalArgumentException("AUDIT_AUTHORIZATION_CONTEXT_INCOMPLETE");
        }
        IdentityAuditVocabulary.validateAuthorizationValues(
                policyVersion, scopeCodes, notApplicableReason);
    }

    public Map<String, Object> asFactFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("decision", decision);
        fields.put("policyVersion", policyVersion);
        fields.put("scopeCodes", scopeCodes);
        fields.put("grantSearchTokens", grantSearchTokens);
        fields.put("notApplicableReason", notApplicableReason);
        return fields;
    }
}
