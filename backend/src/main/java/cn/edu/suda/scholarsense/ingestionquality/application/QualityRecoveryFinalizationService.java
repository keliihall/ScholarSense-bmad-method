package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalDecision;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalRequest;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalView;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorization;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionReconciliation;
import cn.edu.suda.scholarsense.identityaccess.api.RecoveryCheckerBindingResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Fresh D4 orchestration around the single V21 owner-local finalization transaction. */
public final class QualityRecoveryFinalizationService {
    private static final String HRAM_DIGEST =
            "sha256:96142286aa1da5633e77a8c435df37102744a61b573eae189ad44fe1e53251ec";
    private static final String HRAP_DIGEST =
            "sha256:1db5201136807c3bcf8fa62186414b7c79c062f595920012edd0001c77804900";
    private static final String RFP_DIGEST =
            "sha256:84191d8b844b31ef91fb051d34d6731b80981d6d193e42b0f8d19f73caa9ed25";

    private final QualityRecoveryFinalizationStorePort store;
    private final QualityRecoveryAuthorizationGuard authorization;
    private final RecoveryCheckerBindingResolver checkers;
    private final HighRiskApprovalPort approvals;
    private final HighRiskExecutionAuthorizationPort executions;
    private final RecoveryValidationTrustedTimePort time;
    private final Supplier<UUID> ids;

    public QualityRecoveryFinalizationService(
            QualityRecoveryFinalizationStorePort store,
            QualityRecoveryAuthorizationGuard authorization,
            RecoveryCheckerBindingResolver checkers,
            HighRiskApprovalPort approvals,
            HighRiskExecutionAuthorizationPort executions,
            RecoveryValidationTrustedTimePort time,
            Supplier<UUID> ids) {
        this.store = java.util.Objects.requireNonNull(store);
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.checkers = java.util.Objects.requireNonNull(checkers);
        this.approvals = java.util.Objects.requireNonNull(approvals);
        this.executions = java.util.Objects.requireNonNull(executions);
        this.time = java.util.Objects.requireNonNull(time);
        this.ids = java.util.Objects.requireNonNull(ids);
    }

    public QualityRecoveryFinalizationContext requestApproval(
            QualityRecoveryFinalizationCommand command,
            QualityRecoveryCommandActor actor,
            String traceId) {
        requireFinalConfirmer(actor);
        QualityRecoveryFinalizationContext context = ready(command);
        CompositeAuthorizationDecision decision = authorize(context, actor, traceId, false);
        RecoveryCheckerBindingResolver.Resolution checker = currentCheckers(
                context, null, traceId);
        String authContext = decisionDigest(decision);
        String authState = authenticationStateDigest(actor);
        String requestDigest = digest(String.join("\n",
                "QUALITY-RECOVERY-FINAL-APPROVAL-1.0.0", context.recoveryId().toString(),
                Long.toString(context.recoveryVersion()), context.taskId().toString(),
                Long.toString(context.taskVersion()), context.observationDecisionDigest(),
                context.finalPreviewDigest(), context.policyVersion(), context.policyDigest(),
                context.memberSetDigest(), context.watermarksDigest(),
                actor.naturalPersonPrincipalDigest(), authContext, authState,
                Long.toString(decision.decisionToken().invalidationVersion())));
        HighRiskApprovalView approval = approvals.request(new HighRiskApprovalRequest(
                ids.get(), requestDigest, "quality-fuse.recover",
                actor.naturalPersonPrincipalDigest(), authContext, authState,
                "RECOVERY_TASK", digest(context.taskId().toString()), context.taskVersion(),
                digest(context.sourceId()), context.impactScopeDigest(),
                "highly-sensitive-deidentified", "recovering", "eligible",
                "RECOVERY_FINALIZATION", "HRAM-1.0.0", HRAM_DIGEST,
                "HRAP-1.0.0", HRAP_DIGEST, "RFP-1.0.0", RFP_DIGEST,
                context.finalPreviewDigest(), checker.checkerSetDigest(),
                checker.checkerPrincipalDigests(),
                decision.decisionToken().invalidationVersion(), trustedNow(), traceId,
                command.idempotencyKey(), context.observationDecisionDigest(),
                context.memberSetDigest(), context.watermarksDigest(),
                context.policyVersion(), context.policyDigest()));
        return store.bindApproval(binding(
                context, approval, null, checker, actor.naturalPersonPrincipalDigest(),
                requestDigest, authContext, authState,
                decision.decisionToken().invalidationVersion(), traceId));
    }

    public QualityRecoveryFinalizationContext decide(
            UUID recoveryId,
            long expectedApprovalVersion,
            String decisionValue,
            String idempotencyKey,
            QualityRecoveryCommandActor actor,
            String traceId) {
        requireFinalConfirmer(actor);
        HighRiskApprovalDecision.Decision decision =
                HighRiskApprovalDecision.Decision.valueOf(decisionValue.toUpperCase());
        QualityRecoveryFinalizationContext context = load(recoveryId);
        String replayState = switch (decision) {
            case APPROVE -> "approval-approved";
            case REJECT -> "approval-rejected";
            case CANCEL -> "cancelled";
        };
        if (!("approval-pending".equals(context.finalizationState())
                || replayState.equals(context.finalizationState()))
                || context.approvalId() == null || context.approvalVersion() == null) {
            throw conflict();
        }
        authorize(context, actor, traceId, false);
        RecoveryCheckerBindingResolver.Resolution checker = currentCheckers(
                context, context.ownerBindingSetDigest(), traceId);
        if (!checker.checkerPrincipalDigests().contains(actor.naturalPersonPrincipalDigest())
                || !checker.checkerSetDigest().equals(context.checkerSetDigest())
                || !checker.naturalPersonBindingSetDigest().equals(
                        context.checkerPersonSetDigest())) {
            throw conflict();
        }
        HighRiskApprovalView approval = approvals.decide(new HighRiskApprovalDecision(
                context.approvalId(), expectedApprovalVersion,
                decision,
                actor.naturalPersonPrincipalDigest(), checker.checkerSetDigest(),
                context.authorizationGeneration(), trustedNow(), context.traceId(),
                idempotencyKey));
        return store.bindApproval(binding(
                context, approval, approval.receiptDigest(), checker,
                context.makerPrincipalDigest(), context.requestDigest(),
                context.authorizationContextDigest(), context.authenticationStateDigest(),
                context.authorizationGeneration(), context.traceId()));
    }

    public QualityRecoveryFinalizationCommit execute(
            QualityRecoveryFinalizationCommand command,
            QualityRecoveryCommandActor actor,
            String traceId) {
        requireFinalConfirmer(actor);
        QualityRecoveryFinalizationContext context = load(command.recoveryId());
        authorize(context, actor, traceId, false);
        String idempotencyKeyDigest = digest(command.idempotencyKey());
        String clientCommandDigest = clientCommandDigest(command);
        Optional<QualityRecoveryFinalizationCommit> replay = store.findReplay(
                idempotencyKeyDigest, clientCommandDigest, command.recoveryId(),
                command.finalObservationWatermark());
        if (replay.isPresent()) return replay.get();
        if (context.recoveryVersion() != command.expectedRecoveryVersion()
                || context.taskVersion() != command.expectedTaskVersion()
                || !context.finalObservationWatermark().equals(
                        command.finalObservationWatermark())
                || !("approval-approved".equals(context.finalizationState())
                    || "executed".equals(context.finalizationState()))
                || context.approvalId() == null || context.approvalVersion() == null
                || context.approvalReceiptDigest() == null) {
            throw conflict();
        }
        CompositeAuthorizationDecision decision = authorize(context, actor, traceId, true);
        RecoveryCheckerBindingResolver.Resolution checker = currentCheckers(
                context, context.ownerBindingSetDigest(), traceId);
        if (!checker.checkerSetDigest().equals(context.checkerSetDigest())
                || !checker.naturalPersonBindingSetDigest().equals(
                        context.checkerPersonSetDigest())
                || decision.decisionToken().invalidationVersion()
                        != context.authorizationGeneration()
                || !decisionDigest(decision).equals(context.authorizationContextDigest())
                || !authenticationStateDigest(actor).equals(
                        context.authenticationStateDigest())) {
            throw conflict();
        }
        String issuanceDigest = digest(String.join("\n", context.requestDigest(),
                context.approvalId().toString(), Long.toString(context.approvalVersion()),
                context.approvalReceiptDigest(), context.finalPreviewDigest(),
                context.observationDecisionDigest(), command.idempotencyKey()));
        HighRiskExecutionAuthorization lease = executions.issueOrReplay(
                new HighRiskExecutionAuthorizationRequest(
                        context.approvalId(), context.approvalVersion(),
                        context.approvalReceiptDigest(), context.requestDigest(),
                        "quality-fuse.recover", "RECOVERY_TASK",
                        digest(context.taskId().toString()), context.taskVersion(),
                        digest(context.sourceId()), context.impactScopeDigest(),
                        context.finalPreviewDigest(), context.authorizationContextDigest(),
                        context.authenticationStateDigest(), context.checkerSetDigest(),
                        context.authorizationGeneration(), "ingestion-quality",
                        digest(command.idempotencyKey() + ":final-lease"), issuanceDigest,
                        trustedNow(), context.traceId()));
        if ("issued".equals(lease.state())) {
            lease = executions.reserve(lease, trustedNow());
        } else if (!"reserved".equals(lease.state()) && !"executed".equals(lease.state())) {
            throw new IllegalStateException("QUALITY_FINALIZATION_LEASE_INVALID");
        }
        if (!executions.verify(lease)) throw unavailable();
        Map<String, Object> ownerCommand = new LinkedHashMap<>();
        ownerCommand.put("recoveryId", context.recoveryId().toString());
        ownerCommand.put("expectedRecoveryVersion", command.expectedRecoveryVersion());
        ownerCommand.put("expectedTaskVersion", command.expectedTaskVersion());
        ownerCommand.put("expectedEpisodeVersion", context.episodeVersion());
        ownerCommand.put("finalObservationWatermark", command.finalObservationWatermark());
        ownerCommand.put("clientCommandDigest", clientCommandDigest);
        ownerCommand.put("requestDigest", context.requestDigest());
        ownerCommand.put("finalPreviewDigest", context.finalPreviewDigest());
        ownerCommand.put("observationDecisionDigest", context.observationDecisionDigest());
        ownerCommand.put("policyDigest", context.policyDigest());
        ownerCommand.put("memberSetDigest", context.memberSetDigest());
        ownerCommand.put("watermarksDigest", context.watermarksDigest());
        ownerCommand.put("authorizationGeneration", context.authorizationGeneration());
        ownerCommand.put("approvalId", context.approvalId().toString());
        ownerCommand.put("approvalVersion", context.approvalVersion());
        ownerCommand.put("approvalReceiptDigest", context.approvalReceiptDigest());
        ownerCommand.put("leaseId", lease.leaseId().toString());
        ownerCommand.put("leaseVersion", lease.leaseVersion());
        ownerCommand.put("leaseDigest", lease.leaseDigest());
        ownerCommand.put("leaseState", "reserved");
        ownerCommand.put("leaseIssuer", lease.issuer());
        ownerCommand.put("leaseAudience", lease.audience());
        ownerCommand.put("executionJti", lease.executionJti().toString());
        ownerCommand.put("authorizedUntil", lease.authorizedUntil().toString());
        ownerCommand.put("rootEventId", lease.executionJti().toString());
        ownerCommand.put("traceId", traceId);
        QualityRecoveryFinalizationCommit committed = store.execute(
                idempotencyKeyDigest, ownerCommand);
        executions.reconcile(new HighRiskExecutionReconciliation(
                lease.leaseId(), lease.leaseVersion(), lease.leaseDigest(),
                lease.executionJti(), context.requestDigest(),
                "iq-finalization:" + lease.executionJti(),
                committed.recoveryCompletedAt(), committed.ownerResultDigest(),
                committed.confirmationOutboxEventId(), reconciliationDigest(
                    lease, context.requestDigest(), committed, context.traceId()),
                context.traceId()));
        return committed;
    }

    private QualityRecoveryFinalizationContext ready(
            QualityRecoveryFinalizationCommand command) {
        QualityRecoveryFinalizationContext context = load(command.recoveryId());
        if (context.recoveryVersion() != command.expectedRecoveryVersion()
                || context.taskVersion() != command.expectedTaskVersion()
                || !"open".equals(context.taskStatus()) || !context.episodeActive()
                || !"ready".equals(context.observationStatus())
                || !("not-requested".equals(context.finalizationState())
                    || "approval-pending".equals(context.finalizationState())
                    || "approval-rejected".equals(context.finalizationState())
                    || "cancelled".equals(context.finalizationState()))
                || !context.finalObservationWatermark().equals(
                        command.finalObservationWatermark())) {
            throw conflict();
        }
        return context;
    }

    private CompositeAuthorizationDecision authorize(
            QualityRecoveryFinalizationContext context,
            QualityRecoveryCommandActor actor,
            String traceId,
            boolean recheck) {
        CompositeAuthorizationRequest request = new CompositeAuthorizationRequest(
                actor.sessionPseudonym(), "RECOVERY_TASK", "quality-fuse.recover",
                digestHex(context.taskId().toString()), context.taskVersion(),
                Optional.empty(), Optional.empty(), traceId);
        CompositeAuthorizationDecision result = authorization.authorize(request);
        if (recheck) authorization.recheck(request, result);
        return result;
    }

    private RecoveryCheckerBindingResolver.Resolution currentCheckers(
            QualityRecoveryFinalizationContext context,
            String expectedOwnerBindingSetDigest,
            String traceId) {
        List<String> rules = context.affectedRules().stream()
                .map(QualityRecoveryFinalizationContext.AffectedRule::ruleVersionDigest)
                .toList();
        RecoveryCheckerBindingResolver.Resolution resolution = checkers.resolve(
                rules, expectedOwnerBindingSetDigest, trustedNow(), traceId);
        if (!resolution.available()) throw unavailable();
        return resolution;
    }

    private static Map<String, Object> binding(
            QualityRecoveryFinalizationContext context,
            HighRiskApprovalView approval,
            String receiptDigest,
            RecoveryCheckerBindingResolver.Resolution checker,
            String makerPrincipalDigest,
            String requestDigest,
            String authorizationContextDigest,
            String authenticationStateDigest,
            long authorizationGeneration,
            String traceId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("recoveryId", context.recoveryId().toString());
        value.put("approvalId", approval.approvalId().toString());
        value.put("approvalVersion", approval.approvalVersion());
        value.put("approvalStatus", approval.status());
        value.put("approvalReceiptDigest", receiptDigest);
        value.put("requestDigest", requestDigest);
        value.put("finalPreviewDigest", context.finalPreviewDigest());
        value.put("observationDecisionDigest", context.observationDecisionDigest());
        value.put("policyDigest", context.policyDigest());
        value.put("memberSetDigest", context.memberSetDigest());
        value.put("watermarksDigest", context.watermarksDigest());
        value.put("checkerSetDigest", checker.checkerSetDigest());
        value.put("ownerBindingSetDigest", checker.ownerBindingSetDigest());
        value.put("checkerPersonSetDigest", checker.naturalPersonBindingSetDigest());
        value.put("authorizationContextDigest", authorizationContextDigest);
        value.put("authenticationStateDigest", authenticationStateDigest);
        value.put("authorizationGeneration", authorizationGeneration);
        value.put("makerPrincipalDigest", makerPrincipalDigest);
        value.put("traceId", traceId);
        return value;
    }

    private QualityRecoveryFinalizationContext load(UUID recoveryId) {
        return store.load(recoveryId).orElseThrow(
                () -> new IllegalStateException("QUALITY_FINALIZATION_NOT_FOUND"));
    }

    private Instant trustedNow() {
        Instant value = java.util.Objects.requireNonNull(time.now());
        if (value.getNano() % 1_000 != 0) throw unavailable();
        return value;
    }

    private static void requireFinalConfirmer(QualityRecoveryCommandActor actor) {
        if (actor == null || !actor.roleIds().contains("R6-DATA-OWNER")) {
            throw new IllegalStateException("QUALITY_FINALIZATION_NOT_FOUND");
        }
    }

    private static String decisionDigest(CompositeAuthorizationDecision value) {
        var token = value.decisionToken();
        return digest(String.join("\n", value.policyVersion(),
                Long.toString(token.identityVersion()), Long.toString(token.relationVersion()),
                Long.toString(token.grantVersion()), Long.toString(token.invalidationVersion()),
                Long.toString(token.policySequence()), Long.toString(token.objectVersion())));
    }

    private static String authenticationStateDigest(QualityRecoveryCommandActor actor) {
        return digest(String.join("\n", actor.sessionPseudonym(),
                Long.toString(actor.sessionVersion()), actor.sessionExpiresAt().toString(),
                actor.identityProfileVersion()));
    }

    private static String clientCommandDigest(QualityRecoveryFinalizationCommand command) {
        return digest(String.join("\n", "QUALITY-RECOVERY-FINAL-COMMAND-1.0.0",
                command.recoveryId().toString(),
                Long.toString(command.expectedRecoveryVersion()),
                Long.toString(command.expectedTaskVersion()),
                command.finalObservationWatermark()));
    }

    private static String reconciliationDigest(
            HighRiskExecutionAuthorization lease,
            String requestDigest,
            QualityRecoveryFinalizationCommit commit,
            String traceId) {
        return digest(String.join("\n", lease.leaseId().toString(),
                Long.toString(lease.leaseVersion()), lease.leaseDigest(),
                lease.executionJti().toString(), requestDigest,
                "iq-finalization:" + lease.executionJti(),
                commit.recoveryCompletedAt().toString(), commit.ownerResultDigest(),
                commit.confirmationOutboxEventId().toString(), traceId));
    }

    private static String digest(String value) {
        return "sha256:" + digestHex(value);
    }

    private static String digestHex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("QUALITY_FINALIZATION_CONFLICT");
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("QUALITY_FINALIZATION_DEPENDENCY_UNAVAILABLE");
    }
}
