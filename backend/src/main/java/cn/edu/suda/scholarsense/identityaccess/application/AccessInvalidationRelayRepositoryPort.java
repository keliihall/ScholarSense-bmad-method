package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccessInvalidationRelayRepositoryPort {
    List<AccessInvalidationOutboxClaim> claim(
            String leaseOwner,
            int batchSize,
            Instant now,
            Instant leaseExpiresAt);

    void published(
            AccessInvalidationOutboxClaim claim,
            UUID attemptId,
            Instant publishedAt);

    void failed(
            AccessInvalidationOutboxClaim claim,
            UUID attemptId,
            String reasonCode,
            Instant failedAt,
            Instant nextAttemptAt,
            boolean quarantine);
}
