package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;

@FunctionalInterface
public interface AuditSearchRelayClock {
    Instant now();
}
