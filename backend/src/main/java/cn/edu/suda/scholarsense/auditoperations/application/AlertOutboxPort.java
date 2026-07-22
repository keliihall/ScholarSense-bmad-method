package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import java.time.Instant;

@FunctionalInterface
public interface AlertOutboxPort {
    void enqueueActive(IntegrityFinding finding);

    default void enqueueResolved(IntegrityFinding finding, Instant resolvedAt) {}
}
