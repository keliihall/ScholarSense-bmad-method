package cn.edu.suda.scholarsense.identityaccess.domain;

public record AccessInvalidationDependencyWatermark(
        String feedId,
        String partitionId,
        long watermark) {
    public AccessInvalidationDependencyWatermark {
        if (!"identity-authority".equals(feedId)
                && !"responsibility-authority".equals(feedId)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_DEPENDENCY_FEED_INVALID");
        }
        AccessInvalidationValidation.partitionIdentifier(
                partitionId, "ACCESS_INVALIDATION_DEPENDENCY_PARTITION");
        AccessInvalidationValidation.nonNegative(
                watermark, "ACCESS_INVALIDATION_DEPENDENCY_WATERMARK");
    }

    public String route() {
        return feedId + "|" + partitionId;
    }
}
