package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeFreshness;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeQuery;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeValidity;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeView;
import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationFenceQueryPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityCheckpoint;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityRecipientEvidencePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityScopeReadBackPort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityScopeReadBack;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvaluator;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientReason;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Current responsibility scope read model; it intentionally does not decide authorization. */
public final class ResponsibilityScopeQueryAdapter
        implements ResponsibilityScopeQueryPort,
                ResponsibilityScopeReadBackPort {
    private final ResponsibilitySyncRepository repository;
    private final ResponsibilityRecipientEvidencePort evidence;
    private final TrustedTimeSource time;
    private final CheckpointKey key;
    private final AccessInvalidationFenceQueryPort invalidationFence;

    public ResponsibilityScopeQueryAdapter(
            ResponsibilitySyncRepository repository,
            ResponsibilityRecipientEvidencePort evidence,
            TrustedTimeSource time,
            CheckpointKey key) {
        this(
                repository,
                evidence,
                time,
                key,
                AccessInvalidationFenceQueryPort.noOp());
    }

    public ResponsibilityScopeQueryAdapter(
            ResponsibilitySyncRepository repository,
            ResponsibilityRecipientEvidencePort evidence,
            TrustedTimeSource time,
            CheckpointKey key,
            AccessInvalidationFenceQueryPort invalidationFence) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.evidence = java.util.Objects.requireNonNull(evidence);
        this.time = java.util.Objects.requireNonNull(time);
        this.invalidationFence =
                java.util.Objects.requireNonNull(invalidationFence);
        if (key == null
                || !"responsibility".equals(
                        key.consumerProjection())) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_ROUTE_INVALID");
        }
        this.key = key;
    }

    @Override
    public ResponsibilityScopeView query(
            ResponsibilityScopeQuery query) {
        Instant now;
        try {
            now = time.now().instant();
        } catch (RuntimeException unavailableClock) {
            return ResponsibilityScopeView.unavailable(
                    query.studentSourceRefDigest(),
                    ResponsibilityRecipientReason.CLOCK_UNAVAILABLE.code(),
                    query.serverNow());
        }
        Evaluation evaluation =
                evaluate(query.studentSourceRefDigest(), now);
        ResponsibilityRecipientDecision decision =
                evaluation.decision();
        if (decision.validity()
                == ResponsibilityRecipientValidity.DEPENDENCY_UNAVAILABLE) {
            return ResponsibilityScopeView.unavailable(
                    query.studentSourceRefDigest(),
                    decision.reason().code(),
                    now);
        }
        long sourceVersion = evaluation.relations().stream()
                .mapToLong(
                        AuthoritativeResponsibilityRelation
                                ::sourceVersion)
                .max()
                .orElse(0);
        long sourceWatermark = evaluation.relations().stream()
                .mapToLong(
                        AuthoritativeResponsibilityRelation
                                ::sourceWatermark)
                .max()
                .orElse(0);
        long aggregateVersion = evaluation.relations().stream()
                .mapToLong(
                        AuthoritativeResponsibilityRelation
                                ::aggregateVersion)
                .max()
                .orElse(0);
        if (decision.validity()
                == ResponsibilityRecipientValidity.INVALID) {
            return new ResponsibilityScopeView(
                    query.studentSourceRefDigest(),
                    null,
                    null,
                    sourceVersion,
                    sourceWatermark,
                    aggregateVersion,
                    null,
                    null,
                    ResponsibilityScopeValidity.INVALID,
                    ResponsibilityScopeFreshness.FRESH,
                    decision.reason().code(),
                    "RESPONSIBILITY-AUTHORITY-1.0.0",
                    now);
        }
        AuthoritativeResponsibilityRelation relation =
                evaluation.evidence().stream()
                        .filter(candidate -> decision
                                .counselorAccountId()
                                .equals(candidate
                                        .counselorAccountId()))
                        .map(ResponsibilityRecipientEvidence::relation)
                        .findFirst()
                        .orElseThrow(() -> new IdentitySyncException(
                                "RESPONSIBILITY_SCOPE_READBACK_MISMATCH"));
        return new ResponsibilityScopeView(
                query.studentSourceRefDigest(),
                decision.counselorAccountId(),
                decision.collegeOrganizationId(),
                decision.sourceVersion(),
                decision.sourceWatermark(),
                decision.aggregateVersion(),
                relation.effectiveInterval().effectiveFrom(),
                relation.effectiveInterval().effectiveTo(),
                ResponsibilityScopeValidity.VALID,
                ResponsibilityScopeFreshness.FRESH,
                decision.reason().code(),
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                now);
    }

    @Override
    public ResponsibilityScopeReadBack readBack(
            String studentSourceRefDigest, Instant serverNow) {
        try {
            Instant now = time.now().instant();
            Evaluation evaluation =
                    evaluate(studentSourceRefDigest, now);
            ResponsibilityRecipientDecision decision =
                    evaluation.decision();
            long sourceVersion = evaluation.relations().stream()
                    .mapToLong(
                            AuthoritativeResponsibilityRelation
                                    ::sourceVersion)
                    .max()
                    .orElse(0);
            long sourceWatermark = evaluation.relations().stream()
                    .mapToLong(
                            AuthoritativeResponsibilityRelation
                                    ::sourceWatermark)
                    .max()
                    .orElse(0);
            long aggregateVersion = evaluation.relations().stream()
                    .mapToLong(
                            AuthoritativeResponsibilityRelation
                                    ::aggregateVersion)
                    .max()
                    .orElse(0);
            return new ResponsibilityScopeReadBack(
                    decision.validity(),
                    decision.reason().code(),
                    sourceVersion,
                    sourceWatermark,
                    aggregateVersion,
                    now);
        } catch (RuntimeException unavailable) {
            return new ResponsibilityScopeReadBack(
                    ResponsibilityRecipientValidity
                            .DEPENDENCY_UNAVAILABLE,
                    ResponsibilityRecipientReason
                            .DEPENDENCY_WATERMARK_BEHIND
                            .code(),
                    0,
                    0,
                    0,
                    serverNow);
        }
    }

    @Override
    public ResponsibilityScopeReadBack readBack(
            AccessInvalidationLineageId accessLineageId,
            UUID counselorAccountId,
            String studentSourceRefDigest,
            Instant serverNow) {
        java.util.Objects.requireNonNull(
                accessLineageId, "accessLineageId");
        java.util.Objects.requireNonNull(
                counselorAccountId, "counselorAccountId");
        try {
            Instant now = time.now().instant();
            Evaluation evaluation = evaluateCascadeScope(
                    accessLineageId,
                    counselorAccountId,
                    studentSourceRefDigest,
                    now);
            ResponsibilityRecipientDecision decision =
                    evaluation.decision();
            AuthoritativeResponsibilityRelation relation =
                    evaluation.relations().stream()
                            .findFirst()
                            .orElse(null);
            return new ResponsibilityScopeReadBack(
                    decision.validity(),
                    decision.reason().code(),
                    relation == null ? 0 : relation.sourceVersion(),
                    relation == null ? 0 : relation.sourceWatermark(),
                    relation == null ? 0 : relation.aggregateVersion(),
                    now);
        } catch (RuntimeException unavailable) {
            return new ResponsibilityScopeReadBack(
                    ResponsibilityRecipientValidity
                            .DEPENDENCY_UNAVAILABLE,
                    ResponsibilityRecipientReason
                            .DEPENDENCY_WATERMARK_BEHIND
                            .code(),
                    0,
                    0,
                    0,
                    serverNow);
        }
    }

    private Evaluation evaluateCascadeScope(
            AccessInvalidationLineageId accessLineageId,
            UUID counselorAccountId,
            String studentSourceRefDigest,
            Instant now) {
        IdentityCheckpoint checkpoint =
                repository.checkpoint(key).orElse(null);
        if (checkpoint == null
                || checkpoint.freshness()
                        != IdentityProjectionFreshness.FRESH) {
            return unavailable(now);
        }
        AuthoritativeResponsibilityRelation relation = repository
                .currentCascadeScope(
                        key,
                        accessLineageId,
                        counselorAccountId,
                        studentSourceRefDigest,
                        now)
                .orElse(null);
        if (relation == null) {
            return unavailable(now);
        }
        List<AuthoritativeResponsibilityRelation> relations =
                List.of(relation);
        List<ResponsibilityRecipientEvidence> resolved =
                evidence.resolve(relations, now);
        if (resolved.size() != 1
                || !counselorAccountId.equals(
                        resolved.getFirst().counselorAccountId())) {
            return unavailable(now);
        }
        ResponsibilityRecipientDecision decision =
                ResponsibilityRecipientEvaluator.evaluate(
                        resolved, now, true, true);
        if (decision.validity()
                        == ResponsibilityRecipientValidity.VALID
                && invalidationFence.blocks(accessLineageId)) {
            decision = ResponsibilityRecipientDecision.invalid(
                    ResponsibilityRecipientReason
                            .RECONCILIATION_DIFFERENCES);
        }
        return new Evaluation(relations, resolved, decision);
    }

    private static Evaluation unavailable(Instant now) {
        return new Evaluation(
                List.of(),
                List.of(),
                ResponsibilityRecipientEvaluator.evaluate(
                        List.of(), now, false, true));
    }

    private Evaluation evaluate(
            String studentSourceRefDigest, Instant now) {
        try {
            IdentityCheckpoint checkpoint =
                    repository.checkpoint(key).orElse(null);
            if (checkpoint == null
                    || checkpoint.freshness()
                            != IdentityProjectionFreshness.FRESH) {
                return new Evaluation(
                        List.of(),
                        List.of(),
                        ResponsibilityRecipientEvaluator.evaluate(
                                List.of(), now, false, true));
            }
            List<AuthoritativeResponsibilityRelation> relations =
                    repository.currentByStudentDigest(
                            key, studentSourceRefDigest, now);
            if (!repository.qualityGateTrusted(
                    key, studentSourceRefDigest)) {
                return new Evaluation(
                        relations,
                        List.of(),
                        ResponsibilityRecipientDecision.invalid(
                                ResponsibilityRecipientReason
                                        .RECONCILIATION_DIFFERENCES));
            }
            List<ResponsibilityRecipientEvidence> resolved =
                    evidence.resolve(relations, now);
            ResponsibilityRecipientDecision decision =
                    ResponsibilityRecipientEvaluator.evaluate(
                            resolved, now, true, true);
            if (decision.validity()
                            == ResponsibilityRecipientValidity.VALID
                    && relations.stream()
                            .map(AuthoritativeResponsibilityRelation::lineageId)
                            .filter(java.util.Objects::nonNull)
                            .anyMatch(invalidationFence::blocks)) {
                decision = ResponsibilityRecipientDecision.invalid(
                        ResponsibilityRecipientReason
                                .RECONCILIATION_DIFFERENCES);
            }
            return new Evaluation(
                    relations,
                    resolved,
                    decision);
        } catch (IdentitySyncException unavailable) {
            return new Evaluation(
                    List.of(),
                    List.of(),
                    ResponsibilityRecipientEvaluator.evaluate(
                            List.of(), now, false, true));
        } catch (RuntimeException unavailable) {
            return new Evaluation(
                    List.of(),
                    List.of(),
                    ResponsibilityRecipientEvaluator.evaluate(
                            List.of(), now, false, true));
        }
    }

    private record Evaluation(
            List<AuthoritativeResponsibilityRelation> relations,
            List<ResponsibilityRecipientEvidence> evidence,
            ResponsibilityRecipientDecision decision) {}
}
