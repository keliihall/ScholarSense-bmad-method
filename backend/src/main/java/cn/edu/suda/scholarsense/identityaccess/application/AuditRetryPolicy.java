package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Duration;

public final class AuditRetryPolicy {
    private AuditRetryPolicy() {}

    public static Duration delay(long attempts, double jitter) {
        if (attempts < 1 || jitter < 0.5 || jitter > 1.0 || !Double.isFinite(jitter)) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_RETRY_POLICY_INVALID");
        }
        long capSeconds = attempts >= 7 ? 60 : 1L << (attempts - 1);
        return Duration.ofMillis(Math.round(capSeconds * 1_000d * jitter));
    }
}
