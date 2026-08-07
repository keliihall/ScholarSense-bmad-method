package cn.edu.suda.scholarsense.subjectregistry.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SubjectMappingRelayWorkPort {
    List<SubjectMappingRelayClaim> claimDue(int batchSize, Instant now, Duration lease);
    boolean confirm(UUID requestId, long attempts, Instant at);
    boolean retry(UUID requestId, long attempts, Instant at, String code);
    boolean fail(UUID requestId, long attempts, Instant at, String code);
}
