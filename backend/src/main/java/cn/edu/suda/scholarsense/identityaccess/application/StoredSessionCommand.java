package cn.edu.suda.scholarsense.identityaccess.application;

public record StoredSessionCommand(
        String sessionId,
        SessionCommandType commandType,
        String idempotencyKey,
        String requestDigest,
        SessionCommandResult result) {}
