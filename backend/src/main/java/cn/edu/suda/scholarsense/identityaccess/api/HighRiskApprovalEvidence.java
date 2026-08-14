package cn.edu.suda.scholarsense.identityaccess.api;

public record HighRiskApprovalEvidence(
        Availability availability,
        String status,
        long approvalVersion,
        String receiptDigest,
        long authorizationGeneration,
        String reasonCode) {
    public enum Availability { AVAILABLE, UNAVAILABLE, NOT_INSTALLED }

    public boolean currentlyApproved() {
        return availability == Availability.AVAILABLE && "approved".equals(status);
    }

    public static HighRiskApprovalEvidence notInstalled() {
        return new HighRiskApprovalEvidence(
                Availability.NOT_INSTALLED, null, 0, null, 0, "PROVIDER_NOT_INSTALLED");
    }
}
