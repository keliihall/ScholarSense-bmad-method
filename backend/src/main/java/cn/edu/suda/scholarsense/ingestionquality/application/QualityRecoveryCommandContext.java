package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.List;
import java.util.UUID;

/** Closed owner-local recovery context; all identifiers are opaque or controlled. */
public record QualityRecoveryCommandContext(
        UUID taskId,
        long taskVersion,
        UUID episodeId,
        long episodeVersion,
        long episodeGeneration,
        String sourceId,
        String dependencyId,
        List<AffectedRule> affectedRules,
        String affectedRuleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest,
        String currentState,
        String targetState) {
    public QualityRecoveryCommandContext {
        affectedRules = List.copyOf(affectedRules);
        if (taskId == null || episodeId == null || taskId.version() != 7
                || episodeId.version() != 7 || taskVersion < 1 || episodeVersion < 1
                || episodeGeneration < 1 || affectedRules.isEmpty()
                || !"fused".equals(currentState) || !"recovering".equals(targetState)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_RECOVERY_CONTEXT_INVALID");
        }
    }

    public record AffectedRule(String ruleId, String ruleVersion, String ruleVersionDigest) {}
}
