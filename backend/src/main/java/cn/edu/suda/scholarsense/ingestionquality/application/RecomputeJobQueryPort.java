package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface RecomputeJobQueryPort {
    Optional<RecomputeJobRecord> findJob(UUID jobId);
}
