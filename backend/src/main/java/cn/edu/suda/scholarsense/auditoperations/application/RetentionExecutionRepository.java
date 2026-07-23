package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Optional;
import java.util.UUID;

public interface RetentionExecutionRepository {
    Optional<RetentionExecution> find(UUID executionId);

    RetentionExecution create(RetentionExecution execution);

    RetentionExecution replace(
            UUID executionId, long expectedAggregateVersion, long fencingToken, RetentionExecution replacement);
}
