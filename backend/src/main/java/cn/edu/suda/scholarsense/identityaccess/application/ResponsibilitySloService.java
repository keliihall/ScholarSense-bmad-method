package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Records post-commit public read-back without changing committed source state. */
public final class ResponsibilitySloService
        implements ResponsibilitySloRecorder {
    private final ResponsibilityScopeReadBackPort readBack;
    private final ResponsibilitySloEvidencePort evidence;
    private final IdentitySyncObservabilityPort observability;
    private final TrustedTimeSource time;

    public ResponsibilitySloService(
            ResponsibilityScopeReadBackPort readBack,
            ResponsibilitySloEvidencePort evidence,
            IdentitySyncObservabilityPort observability,
            TrustedTimeSource time) {
        this.readBack = java.util.Objects.requireNonNull(readBack);
        this.evidence = java.util.Objects.requireNonNull(evidence);
        this.observability =
                java.util.Objects.requireNonNull(observability);
        this.time = java.util.Objects.requireNonNull(time);
    }

    @Override
    public void record(
            NormalizedResponsibilityBatch batch,
            Instant appliedAt,
            IdentitySyncResult result) {
        if (batch.relations().isEmpty()
                || result.sourceVersion() != batch.sourceVersion()
                || result.sourceWatermark() != batch.toWatermark()
                || result.outcome() != IdentitySyncOutcome.APPLIED
                        && result.outcome()
                                != IdentitySyncOutcome.REPLAYED) {
            return;
        }
        for (var relation : batch.relations()) {
            recordOne(batch, relation.relationId(),
                    relation.studentSourceReference()
                            .equivalenceDomain(),
                    appliedAt, result);
        }
    }

    public ResponsibilitySloWindow rollingThirtyDays() {
        return evidence.rollingThirtyDays(time.now().instant());
    }

    private void recordOne(
            NormalizedResponsibilityBatch batch,
            UUID relationId,
            String studentDigest,
            Instant appliedAt,
            IdentitySyncResult result) {
        ResponsibilityScopeReadBack observation;
        Instant effectiveAt;
        try {
            Instant probeAt = time.now().instant();
            observation = readBack.readBack(
                    studentDigest, probeAt);
            effectiveAt = observation.evaluatedAt();
        } catch (RuntimeException unavailable) {
            effectiveAt = appliedAt;
            observation = new ResponsibilityScopeReadBack(
                    ResponsibilityRecipientValidity
                            .DEPENDENCY_UNAVAILABLE,
                    "RESPONSIBILITY_SCOPE_READBACK_UNAVAILABLE",
                    0,
                    0,
                    0,
                    effectiveAt);
        }
        boolean empty = observation.sourceVersion() == 0
                && observation.sourceWatermark() == 0
                && observation.aggregateVersion() == 0;
        boolean versionVisible =
                observation.sourceVersion()
                                == result.sourceVersion()
                        && observation.sourceWatermark()
                                == result.sourceWatermark()
                        && observation.aggregateVersion()
                                >= result.aggregateVersion();
        boolean available = observation.validity()
                != ResponsibilityRecipientValidity
                        .DEPENDENCY_UNAVAILABLE;
        boolean within = available
                && versionVisible
                && !effectiveAt.isAfter(
                        batch.sourceVisibleAt()
                                .plus(Duration.ofMinutes(15)));
        String reasonCode = within
                ? null
                : empty
                        ? "RESPONSIBILITY_SCOPE_READBACK_EMPTY"
                        : !available
                                ? "RESPONSIBILITY_SCOPE_READBACK_UNAVAILABLE"
                                : !versionVisible
                                        ? "RESPONSIBILITY_SCOPE_READBACK_VERSION_MISMATCH"
                                        : "RESPONSIBILITY_SCOPE_EFFECTIVE_LATE";
        ResponsibilitySloEvidence item =
                new ResponsibilitySloEvidence(
                        UUID.fromString(
                                UuidV7.generate(effectiveAt)),
                        relationId,
                        studentDigest,
                        result.sourceVersion(),
                        result.sourceWatermark(),
                        result.aggregateVersion(),
                        batch.sourceVisibleAt(),
                        appliedAt,
                        effectiveAt,
                        within,
                        reasonCode,
                        batch.traceId());
        try {
            evidence.append(item);
            recordMetric(batch, item, within ? "met" : "missed");
        } catch (RuntimeException writeFailure) {
            ResponsibilitySloEvidence failed =
                    item.asWriteFailure();
            try {
                evidence.compensate(
                        failed,
                        "RESPONSIBILITY_SLO_EVIDENCE_WRITE_FAILED");
                recordMetric(
                        batch,
                        failed,
                        "compensation_required");
            } catch (RuntimeException compensationFailure) {
                recordMetric(
                        batch,
                        failed,
                        "compensation_failed");
                throw new IdentitySyncException(
                        "RESPONSIBILITY_SLO_COMPENSATION_PERSISTENCE_UNAVAILABLE");
            }
        }
    }

    private void recordMetric(
            NormalizedResponsibilityBatch batch,
            ResponsibilitySloEvidence item,
            String outcome) {
        observability.record(new IdentitySyncObservation(
                "responsibility_sync_slo_probe_total",
                1,
                Map.of(
                        "sourceId", batch.key().sourceId(),
                        "feedId", batch.key().feedId(),
                        "consumerProjection", "responsibility",
                        "outcome", outcome),
                item.traceId(),
                item.authorizationEffectiveAt()));
    }
}
