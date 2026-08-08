package cn.edu.suda.scholarsense.subjectregistry.api;

import java.util.Optional;
import java.util.UUID;

/** Query boundary for the commit-to-relay interval; it exposes no protected identifiers. */
@FunctionalInterface
public interface PendingSubjectRecomputeRequestPort {
    Optional<PendingSubjectRecomputeRequest> findPendingRequest(UUID requestId);
}
