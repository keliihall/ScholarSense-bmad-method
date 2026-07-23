package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;

public record AuditSearchTokenQuery(
        AuditSearchTokenDomain domain,
        String rawReference,
        Instant retainedFrom) {
    public AuditSearchTokenQuery {
        Objects.requireNonNull(domain, "domain");
        if (rawReference == null || rawReference.isBlank() || rawReference.length() > 256) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_INPUT_INVALID");
        }
        Objects.requireNonNull(retainedFrom, "retainedFrom");
    }
}
