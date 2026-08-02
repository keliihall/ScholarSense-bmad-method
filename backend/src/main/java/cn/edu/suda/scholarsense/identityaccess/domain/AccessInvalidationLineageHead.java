package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.UUID;

public record AccessInvalidationLineageHead(
        AccessInvalidationLineageId lineageId,
        UUID eventId,
        long aggregateVersion) {
    public AccessInvalidationLineageHead {
        AccessInvalidationValidation.required(lineageId, "lineageId");
        AccessInvalidationValidation.uuidV7(
                eventId, "ACCESS_INVALIDATION_HEAD_EVENT");
        AccessInvalidationValidation.positive(
                aggregateVersion,
                "ACCESS_INVALIDATION_HEAD_VERSION");
    }

    public boolean accepts(AccessInvalidationFact candidate) {
        return candidate != null
                && lineageId.equals(candidate.lineageId())
                && aggregateVersion + 1 == candidate.aggregateVersion()
                && eventId.equals(candidate.supersedesId());
    }
}
