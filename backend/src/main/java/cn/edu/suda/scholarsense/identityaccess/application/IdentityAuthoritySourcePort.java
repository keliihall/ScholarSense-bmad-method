package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentityAuthoritySourcePort {
    NormalizedIdentityBatch fetch(
            CheckpointKey key, long afterWatermark, String traceId);

    default NormalizedIdentityBatch fetchRange(
            CheckpointKey key,
            long fromInclusive,
            long toInclusive,
            String traceId) {
        throw new IdentitySyncException("IDENTITY_SOURCE_REPLAY_UNAVAILABLE");
    }
}
