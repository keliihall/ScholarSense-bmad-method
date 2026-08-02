package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface ResponsibilityAuthoritySourcePort {
    String VERSION_1 = "RESPONSIBILITY-AUTHORITY-1.0.0";
    String VERSION_2 = "RESPONSIBILITY-AUTHORITY-2.0.0";

    NormalizedResponsibilityBatch fetch(
            CheckpointKey key, long afterWatermark, String traceId);

    default NormalizedResponsibilityBatch fetch(
            CheckpointKey key,
            String contractVersion,
            long afterWatermark,
            String traceId) {
        if (!VERSION_1.equals(contractVersion)) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
        }
        return fetch(key, afterWatermark, traceId);
    }

    default NormalizedResponsibilityBatch fetchRange(
            CheckpointKey key,
            long fromInclusive,
            long toInclusive,
            String traceId) {
        throw new IdentitySyncException(
                "RESPONSIBILITY_SOURCE_REPLAY_UNAVAILABLE");
    }

    default NormalizedResponsibilityBatch fetchRange(
            CheckpointKey key,
            String contractVersion,
            long fromInclusive,
            long toInclusive,
            String traceId) {
        if (!VERSION_1.equals(contractVersion)) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
        }
        return fetchRange(
                key, fromInclusive, toInclusive, traceId);
    }
}
