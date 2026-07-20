package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record StoredRemoteLogout(
        String requestId,
        String sessionId,
        String registrationId,
        SessionCommandType commandType,
        int attempts,
        Instant nextAttemptAt) {}
