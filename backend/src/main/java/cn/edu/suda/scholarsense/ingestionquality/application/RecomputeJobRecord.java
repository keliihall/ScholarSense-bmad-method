package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJobStatus;
import java.time.Instant;
import java.util.UUID;

public record RecomputeJobRecord(
        UUID jobId,
        MappingRecomputeJobStatus status,
        int attemptNo,
        Instant queuedAt,
        Instant completedAt,
        String resultCode,
        String traceId,
        String ownerSourceId,
        long objectVersion) {}
