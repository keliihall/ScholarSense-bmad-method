package cn.edu.suda.scholarsense.identityaccess.api;

public record AuditSearchCapabilityManifest(
        String rfpVersion,
        boolean conformanceVerified,
        boolean productionAuthorizationEnabled,
        boolean historicalTokenQueryEnabled) {
    public AuditSearchCapabilityManifest {
        if (!"RFP-1.0.0".equals(rfpVersion)) {
            throw new IllegalArgumentException("AUDIT_SEARCH_CAPABILITY_VERSION_INVALID");
        }
    }

    public static AuditSearchCapabilityManifest conformanceOnly() {
        return new AuditSearchCapabilityManifest("RFP-1.0.0", true, false, false);
    }
}
