package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import java.util.Map;
import java.util.Set;

/** Rejects policy drift before any search or evidence fields can leave the application boundary. */
final class AuditSearchDecisionValidator {
    private static final Map<String, AuditFieldVisibility> BUSINESS = Map.of(
            "B", AuditFieldVisibility.CLEAR,
            "I", AuditFieldVisibility.MASKED,
            "C", AuditFieldVisibility.HIDDEN,
            "S", AuditFieldVisibility.HIDDEN,
            "E", AuditFieldVisibility.HIDDEN,
            "N", AuditFieldVisibility.HIDDEN,
            "G", AuditFieldVisibility.CLEAR,
            "T", AuditFieldVisibility.MASKED);
    private static final Map<String, AuditFieldVisibility> TECHNICAL = Map.of(
            "B", AuditFieldVisibility.CLEAR,
            "I", AuditFieldVisibility.HIDDEN,
            "C", AuditFieldVisibility.HIDDEN,
            "S", AuditFieldVisibility.HIDDEN,
            "E", AuditFieldVisibility.HIDDEN,
            "N", AuditFieldVisibility.HIDDEN,
            "G", AuditFieldVisibility.MASKED,
            "T", AuditFieldVisibility.CLEAR);

    private AuditSearchDecisionValidator() {}

    static boolean isValid(AuditSearchView view, AuthorizedAuditSearchDecision decision) {
        if (view == null || decision == null || !decision.allowed()) {
            return false;
        }
        Map<String, AuditFieldVisibility> expected = view == AuditSearchView.BUSINESS
                ? BUSINESS : TECHNICAL;
        String expectedAction = view == AuditSearchView.BUSINESS
                ? "audit.search-business-metadata" : "audit.search-technical-metadata";
        return "RFP-1.0.0".equals(decision.rfpVersion())
                && expectedAction.equals(decision.action())
                && decision.scopes().equals(Set.of("audit-domain"))
                && decision.fieldProjection().keySet().equals(Set.copyOf(expected.keySet()))
                && decision.fieldProjection().equals(expected)
                && decision.reasonCode() == null;
    }
}
