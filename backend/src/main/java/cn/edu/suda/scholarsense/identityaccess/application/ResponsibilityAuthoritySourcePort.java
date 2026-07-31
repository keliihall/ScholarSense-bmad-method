package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface ResponsibilityAuthoritySourcePort {
    NormalizedResponsibilityBatch fetch(
            CheckpointKey key, long afterWatermark, String traceId);

    default NormalizedResponsibilityBatch fetchRange(
            CheckpointKey key,
            long fromInclusive,
            long toInclusive,
            String traceId) {
        throw new IdentitySyncException(
                "RESPONSIBILITY_SOURCE_REPLAY_UNAVAILABLE");
    }
}
