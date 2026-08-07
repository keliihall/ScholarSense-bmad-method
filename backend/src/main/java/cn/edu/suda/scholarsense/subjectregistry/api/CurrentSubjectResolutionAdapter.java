package cn.edu.suda.scholarsense.subjectregistry.api;

import cn.edu.suda.scholarsense.subjectregistry.application.SubjectResolutionLookup;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectResolutionQueryService;
import java.util.Objects;

public final class CurrentSubjectResolutionAdapter implements CurrentSubjectResolutionPort {
    private final SubjectResolutionQueryService service;

    public CurrentSubjectResolutionAdapter(SubjectResolutionQueryService service) {
        this.service = Objects.requireNonNull(service);
    }

    @Override
    public CurrentSubjectResolution resolve(CurrentSubjectResolutionQuery query) {
        var result = service.resolve(new SubjectResolutionLookup(
                query.sourceId(), query.identifierType(), query.environment(),
                query.keyRef(), query.keyVersion(), query.protectedIdentifierToken(), query.at()));
        return new CurrentSubjectResolution(result.outcome(), result.subjectRef());
    }
}
