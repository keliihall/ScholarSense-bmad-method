package cn.edu.suda.scholarsense.identityaccess.application;

/** Latest committed version and payload identity for one stable source record key. */
public record IdentityRecordState(long sourceVersion, String payloadDigest) {
    public IdentityRecordState {
        if (sourceVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_SOURCE_VERSION_INVALID");
        }
        if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IDENTITY_SOURCE_DIGEST_INVALID");
        }
    }
}
