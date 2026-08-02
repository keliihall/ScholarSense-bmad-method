package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationConsumerRoute;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDeliveryDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import java.time.Instant;

@FunctionalInterface
public interface AccessInvalidationConsumerPort {
    AccessInvalidationDeliveryDecision apply(
            AccessInvalidationConsumerRoute route,
            AccessInvalidationFact fact,
            String eventPayload,
            String payloadDigest,
            Instant appliedAt);
}
