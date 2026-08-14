package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Self-contained Story 2.4 decision evidence carried into an episode/task handoff. */
public record QualityFuseEligibilityEvidence(
        UUID eligibilityId,
        String businessKey,
        long aggregateVersion,
        RuleVersionIdentity ruleVersion,
        String memberSetDigest,
        String registryVersion,
        String registryDigest,
        String dccVersion,
        String dccDigest,
        String ruleCatalogVersion,
        String ruleCatalogDigest,
        DependencyOperator operator,
        Integer threshold,
        QualityEligibilityStatus priorState,
        QualityEligibilityStatus evaluatedState,
        QualityEligibilityStatus appliedState,
        QualityEligibilityReason reason,
        List<QualityFuseMemberEvidence> members) {
    public QualityFuseEligibilityEvidence {
        if (eligibilityId == null || eligibilityId.version() != 7 || eligibilityId.variant() != 2
                || aggregateVersion < 1) throw invalid();
        ruleVersion = Objects.requireNonNull(ruleVersion);
        businessKey = text(businessKey, 256);
        registryVersion = text(registryVersion, 128);
        if (!businessKey.equals(ruleVersion.businessKey(registryVersion))) throw invalid();
        memberSetDigest = digest(memberSetDigest);
        registryDigest = digest(registryDigest);
        dccVersion = text(dccVersion, 128);
        dccDigest = digest(dccDigest);
        ruleCatalogVersion = text(ruleCatalogVersion, 128);
        ruleCatalogDigest = digest(ruleCatalogDigest);
        operator = Objects.requireNonNull(operator);
        if ((operator == DependencyOperator.THRESHOLD) != (threshold != null)) throw invalid();
        priorState = Objects.requireNonNull(priorState);
        evaluatedState = Objects.requireNonNull(evaluatedState);
        appliedState = Objects.requireNonNull(appliedState);
        reason = Objects.requireNonNull(reason);
        members = List.copyOf(Objects.requireNonNull(members)).stream()
                .sorted(Comparator.comparing(QualityFuseMemberEvidence::dependencyId))
                .toList();
        if (members.isEmpty()
                || new HashSet<>(members.stream().map(QualityFuseMemberEvidence::dependencyId)
                        .toList()).size() != members.size()
                || !memberSetDigest.equals(digestMembers(members))) throw invalid();
    }

    public static String digestMembers(List<QualityFuseMemberEvidence> members) {
        String material = List.copyOf(Objects.requireNonNull(members)).stream()
                .sorted(Comparator.comparing(QualityFuseMemberEvidence::dependencyId))
                .map(member -> String.join("\u001f", member.sourceId(),
                        Long.toString(member.sourceVersion()), member.dependencyId(),
                        Long.toString(member.dependencyVersion())))
                .collect(java.util.stream.Collectors.joining("\u001e"));
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalid();
        return value;
    }

    private static String digest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_FUSE_ELIGIBILITY_EVIDENCE_INVALID");
    }
}
