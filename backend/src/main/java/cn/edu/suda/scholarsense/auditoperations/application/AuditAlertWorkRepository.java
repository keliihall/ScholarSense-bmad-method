package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditAlertWorkRepository {
    List<ClaimedAuditAlert> claimDue(int batchSize, Instant now, Duration lease);

    boolean confirm(UUID alertId, int expectedAttempts, Instant confirmedAt);

    boolean retry(UUID alertId, int expectedAttempts, Instant retryAt, String errorCode);
}
