package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDependencyWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSourceVector;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds the same self-contained responsibility fact for shadow replay and live publishing. */
public final class ResponsibilityInvalidationFactFactory {
    private static final Duration RETENTION = Duration.ofDays(2190);

    private ResponsibilityInvalidationFactFactory() {}

    public static AccessInvalidationFact create(
            NormalizedResponsibilityBatch batch,
            List<ResponsibilityScopeProjectionUpdate> scopeUpdates,
            AuthoritativeResponsibilityRelation relation,
            long aggregateVersion) {
        ResponsibilityScopeProjectionUpdate scope = scopeUpdates.stream()
                .filter(update -> update.studentSourceRefDigest().equals(
                        relation.studentSourceReference().equivalenceDomain()))
                .filter(update -> update.resultingRelations().stream()
                        .anyMatch(candidate -> candidate.relationRefToken()
                                .equals(relation.relationRefToken())))
                .findFirst()
                .orElseThrow(() -> new IdentitySyncException(
                        "RESPONSIBILITY_SCOPE_PROJECTION_MISSING"));
        return new AccessInvalidationFact(
                relation.relationId(),
                batch.traceId(),
                relation.changeKind(),
                relation.changeReason(),
                relation.lineageId(),
                relation.supersedesId(),
                null,
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                relation.lineageId().value(),
                aggregateVersion,
                aggregateVersion,
                relation.changeEffectiveAt(),
                sourceVector(batch),
                new AccessInvalidationSubjectSnapshot(
                        "subtok_" + relation.studentSourceReference().digest(),
                        "scptok_" + relation.payloadDigest(),
                        relation.payloadDigest(),
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                authorization(scope, relation),
                new AccessInvalidationRetention(
                        "restricted",
                        "RS-1.0.0",
                        relation.changeEffectiveAt().plus(RETENTION),
                        false),
                relation.payloadDigest());
    }

    private static AccessInvalidationSourceVector sourceVector(
            NormalizedResponsibilityBatch batch) {
        List<AccessInvalidationDependencyWatermark> dependencies =
                new ArrayList<>();
        batch.supportingIdentityOrgWatermarks().forEach((route, watermark) -> {
            String[] parts = route.split("\\|", -1);
            dependencies.add(new AccessInvalidationDependencyWatermark(
                    parts[0], parts[1], watermark));
        });
        dependencies.add(new AccessInvalidationDependencyWatermark(
                batch.key().feedId(),
                batch.key().partitionId(),
                batch.toWatermark()));
        return new AccessInvalidationSourceVector(
                batch.key().sourceId(),
                batch.sourceVersion(),
                batch.toWatermark(),
                dependencies);
    }

    private static AccessInvalidationAuthorizationSnapshot authorization(
            ResponsibilityScopeProjectionUpdate scope,
            AuthoritativeResponsibilityRelation relation) {
        var decision = scope.decision();
        ResponsibilityRecipientEvidence evidence = scope.recipientEvidence()
                .stream()
                .filter(candidate -> candidate.relation().relationRefToken()
                        .equals(relation.relationRefToken()))
                .findFirst()
                .orElseThrow(() -> new IdentitySyncException(
                        "RESPONSIBILITY_V2_AUTHORIZATION_EVIDENCE_MISSING"));
        boolean relationEffective = relation.status() == ResponsibilityStatus.ACTIVE
                && relation.effectiveInterval().contains(
                        relation.changeEffectiveAt());
        boolean accountActive = evidence.accountActive();
        boolean r1Valid = evidence.r1EmploymentActive();
        boolean collegeActive = evidence.collegeActive();
        boolean recovery = relation.changeKind()
                == AccessInvalidationChangeKind.REVALIDATED;
        boolean selectedByValidDecision = decision.validity()
                        == ResponsibilityRecipientValidity.VALID
                && Objects.equals(
                        decision.counselorAccountId(),
                        evidence.counselorAccountId())
                && Objects.equals(
                        decision.collegeOrganizationId(),
                        evidence.collegeOrganizationId())
                && decision.sourceVersion() == relation.sourceVersion()
                && decision.sourceWatermark() == relation.sourceWatermark()
                && decision.aggregateVersion() == relation.aggregateVersion();
        if (recovery
                && (!accountActive
                        || !r1Valid
                        || !collegeActive
                        || !evidence.collegeMatchesEmployment()
                        || !relationEffective
                        || !selectedByValidDecision)) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_RECOVERY_EVIDENCE_INVALID");
        }
        return new AccessInvalidationAuthorizationSnapshot(
                recovery
                        ? AccessInvalidationAuthorizationState.REVALIDATED
                        : AccessInvalidationAuthorizationState.INVALIDATED,
                accountActive,
                r1Valid,
                collegeActive,
                relationEffective,
                "RFP-1.0.0");
    }
}
