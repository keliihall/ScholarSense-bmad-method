package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;

public record AccessInvalidationConsumerRegistration(
        String consumerId,
        String owner,
        String ownerStory,
        String applicableRelease,
        AccessInvalidationConsumerLifecycle lifecycle,
        boolean required,
        Instant activationAt,
        Long initialWatermark,
        AccessInvalidationRuntimeEvidenceClaim runtimeEvidenceClaim) {
    public AccessInvalidationConsumerRegistration {
        AccessInvalidationValidation.identifier(
                consumerId, "ACCESS_INVALIDATION_CONSUMER_ID");
        AccessInvalidationValidation.identifier(
                owner, "ACCESS_INVALIDATION_CONSUMER_OWNER");
        if (ownerStory == null
                || !ownerStory.matches("[0-9]+\\.[0-9]+[a-z]?")) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_OWNER_STORY_INVALID");
        }
        if (applicableRelease == null || applicableRelease.isBlank()) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_APPLICABLE_RELEASE_INVALID");
        }
        AccessInvalidationValidation.required(lifecycle, "lifecycle");
        AccessInvalidationValidation.required(
                runtimeEvidenceClaim, "runtimeEvidenceClaim");
        if (lifecycle
                == AccessInvalidationConsumerLifecycle
                        .PLANNED_NOT_INSTALLED) {
            if (required
                    || activationAt != null
                    || initialWatermark != null
                    || runtimeEvidenceClaim
                            != AccessInvalidationRuntimeEvidenceClaim.NONE) {
                throw new IllegalArgumentException(
                        "ACCESS_INVALIDATION_PLANNED_CONSUMER_CLAIM_INVALID");
            }
        } else if (lifecycle
                == AccessInvalidationConsumerLifecycle.ACTIVE) {
            if (activationAt == null || initialWatermark == null) {
                throw new IllegalArgumentException(
                        "ACCESS_INVALIDATION_ACTIVE_CONSUMER_ACTIVATION_INVALID");
            }
            AccessInvalidationValidation.nonNegative(
                    initialWatermark,
                    "ACCESS_INVALIDATION_INITIAL_WATERMARK");
        }
    }

    public static AccessInvalidationConsumerRegistration activeRequired(
            String consumerId,
            String owner,
            String ownerStory,
            String applicableRelease,
            Instant activationAt,
            long initialWatermark) {
        return new AccessInvalidationConsumerRegistration(
                consumerId,
                owner,
                ownerStory,
                applicableRelease,
                AccessInvalidationConsumerLifecycle.ACTIVE,
                true,
                activationAt,
                initialWatermark,
                AccessInvalidationRuntimeEvidenceClaim.CURRENT_RUNTIME);
    }

    public static AccessInvalidationConsumerRegistration planned(
            String consumerId, String owner, String ownerStory) {
        return new AccessInvalidationConsumerRegistration(
                consumerId,
                owner,
                ownerStory,
                "owner-story-activation",
                AccessInvalidationConsumerLifecycle
                        .PLANNED_NOT_INSTALLED,
                false,
                null,
                null,
                AccessInvalidationRuntimeEvidenceClaim.NONE);
    }
}
