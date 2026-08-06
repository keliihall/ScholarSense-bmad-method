package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CatalogAuditRelayWorkPort {
    List<CatalogAuditRelayClaim> claimDue(int batchSize, Instant now, Duration lease);
    boolean confirm(UUID eventId, long attempts, Instant at);
    boolean retry(UUID eventId, long attempts, Instant nextAttemptAt, String errorCode);
    boolean fail(UUID eventId, long attempts, Instant at, String errorCode);
}
