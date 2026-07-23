package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchCapabilityManifest;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchView;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import java.util.Map;
import java.util.Set;

/** RFP-1.0.0 anonymous conformance oracle. This adapter is deliberately not a Spring bean. */
public final class RfpAuditSearchConformanceAdapter implements AuditSearchAuthorizationPort {
    private static final Map<String, FieldVisibility> BUSINESS = Map.of(
            "B", FieldVisibility.CLEAR,
            "I", FieldVisibility.MASKED,
            "C", FieldVisibility.HIDDEN,
            "S", FieldVisibility.HIDDEN,
            "E", FieldVisibility.HIDDEN,
            "N", FieldVisibility.HIDDEN,
            "G", FieldVisibility.CLEAR,
            "T", FieldVisibility.MASKED);
    private static final Map<String, FieldVisibility> TECHNICAL = Map.of(
            "B", FieldVisibility.CLEAR,
            "I", FieldVisibility.HIDDEN,
            "C", FieldVisibility.HIDDEN,
            "S", FieldVisibility.HIDDEN,
            "E", FieldVisibility.HIDDEN,
            "N", FieldVisibility.HIDDEN,
            "G", FieldVisibility.MASKED,
            "T", FieldVisibility.CLEAR);

    private RfpAuditSearchConformanceAdapter() {}

    public static RfpAuditSearchConformanceAdapter approvedFixture() {
        return new RfpAuditSearchConformanceAdapter();
    }

    @Override
    public AuditSearchAuthorizationDecision authorize(AuditSearchAuthorizationRequest request) {
        String fixtureRole = request.sessionPseudonym();
        if (fixtureRole.contains("+") || fixtureRole.startsWith("revoked-")
                || fixtureRole.equals("policy-unavailable")) {
            return AuditSearchAuthorizationDecision.denied("AUDIT_SEARCH_FORBIDDEN");
        }
        if (fixtureRole.equals("r3") && request.requestedView() == AuditSearchView.BUSINESS) {
            return allowed("audit.search-business-metadata", request.scope(), BUSINESS);
        }
        if (fixtureRole.equals("r7") && request.requestedView() == AuditSearchView.TECHNICAL) {
            return allowed("audit.search-technical-metadata", request.scope(), TECHNICAL);
        }
        return AuditSearchAuthorizationDecision.denied("AUDIT_SEARCH_FORBIDDEN");
    }

    @Override
    public AuditSearchCapabilityManifest capabilityManifest() {
        return AuditSearchCapabilityManifest.conformanceOnly();
    }

    private static AuditSearchAuthorizationDecision allowed(
            String action, String scope, Map<String, FieldVisibility> projection) {
        return new AuditSearchAuthorizationDecision(
                true, "RFP-1.0.0", action, Set.of(scope), projection, null);
    }
}
