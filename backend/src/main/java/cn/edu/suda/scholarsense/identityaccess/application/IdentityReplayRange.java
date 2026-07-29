package cn.edu.suda.scholarsense.identityaccess.application;

public record IdentityReplayRange(long fromInclusive, long toInclusive) {
    public IdentityReplayRange {
        if (fromInclusive < 1 || toInclusive < fromInclusive) {
            throw new IllegalArgumentException("IDENTITY_REPLAY_RANGE_INVALID");
        }
    }
}
