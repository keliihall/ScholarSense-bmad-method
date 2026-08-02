package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;

public record AccessInvalidationRetention(
        String classification,
        String retentionScheduleVersion,
        Instant retainUntil,
        boolean legalHold) {
    public AccessInvalidationRetention {
        if (!"restricted".equals(classification)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_CLASSIFICATION_INVALID");
        }
        if (!"RS-1.0.0".equals(retentionScheduleVersion)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_RETENTION_SCHEDULE_INVALID");
        }
        AccessInvalidationValidation.required(retainUntil, "retainUntil");
    }
}
