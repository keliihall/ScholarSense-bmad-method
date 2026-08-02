package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationConsumerRoute;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationConsumerWatermark;
import java.util.Optional;

@FunctionalInterface
public interface AccessInvalidationWatermarkQueryPort {
    Optional<AccessInvalidationConsumerWatermark> find(
            AccessInvalidationConsumerRoute route);
}
