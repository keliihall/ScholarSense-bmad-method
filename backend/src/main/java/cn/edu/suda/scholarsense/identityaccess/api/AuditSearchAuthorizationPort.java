package cn.edu.suda.scholarsense.identityaccess.api;

public interface AuditSearchAuthorizationPort {
    AuditSearchAuthorizationDecision authorize(AuditSearchAuthorizationRequest request);

    AuditSearchCapabilityManifest capabilityManifest();

    static AuditSearchAuthorizationPort productionFailClosed() {
        return new AuditSearchAuthorizationPort() {
            @Override
            public AuditSearchAuthorizationDecision authorize(AuditSearchAuthorizationRequest request) {
                if (request == null) {
                    throw new IllegalArgumentException("AUDIT_SEARCH_AUTHORIZATION_REQUEST_REQUIRED");
                }
                return AuditSearchAuthorizationDecision.denied("AUDIT_SEARCH_AUTHORITY_UNAVAILABLE");
            }

            @Override
            public AuditSearchCapabilityManifest capabilityManifest() {
                return AuditSearchCapabilityManifest.conformanceOnly();
            }
        };
    }
}
