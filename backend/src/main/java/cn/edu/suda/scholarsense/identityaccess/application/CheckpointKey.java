package cn.edu.suda.scholarsense.identityaccess.application;

public record CheckpointKey(
        String sourceId,
        String feedId,
        String partitionId,
        String consumerProjection) {
    public CheckpointKey {
        if (!"SRC-P0-RESPONSIBILITY-001".equals(sourceId)
                || feedId == null || !feedId.matches("[a-z][a-z0-9-]{2,63}")
                || partitionId == null || !partitionId.matches("[a-z0-9][a-z0-9-]{0,63}")
                || !java.util.Set.of("identity-org", "responsibility").contains(consumerProjection)) {
            throw new IllegalArgumentException("IDENTITY_CHECKPOINT_KEY_INVALID");
        }
    }
}
