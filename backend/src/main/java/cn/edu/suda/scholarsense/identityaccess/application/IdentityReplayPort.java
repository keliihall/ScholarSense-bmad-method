package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Optional;

@FunctionalInterface
public interface IdentityReplayPort {
    void request(CheckpointKey key, long fromInclusive, long toInclusive, String traceId);

    default Optional<IdentityReplayRange> nextRequested(CheckpointKey key) {
        return Optional.empty();
    }
}
