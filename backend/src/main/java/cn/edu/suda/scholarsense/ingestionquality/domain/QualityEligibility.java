package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record QualityEligibility(
        UUID eligibilityId,
        RuleVersionIdentity ruleVersion,
        String registryVersion,
        String registryDigest,
        String catalogVersion,
        String catalogDigest,
        String ruleCatalogVersion,
        String ruleCatalogDigest,
        long aggregateVersion,
        QualityEligibilityStatus status,
        QualityEligibilityReason reason,
        DependencyOperator operator,
        Integer threshold,
        List<QualityEligibilityMemberEvidence> members,
        List<String> failedMembers,
        Instant effectiveAt,
        Instant occurredAt,
        String traceId) {

    public QualityEligibility {
        eligibilityId = IngestionQualityDomainRules.requireUuidV7(eligibilityId);
        ruleVersion = Objects.requireNonNull(ruleVersion);
        if (!"RULE-DEPENDENCY-REGISTRY-1.0.0".equals(registryVersion)
                || !"DCC-1.1.0".equals(catalogVersion)
                || !"RC-1.0.0".equals(ruleCatalogVersion)) {
            throw invalid();
        }
        registryDigest = IngestionQualityDomainRules.requireSha256(registryDigest);
        catalogDigest = IngestionQualityDomainRules.requireSha256(catalogDigest);
        ruleCatalogDigest = IngestionQualityDomainRules.requireSha256(ruleCatalogDigest);
        aggregateVersion = IngestionQualityDomainRules.requireVersion(aggregateVersion);
        status = Objects.requireNonNull(status);
        reason = Objects.requireNonNull(reason);
        operator = Objects.requireNonNull(operator);
        members = List.copyOf(Objects.requireNonNull(members)).stream()
                .sorted(Comparator.comparing(QualityEligibilityMemberEvidence::dependencyId))
                .toList();
        if (members.isEmpty() || members.size() > 11
                || new HashSet<>(members.stream()
                        .map(QualityEligibilityMemberEvidence::dependencyId).toList()).size()
                        != members.size()) {
            throw invalid();
        }
        if (operator == DependencyOperator.THRESHOLD) {
            if (threshold == null || threshold < 1 || threshold > members.size()) {
                throw invalid();
            }
        } else if (threshold != null) {
            throw invalid();
        }
        failedMembers = List.copyOf(Objects.requireNonNull(failedMembers)).stream()
                .sorted().toList();
        Set<String> memberIds = Set.copyOf(
                members.stream().map(QualityEligibilityMemberEvidence::dependencyId).toList());
        if (new HashSet<>(failedMembers).size() != failedMembers.size()
                || !memberIds.containsAll(failedMembers)
                || (status == QualityEligibilityStatus.ELIGIBLE && !failedMembers.isEmpty())) {
            throw invalid();
        }
        effectiveAt = requireMicrosecond(effectiveAt);
        occurredAt = requireMicrosecond(occurredAt);
        if (effectiveAt.isAfter(occurredAt)) throw invalid();
        if (traceId == null || !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) {
            throw invalid();
        }
    }

    public String businessKey() {
        return ruleVersion.businessKey(registryVersion);
    }

    public QualityEligibility recovering(long expectedAggregateVersion, Instant at) {
        if (status != QualityEligibilityStatus.FUSED
                || aggregateVersion != expectedAggregateVersion
                || aggregateVersion == IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_VERSION_CONFLICT);
        }
        return new QualityEligibility(
                eligibilityId, ruleVersion, registryVersion, registryDigest, catalogVersion,
                catalogDigest, ruleCatalogVersion, ruleCatalogDigest, aggregateVersion + 1,
                QualityEligibilityStatus.RECOVERING,
                QualityEligibilityReason.RECOVERY_COMMAND_ACCEPTED, operator, threshold,
                members, failedMembers, effectiveAt, at, traceId);
    }

    private static Instant requireMicrosecond(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) throw invalid();
        return value;
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_ELIGIBILITY_INVALID);
    }
}
