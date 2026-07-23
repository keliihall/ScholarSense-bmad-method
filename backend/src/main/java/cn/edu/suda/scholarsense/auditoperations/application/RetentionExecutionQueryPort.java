package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface RetentionExecutionQueryPort {
    Optional<RetentionExecution> find(UUID executionId);
}
