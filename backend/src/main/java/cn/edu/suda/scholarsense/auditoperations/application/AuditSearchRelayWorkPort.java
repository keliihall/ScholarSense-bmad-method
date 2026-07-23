package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditSearchRelayWorkPort {
    List<AuditSearchRelayClaim> claimDue(int batchSize, Instant now, Duration lease);
    boolean confirm(UUID eventId, long attempts, Instant at);
    boolean retry(UUID eventId, long attempts, Instant nextAttemptAt, String errorCode);
    boolean fail(UUID eventId, long attempts, Instant at, String errorCode);
}
