package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Optional;

public interface RemoteLogoutWorkRepository {
    Optional<StoredRemoteLogout> claimDue(Instant now);

    void markConfirmed(String requestId, Instant completedAt);

    void markRetry(String requestId, String safeErrorCode, Instant nextAttemptAt);
}
