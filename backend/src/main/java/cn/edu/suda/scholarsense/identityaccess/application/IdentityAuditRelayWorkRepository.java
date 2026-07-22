package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Identity-owned delivery metadata boundary. Every state transition is fenced by expected attempts. */
public interface IdentityAuditRelayWorkRepository {
    List<AuditRelayClaim> claimDue(int batchSize, Instant now, Duration lease);

    boolean confirm(UUID eventId, long expectedAttempts, Instant confirmedAt);

    boolean retry(UUID eventId, long expectedAttempts, Instant nextAttemptAt, String errorCode);

    boolean fail(UUID eventId, long expectedAttempts, String errorCode, Instant failedAt);
}
