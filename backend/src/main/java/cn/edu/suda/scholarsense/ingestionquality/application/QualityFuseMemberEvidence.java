package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyQualityState;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyMember;
import java.util.Objects;

/** Full registry definition plus the exact member state used in one eligibility decision. */
public record QualityFuseMemberEvidence(
        String sourceId,
        String sourceContractVersion,
        long sourceVersion,
        String dependencyId,
        String dependencyContractVersion,
        long dependencyVersion,
        DependencyRequirement requirement,
        String compositionGroup,
        QualityEligibilityStatus state,
        boolean versionContinuous,
        String watermark,
        boolean failed) {
    public QualityFuseMemberEvidence {
        Objects.requireNonNull(new RuleDependencyMember(
                sourceId, sourceContractVersion, dependencyId, dependencyContractVersion,
                requirement, compositionGroup));
        if (sourceVersion < 1 || dependencyVersion < 1) throw invalid();
        state = Objects.requireNonNull(state);
        if (watermark == null || watermark.isBlank() || watermark.length() > 512) throw invalid();
    }

    public static QualityFuseMemberEvidence from(
            RuleDependencyMember definition,
            DependencyQualityState current,
            boolean failed) {
        return new QualityFuseMemberEvidence(
                definition.sourceId(), definition.sourceVersion(),
                current == null ? 1 : current.sourceVersion(),
                definition.dependencyId(), definition.dependencyVersion(),
                current == null ? 1 : current.dependencyVersion(),
                definition.requirement(), definition.compositionGroup(),
                current == null ? QualityEligibilityStatus.MISSING : current.status(),
                current != null && current.versionContinuous(),
                current == null ? "missing" : current.watermark(), failed);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_FUSE_MEMBER_EVIDENCE_INVALID");
    }
}
