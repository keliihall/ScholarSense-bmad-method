package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationJob;
import java.util.Optional;

public interface RecoveryValidationSubmissionPort {
    Optional<RecoveryValidationJob> findByIdempotencyKeyDigest(String digest);

    RecoveryValidationJob insertIfAbsent(
            String idempotencyKeyDigest, RecoveryValidationJob requested);
}
