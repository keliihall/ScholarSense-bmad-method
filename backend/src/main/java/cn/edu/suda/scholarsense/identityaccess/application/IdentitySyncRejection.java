package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public record IdentitySyncRejection(
        UUID rejectionId,
        UUID batchId,
        CheckpointKey key,
        long sourceVersion,
        long sourceWatermark,
        String payloadDigest,
        String reasonCode,
        boolean replayable,
        UUID jobId,
        int attemptNo,
        String traceId,
        Instant rejectedAt) {}
