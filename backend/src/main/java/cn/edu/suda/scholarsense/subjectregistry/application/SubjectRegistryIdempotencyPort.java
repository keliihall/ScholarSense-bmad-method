package cn.edu.suda.scholarsense.subjectregistry.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SubjectRegistryIdempotencyPort {
    Optional<RepairIdempotencyResult> find(RepairIdempotencyScope scope, Instant at);
    RepairIdempotencyClaim claim(
            RepairIdempotencyScope scope, String requestDigest, UUID exceptionId, Instant at);
    void complete(RepairIdempotencyResult result);
}
