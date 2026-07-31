package cn.edu.suda.scholarsense.identityaccess.application;

public record ResponsibilityRecordState(long recordVersion, String payloadDigest) {
    public ResponsibilityRecordState {
        if (recordVersion < 1
                || payloadDigest == null
                || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RECORD_STATE_INVALID");
        }
    }
}
