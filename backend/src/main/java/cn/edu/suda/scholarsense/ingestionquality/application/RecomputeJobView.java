package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

public record RecomputeJobView(
        UUID jobId, String status, int attemptNo, Instant queuedAt,
        Instant completedAt, String resultCode, String traceId) {}
