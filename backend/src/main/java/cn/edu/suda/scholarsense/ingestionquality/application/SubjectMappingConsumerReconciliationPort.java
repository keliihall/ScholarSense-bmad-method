package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface SubjectMappingConsumerReconciliationPort {
    boolean reconcile(UUID aggregateId, long authoritativeVersion, Instant serverNow);
}
