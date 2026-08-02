package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Future expiry carried from the V2 shadow projection into the live scheduler. */
public record ResponsibilityV2ExpiryCandidate(
        AccessInvalidationLineageId lineageId,
        UUID scheduledEventId,
        long scheduledAggregateVersion,
        Instant effectiveTo,
        String traceId) {
    public ResponsibilityV2ExpiryCandidate {
        Objects.requireNonNull(lineageId, "lineageId");
        if (scheduledEventId == null
                || scheduledEventId.version() != 7
                || scheduledEventId.variant() != 2
                || scheduledAggregateVersion < 1
                || effectiveTo == null
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_EXPIRY_CANDIDATE_INVALID");
        }
    }
}
