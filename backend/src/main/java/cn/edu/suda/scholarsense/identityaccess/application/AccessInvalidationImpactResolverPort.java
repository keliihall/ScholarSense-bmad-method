package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationCause;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import java.util.List;

@FunctionalInterface
public interface AccessInvalidationImpactResolverPort {
    List<AccessInvalidationFact> resolve(
            AccessInvalidationCause cause,
            int batchSize,
            long sequence,
            String afterLineageId);
}
