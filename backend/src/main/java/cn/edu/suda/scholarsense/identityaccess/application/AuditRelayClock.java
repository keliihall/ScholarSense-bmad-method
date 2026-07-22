package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

@FunctionalInterface
public interface AuditRelayClock {
    Instant now();
}
