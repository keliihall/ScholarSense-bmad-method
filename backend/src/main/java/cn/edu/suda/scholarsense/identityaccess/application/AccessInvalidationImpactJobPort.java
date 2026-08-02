package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationCause;
import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface AccessInvalidationImpactJobPort {
    void enqueue(
            UUID jobId,
            AccessInvalidationCause cause,
            Instant retainUntil);
}
