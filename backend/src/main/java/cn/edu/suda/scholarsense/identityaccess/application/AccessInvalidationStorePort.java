package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageHead;
import java.util.Optional;
import java.util.UUID;

public interface AccessInvalidationStorePort {
    AccessInvalidationFact append(
            AccessInvalidationAppendCommand command);

    Optional<AccessInvalidationLineageHead> head(String lineageId);

    Optional<AccessInvalidationFact> find(UUID eventId);

    Optional<AccessInvalidationFact> latest(String lineageId);

    long fencingToken(String lineageId);
}
