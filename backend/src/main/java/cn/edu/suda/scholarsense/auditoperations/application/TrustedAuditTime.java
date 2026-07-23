package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;

public record TrustedAuditTime(Instant value, boolean fresh) {
    public TrustedAuditTime {
        if (value == null) throw new IllegalArgumentException("AUDIT_TRUSTED_TIME_INVALID");
    }
}
