package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** A delivery projection containing only the contract-approved structured alert JSON. */
public record ClaimedAuditAlert(UUID alertId, int attempts, String safePayload) {
    public ClaimedAuditAlert {
        Objects.requireNonNull(alertId, "alertId");
        if (attempts < 1) {
            throw new IllegalArgumentException("AUDIT_ALERT_ATTEMPTS_INVALID");
        }
        if (safePayload == null || safePayload.length() > 8192
                || !safePayload.strip().startsWith("{") || !safePayload.strip().endsWith("}")) {
            throw new IllegalArgumentException("AUDIT_ALERT_PAYLOAD_INVALID");
        }
        String normalized = safePayload.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (String forbidden : new String[] {
                "\"studentid\"", "\"token\"", "\"cookie\"", "\"rawpayload\"",
                "\"actorid\"", "\"objectid\"", "\"sourceip\""}) {
            if (normalized.contains(forbidden)) {
                throw new IllegalArgumentException("AUDIT_ALERT_SENSITIVE_FIELD_FORBIDDEN");
            }
        }
    }
}
