package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record RemoteLogoutRequest(
        String requestId,
        String sessionId,
        String registrationId,
        SessionCommandType commandType,
        String idempotencyKey,
        Instant createdAt,
        String state) {}
