package cn.edu.suda.scholarsense.identityaccess.application;

public record ResponsibilityReconciliationDifference(
        String differenceType,
        String relationRefToken,
        String studentSourceRefDigest,
        Long expectedRecordVersion,
        Long actualRecordVersion,
        String expectedPayloadDigest,
        String actualPayloadDigest,
        String reasonCode) {
    public ResponsibilityReconciliationDifference {
        if (!java.util.Set.of(
                        "missing",
                        "unexpected",
                        "version-drift",
                        "duplicate")
                .contains(differenceType)
                || relationRefToken == null
                || !relationRefToken.matches(
                        "rtok_[A-Za-z0-9_-]{32,128}")
                || studentSourceRefDigest == null
                || !studentSourceRefDigest.matches("[0-9a-f]{64}")
                || reasonCode == null
                || !reasonCode.matches("RESPONSIBILITY_[A-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RECONCILIATION_DETAIL_INVALID");
        }
    }
}
