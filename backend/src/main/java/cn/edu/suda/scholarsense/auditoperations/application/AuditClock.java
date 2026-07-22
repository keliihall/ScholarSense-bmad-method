package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;

@FunctionalInterface
public interface AuditClock {
    Instant now();
}
