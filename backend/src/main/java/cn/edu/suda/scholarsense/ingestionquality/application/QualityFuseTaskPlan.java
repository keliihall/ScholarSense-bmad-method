package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTaskAction;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Self-contained owner-local episode/task mutation; public delivery remains a sidecar. */
public record QualityFuseTaskPlan(
        QualityFuseTaskAction action,
        UUID episodeId,
        UUID recoveryTaskId,
        String workItemKey,
        String workItemKeyVersion,
        long episodeGeneration,
        long expectedEpisodeAggregateVersion,
        String sourceId,
        long sourceVersion,
        String dependencyId,
        long dependencyVersion,
        UUID batchId,
        UUID snapshotId,
        String snapshotHash,
        String qualityGateVersion,
        String qualityGateDigest,
        String qmdpVersion,
        String qmdpDigest,
        String qshmVersion,
        String qshmDigest,
        UUID lineageId,
        String watermark,
        List<RuleVersionIdentity> affectedRules,
        List<QualityFuseEligibilityEvidence> eligibilityEvidence,
        List<QualityFuseFormulaBoundaryEvidence> formulaEvidence,
        String fuseBusinessKey,
        String commandBodyDigest,
        Instant effectiveAt,
        Instant occurredAt) {
    public QualityFuseTaskPlan {
        action = Objects.requireNonNull(action);
        episodeId = Objects.requireNonNull(episodeId);
        recoveryTaskId = Objects.requireNonNull(recoveryTaskId);
        workItemKey = requireText(workItemKey, 256);
        if (workItemKeyVersion == null
                || !workItemKeyVersion.matches("^k[1-9][0-9]*$")) {
            throw invalid();
        }
        if (episodeGeneration < 1 || expectedEpisodeAggregateVersion < 0
                || sourceVersion < 1
                || dependencyVersion < 1) {
            throw invalid();
        }
        sourceId = requireText(sourceId, 64);
        dependencyId = requireText(dependencyId, 64);
        batchId = Objects.requireNonNull(batchId);
        snapshotId = Objects.requireNonNull(snapshotId);
        snapshotHash = requireDigest(snapshotHash);
        qualityGateVersion = requireText(qualityGateVersion, 64);
        qualityGateDigest = requireDigest(qualityGateDigest);
        qmdpVersion = requireText(qmdpVersion, 64);
        qmdpDigest = requireDigest(qmdpDigest);
        qshmVersion = requireText(qshmVersion, 64);
        qshmDigest = requireDigest(qshmDigest);
        lineageId = Objects.requireNonNull(lineageId);
        if (lineageId.version() != 7 || lineageId.variant() != 2) throw invalid();
        watermark = requireText(watermark, 512);
        affectedRules = List.copyOf(Objects.requireNonNull(affectedRules)).stream()
                .sorted(Comparator.comparing(RuleVersionIdentity::ruleId)
                        .thenComparing(RuleVersionIdentity::ruleVersion))
                .toList();
        if (affectedRules.isEmpty()) throw invalid();
        eligibilityEvidence = List.copyOf(Objects.requireNonNull(eligibilityEvidence)).stream()
                .sorted(Comparator.comparing(value -> value.ruleVersion().ruleId()))
                .toList();
        formulaEvidence = List.copyOf(Objects.requireNonNull(formulaEvidence)).stream()
                .sorted(Comparator.comparing(QualityFuseFormulaBoundaryEvidence::formulaId))
                .toList();
        if (eligibilityEvidence.size() != affectedRules.size() || formulaEvidence.isEmpty()) {
            throw invalid();
        }
        fuseBusinessKey = requireText(fuseBusinessKey, 512);
        commandBodyDigest = requireDigest(commandBodyDigest);
        effectiveAt = Objects.requireNonNull(effectiveAt);
        occurredAt = Objects.requireNonNull(occurredAt);
    }

    private static String requireText(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalid();
        return value;
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_FUSE_TASK_PLAN_INVALID");
    }
}
