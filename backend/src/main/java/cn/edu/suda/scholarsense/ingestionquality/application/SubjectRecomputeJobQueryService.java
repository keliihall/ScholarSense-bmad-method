package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckRequest;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJobStatus;
import cn.edu.suda.scholarsense.subjectregistry.api.PendingSubjectRecomputeRequestPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

public final class SubjectRecomputeJobQueryService {
    private final RecomputeJobQueryPort jobs;
    private final CompositeAuthorizationPort authorization;
    private final CompositeAuthorizationRecheckPort recheck;
    private final PendingSubjectRecomputeRequestPort pendingRequests;

    public SubjectRecomputeJobQueryService(
            RecomputeJobQueryPort jobs,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            PendingSubjectRecomputeRequestPort pendingRequests) {
        this.jobs = java.util.Objects.requireNonNull(jobs);
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.recheck = java.util.Objects.requireNonNull(recheck);
        this.pendingRequests = java.util.Objects.requireNonNull(pendingRequests);
    }

    public RecomputeJobView get(
            UUID jobId, RecomputeJobActorContext actor, String traceId) {
        RecomputeJobRecord current = findRecord(jobId).orElse(null);
        long version = current == null ? 1 : current.objectVersion();
        String ownerSource = current == null
                ? "SRC-P0-STUDENT-001" : current.ownerSourceId();
        boolean dependencyUnavailable = false;
        try {
            for (String action : java.util.List.of("platform.read")) {
                CompositeAuthorizationRequest request = new CompositeAuthorizationRequest(
                        actor.actorPseudonym(), "JOB", action,
                        digest(ownerSource), version, Optional.empty(), Optional.empty(), traceId);
                var decision = authorization.authorize(request);
                if (decision == null
                        || decision.outcome() == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE) {
                    dependencyUnavailable = true;
                    continue;
                }
                if (decision.outcome() != CompositeAuthorizationOutcome.ALLOW
                        || decision.objectVersion() != version) {
                    continue;
                }
                if (current == null) throw forbidden();
                var rechecked = recheck.recheck(new CompositeAuthorizationRecheckRequest(
                        request, decision.decisionToken()));
                if (rechecked == null
                        || rechecked.outcome() == CompositeAuthorizationRecheckOutcome.DEPENDENCY_UNAVAILABLE) {
                    throw unavailable();
                }
                if (rechecked.outcome() != CompositeAuthorizationRecheckOutcome.CURRENT) {
                    throw forbidden();
                }
                RecomputeJobRecord refreshed = findRecord(jobId).orElseThrow(
                        SubjectRecomputeJobQueryService::forbidden);
                if (refreshed.objectVersion() != version
                        || !refreshed.ownerSourceId().equals(ownerSource)) {
                    throw forbidden();
                }
                return view(refreshed);
            }
            if (dependencyUnavailable) throw unavailable();
            throw forbidden();
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private Optional<RecomputeJobRecord> findRecord(UUID jobId) {
        Optional<RecomputeJobRecord> persisted = jobs.findJob(jobId);
        if (persisted.isPresent()) return persisted;
        return pendingRequests.findPendingRequest(jobId).map(pending -> new RecomputeJobRecord(
                pending.requestId(), MappingRecomputeJobStatus.QUEUED, 0,
                pending.queuedAt(), null, null, pending.traceId(),
                pending.ownerSourceId(), 1));
    }

    private static RecomputeJobView view(RecomputeJobRecord current) {
        return new RecomputeJobView(
                current.jobId(), current.status().name().toLowerCase(java.util.Locale.ROOT),
                current.attemptNo(), current.queuedAt(), current.completedAt(),
                current.resultCode(), current.traceId());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IngestionQualityApplicationException forbidden() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }

    private static IngestionQualityApplicationException unavailable() {
        return new IngestionQualityApplicationException(
                "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
    }
}
