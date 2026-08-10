package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Production retention coordinator. Authority evidence is ingested first, then only its opaque id
 * crosses into the destructive executor boundary.
 */
public final class QualitySnapshotRetentionOrchestrator {
    private final QualitySnapshotRetentionCandidatePort candidates;
    private final ConsumerRegistryAuthorityPort authority;
    private final QualitySnapshotRetentionIdPort ids;
    private final QualitySnapshotRetentionAuthorityIngestPort ingest;
    private final QualitySnapshotRetentionExecutionPort executor;

    public QualitySnapshotRetentionOrchestrator(
            QualitySnapshotRetentionCandidatePort candidates,
            ConsumerRegistryAuthorityPort authority,
            QualitySnapshotRetentionIdPort ids,
            QualitySnapshotRetentionAuthorityIngestPort ingest,
            QualitySnapshotRetentionExecutionPort executor) {
        this.candidates = Objects.requireNonNull(candidates);
        this.authority = Objects.requireNonNull(authority);
        this.ids = Objects.requireNonNull(ids);
        this.ingest = Objects.requireNonNull(ingest);
        this.executor = Objects.requireNonNull(executor);
    }

    /** Executes at most one owner-selected due candidate. */
    public Optional<QualitySnapshotRetentionResult> runOne(String requestedTraceId) {
        String invocationTraceId = traceId(requestedTraceId);
        QualitySnapshotRetentionCandidate candidate;
        try {
            Optional<QualitySnapshotRetentionCandidate> next = Objects.requireNonNull(
                    candidates.findNextDue(), "candidate result");
            if (next.isEmpty()) return Optional.empty();
            candidate = Objects.requireNonNull(next.orElseThrow());
        } catch (RuntimeException unavailable) {
            if (unavailable instanceof IngestionQualityApplicationException classified) {
                throw classified;
            }
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }

        QualitySnapshotRetentionAttemptIds attempt = Objects.requireNonNull(ids.nextAttempt());
        if (attempt.resultEventId().equals(candidate.executionId())
                || attempt.resultEventId().equals(candidate.scope().snapshotId())
                || attempt.authorityEvidenceId().equals(candidate.executionId())
                || attempt.authorityEvidenceId().equals(candidate.scope().snapshotId())) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_RETENTION_ATTEMPT_ID_INVALID");
        }
        ingestVerifiedAuthorityIfAvailable(candidate, attempt);
        return Optional.of(Objects.requireNonNull(
                executor.execute(candidate, attempt, invocationTraceId)));
    }

    private void ingestVerifiedAuthorityIfAvailable(
            QualitySnapshotRetentionCandidate candidate,
            QualitySnapshotRetentionAttemptIds attempt) {
        Optional<QualitySnapshotRetentionAuthorityEvidence> verified;
        try {
            verified = Objects.requireNonNull(
                    authority.verify(candidate, attempt.authorityEvidenceId()),
                    "authority result");
        } catch (RuntimeException unavailableOrInvalid) {
            return;
        }
        if (verified.isEmpty()) return;
        QualitySnapshotRetentionAuthorityEvidence evidence = verified.orElseThrow();
        if (!evidence.authorityEvidenceId().equals(attempt.authorityEvidenceId())) return;
        try {
            UUID evidenceId = Objects.requireNonNull(
                    ingest.ingest(evidence), "authority evidence id");
            if (!evidenceId.equals(attempt.authorityEvidenceId())) return;
        } catch (RuntimeException unavailableOrInvalid) {
            return;
        }
    }

    private static String traceId(String value) {
        if (value == null || !value.matches("(?!0{32})[0-9a-f]{32}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_RETENTION_TRACE_INVALID");
        }
        return value;
    }
}
