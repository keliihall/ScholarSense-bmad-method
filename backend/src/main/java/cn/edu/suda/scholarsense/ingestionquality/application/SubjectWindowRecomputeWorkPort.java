package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SubjectWindowRecomputeWorkPort {
    List<SubjectWindowRecomputeCandidate> findClaimable(int batchSize, Instant now);
    long claim(UUID jobId, String workerId, Instant now, Duration lease);
    boolean checkpoint(UUID jobId, long fence, long sequence, Instant now);
    MappingRecomputeCompletion complete(UUID jobId, long fence, Instant now, UUID eventId);
    boolean fail(UUID jobId, long fence, Instant now, String controlledCode);
}
