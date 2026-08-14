package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.UUID;

/** Opaque current-evidence lookup used by object-owner authorization providers. */
public record HighRiskApprovalEvidenceQuery(
        String actionType,
        String objectType,
        String objectRefDigest,
        long objectVersion,
        UUID actorAccountId,
        Instant trustedNow,
        String traceId) {}
