package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

/** Persists the request-level status returned to R6/R7 independently of a browser session. */
public interface MappingRecomputePlanPort {
    void recordPlan(
            UUID requestId,
            UUID correctionLineageId,
            String ownerSourceId,
            int jobCount,
            int historyOnlyWindowCount,
            Instant plannedAt,
            String traceId);
}
