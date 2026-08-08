package cn.edu.suda.scholarsense.subjectregistry.application;

import java.time.Instant;
import java.util.Objects;

public record SubjectResolutionLookup(
        String sourceId,
        String identifierType,
        String environment,
        String keyRef,
        String keyVersion,
        String protectedIdentifierToken,
        Instant at) {
    public SubjectResolutionLookup {
        Objects.requireNonNull(at);
    }
}
