package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierKey;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import cn.edu.suda.scholarsense.subjectregistry.domain.ProtectedIdentifierToken;
import java.util.Arrays;
import java.util.Objects;

public final class SubjectResolutionQueryService {
    private final SubjectRegistryRepository repository;

    public SubjectResolutionQueryService(SubjectRegistryRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public SubjectResolutionLookupResult resolve(SubjectResolutionLookup query) {
        Objects.requireNonNull(query);
        IdentifierType type = Arrays.stream(IdentifierType.values())
                .filter(candidate -> candidate.wireValue().equals(query.identifierType()))
                .findFirst()
                .orElseThrow(() -> new SubjectRegistryApplicationException(
                        "SUBJECT_REGISTRY_IDENTIFIER_INVALID"));
        IdentifierKey key = new IdentifierKey(
                query.sourceId(), type,
                ProtectedIdentifierToken.of(
                        query.environment(), query.keyRef(), query.keyVersion(),
                        query.protectedIdentifierToken()));
        var resolution = repository.timeline(key).resolve(key, query.at());
        String outcome = switch (resolution.outcome()) {
            case NO_MATCH -> "no-match";
            case UNIQUE -> "unique";
            case AMBIGUOUS -> "ambiguous";
        };
        return new SubjectResolutionLookupResult(
                outcome, resolution.studentRef().map(Object::toString));
    }
}
