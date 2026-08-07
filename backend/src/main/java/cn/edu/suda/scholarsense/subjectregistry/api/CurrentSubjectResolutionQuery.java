package cn.edu.suda.scholarsense.subjectregistry.api;

import java.time.Instant;
import java.util.Objects;

public record CurrentSubjectResolutionQuery(
        String sourceId,
        String identifierType,
        String environment,
        String keyRef,
        String keyVersion,
        String protectedIdentifierToken,
        Instant at) {
    public CurrentSubjectResolutionQuery {
        if (sourceId == null || identifierType == null || environment == null
                || keyRef == null || keyVersion == null || protectedIdentifierToken == null) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_RESOLUTION_QUERY_INVALID");
        }
        Objects.requireNonNull(at);
    }
}
