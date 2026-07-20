package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.regex.Pattern;

public record SessionCommand(
        SessionCommandType commandType,
        String sessionId,
        long expectedSessionVersion,
        String idempotencyKey,
        String requestDigest,
        String sourceIp,
        String traceId) {

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("idem_[A-Za-z0-9_-]{43}");

    public SessionCommand {
        if (commandType == null || expectedSessionVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_SESSION_COMMAND_INVALID");
        }
        for (String value : new String[] {
                sessionId, requestDigest, sourceIp, traceId
        }) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("IDENTITY_SESSION_COMMAND_INVALID");
            }
        }
        if (!isIdempotencyKeyValid(idempotencyKey)) {
            throw new IllegalArgumentException("IDENTITY_SESSION_COMMAND_INVALID");
        }
    }

    static boolean isIdempotencyKeyValid(String value) {
        return value != null && IDEMPOTENCY_KEY.matcher(value).matches();
    }
}
