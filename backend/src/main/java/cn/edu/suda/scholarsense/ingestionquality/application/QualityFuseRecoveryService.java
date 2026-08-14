package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
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

/** Server-side request/D4/lease orchestration; clients never submit trusted approval evidence. */
public final class QualityFuseRecoveryService {
    public static final String QRP_DIGEST =
            "sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366";
    private static final String HRAM_DIGEST =
            "sha256:96142286aa1da5633e77a8c435df37102744a61b573eae189ad44fe1e53251ec";
    private static final String HRAP_DIGEST =
            "sha256:1db5201136807c3bcf8fa62186414b7c79c062f595920012edd0001c77804900";
    private static final String RFP_DIGEST =
            "sha256:84191d8b844b31ef91fb051d34d6731b80981d6d193e42b0f8d19f73caa9ed25";

    private final QualityRecoveryCommandStorePort store;
    private final CompositeAuthorizationPort authorization;
    private final QualityRecoveryAuthorizationGuard recoveryAuthorization;
    private final RecoveryCheckerBindingResolver checkers;
    private final HighRiskApprovalPort approvals;
    private final HighRiskExecutionAuthorizationPort executionAuthorizations;
    private final RecoveryValidationTrustedTimePort time;
    private final Supplier<UUID> ids;

    public QualityFuseRecoveryService(
            QualityRecoveryCommandStorePort store,
            CompositeAuthorizationPort authorization,
            QualityRecoveryAuthorizationGuard recoveryAuthorization,
            RecoveryCheckerBindingResolver checkers,
            HighRiskApprovalPort approvals,
            HighRiskExecutionAuthorizationPort executionAuthorizations,
            RecoveryValidationTrustedTimePort time,
            Supplier<UUID> ids) {
        this.store = java.util.Objects.requireNonNull(store);
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.recoveryAuthorization = java.util.Objects.requireNonNull(recoveryAuthorization);
        this.checkers = java.util.Objects.requireNonNull(checkers);
        this.approvals = java.util.Objects.requireNonNull(approvals);
        this.executionAuthorizations = java.util.Objects.requireNonNull(executionAuthorizations);
        this.time = java.util.Objects.requireNonNull(time);
        this.ids = java.util.Objects.requireNonNull(ids);
    }

    public QualityRecoveryRequestState request(
            UUID taskId, long expectedTaskVersion, String reasonCode,
            String idempotencyKey, QualityRecoveryCommandActor actor, String traceId) {
        if (!actor.roleIds().contains("R6-DATA-OWNER")
                || !"QUALITY_EVIDENCE_REVALIDATION_REQUESTED".equals(reasonCode)) deny();
        QualityRecoveryCommandContext context = store.loadContext(taskId).orElseThrow(
                QualityFuseRecoveryService::notFound);
        if (context.taskVersion() != expectedTaskVersion) conflict(context.taskVersion());
        CompositeAuthorizationDecision read = authorizeRead(context, actor, traceId);
        Instant now = trustedNow();
        UUID requestId = ids.get();
        String maker = actor.naturalPersonPrincipalDigest();
        String authContext = decisionDigest(read);
        String authState = digest(String.join("\n", actor.sessionPseudonym(),
                Long.toString(actor.sessionVersion()), actor.sessionExpiresAt().toString(),
                actor.identityProfileVersion()));
        String scopeDigest = digest(context.sourceId());
        String idempotencyInputDigest = digest(String.join("\n", taskId.toString(),
                Long.toString(expectedTaskVersion), reasonCode, maker));
        String selectionSeed = digest(requestId + "\n" + context.memberSetDigest());
        long generation = read.decisionToken().invalidationVersion();
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("recoveryRequestId", requestId.toString());
        aggregate.put("requestVersion", 1L);
        aggregate.put("status", "requested");
        aggregate.put("actionType", "quality-fuse.recover");
        aggregate.put("currentState", "fused");
        aggregate.put("targetState", "recovering");
        aggregate.put("taskId", context.taskId().toString());
        aggregate.put("taskVersion", context.taskVersion());
        aggregate.put("episodeId", context.episodeId().toString());
        aggregate.put("episodeVersion", context.episodeVersion());
        aggregate.put("episodeGeneration", context.episodeGeneration());
        aggregate.put("sourceId", context.sourceId());
        aggregate.put("dependencyId", context.dependencyId());
        aggregate.put("affectedRuleVersionsDigest", context.affectedRuleVersionsDigest());
        aggregate.put("memberSetDigest", context.memberSetDigest());
        aggregate.put("watermarksDigest", context.watermarksDigest());
        aggregate.put("qrpVersion", "QRP-1.0.0");
        aggregate.put("qrpDigest", QRP_DIGEST);
        aggregate.put("makerPrincipalDigest", maker);
        aggregate.put("makerPersonBindingSetDigest", actor.naturalPersonBindingSetDigest());
        aggregate.put("authorizationContextDigest", authContext);
        aggregate.put("authenticationStateDigest", authState);
        aggregate.put("authorizationGeneration", generation);
        aggregate.put("scopeDigest", scopeDigest);
        aggregate.put("impactScopeDigest", context.affectedRuleVersionsDigest());
        aggregate.put("selectionSeed", selectionSeed);
        aggregate.put("reasonCode", reasonCode);
        aggregate.put("idempotencyInputDigest", idempotencyInputDigest);
        aggregate.put("requestedAt", now.toString());
        aggregate.put("traceId", traceId);
        String requestDigest = digest(canonical(aggregate));
        aggregate.put("requestDigest", requestDigest);
        UUID jobId = ids.get();
        return store.submitRequestWithValidationJob(
                digest(idempotencyKey), aggregate,
                digest(idempotencyKey + ":validation"), jobId);
    }

    private QualityRecoveryRequestState state(UUID requestId) {
        return store.findRequest(requestId).orElseThrow(QualityFuseRecoveryService::notFound);
    }

    public QualityRecoveryRequestState status(
            UUID requestId, QualityRecoveryCommandActor actor, String traceId) {
        QualityRecoveryRequestState state = state(requestId);
        QualityRecoveryCommandContext context = store.loadContext(state.taskId()).orElseThrow(
                QualityFuseRecoveryService::notFound);
        authorizeRead(context, actor, traceId);
        return state;
    }

    public QualityRecoveryRequestState requestApproval(
            UUID requestId, long expectedVersion, String idempotencyKey,
            QualityRecoveryCommandActor actor, String traceId) {
        QualityRecoveryRequestState state = state(requestId);
        QualityRecoveryCommandContext context = store.loadContext(state.taskId()).orElseThrow(
                QualityFuseRecoveryService::notFound);
        CompositeAuthorizationDecision read = authorizeRead(context, actor, traceId);
        String replayKey = digest(idempotencyKey + ":approval-binding");
        String replayInput = digest(String.join("\n", requestId.toString(),
                Long.toString(expectedVersion), "approval-request"));
        QualityRecoveryRequestState replay = store.findRequestReplay(
                replayKey, replayInput, requestId).orElse(null);
        if (replay != null) return replay;
        if (!"validation-succeeded".equals(state.status()) || state.previewDigest() == null) {
            invalidState();
        }
        if (state.requestVersion() != expectedVersion) conflict(state.requestVersion());
        List<String> rules = context.affectedRules().stream()
                .map(QualityRecoveryCommandContext.AffectedRule::ruleVersionDigest).toList();
        RecoveryCheckerBindingResolver.Resolution checker = checkers.resolve(
                rules, null, trustedNow(), traceId);
        if (!checker.available()) unavailable();
        Map<String, Object> binding = state.binding();
        if (read.decisionToken().invalidationVersion()
                    != number(binding, "authorizationGeneration")
                || !decisionDigest(read).equals(
                        string(binding, "authorizationContextDigest"))
                || !authenticationStateDigest(actor).equals(
                        string(binding, "authenticationStateDigest"))) {
            conflict(state.requestVersion());
        }
        HighRiskApprovalView approval = approvals.request(new HighRiskApprovalRequest(
                requestId, string(binding, "requestDigest"), "quality-fuse.recover",
                actor.naturalPersonPrincipalDigest(), string(binding, "authorizationContextDigest"),
                string(binding, "authenticationStateDigest"), "RECOVERY_TASK",
                "sha256:" + digestHex(context.taskId().toString()), context.taskVersion(),
                string(binding, "scopeDigest"), string(binding, "impactScopeDigest"),
                "highly-sensitive-deidentified", "fused", "recovering",
                "QUALITY_EVIDENCE_REVALIDATION_REQUESTED", "HRAM-1.0.0", HRAM_DIGEST,
                "HRAP-1.0.0", HRAP_DIGEST, "RFP-1.0.0", RFP_DIGEST,
                state.previewDigest(), checker.checkerSetDigest(),
                checker.checkerPrincipalDigests(), read.decisionToken().invalidationVersion(),
                trustedNow(), traceId, idempotencyKey));
        return store.bindApproval(replayKey, replayInput, approvalBinding(
                        state, context, approval, null,
                        checker.checkerSetDigest(),
                        checker.ownerBindingSetDigest(),
                        checker.naturalPersonBindingSetDigest(),
                        read.decisionToken().invalidationVersion(), traceId),
                trustedNow());
    }

    public QualityRecoveryRequestState decide(
            UUID requestId, long expectedRequestVersion, long expectedApprovalVersion,
            String decision, String idempotencyKey,
            QualityRecoveryCommandActor actor, String traceId) {
        QualityRecoveryRequestState state = state(requestId);
        QualityRecoveryCommandContext context = store.loadContext(state.taskId()).orElseThrow(
                QualityFuseRecoveryService::notFound);
        authorizeRead(context, actor, traceId);
        if (!actor.roleIds().contains("R6-DATA-OWNER")) deny();
        String actorDigest = actor.naturalPersonPrincipalDigest();
        String replayKey = digest(idempotencyKey + ":decision-binding");
        String replayInput = digest(String.join("\n", requestId.toString(),
                Long.toString(expectedRequestVersion),
                Long.toString(expectedApprovalVersion), decision, actorDigest));
        QualityRecoveryRequestState replay = store.findRequestReplay(
                replayKey, replayInput, requestId).orElse(null);
        if (replay != null) return replay;
        if (!"approval-pending".equals(state.status())
                || state.requestVersion() != expectedRequestVersion
                || state.approvalId() == null) conflict(state.requestVersion());
        List<String> rules = context.affectedRules().stream()
                .map(QualityRecoveryCommandContext.AffectedRule::ruleVersionDigest).toList();
        RecoveryCheckerBindingResolver.Resolution checker = checkers.resolve(
                rules, string(state.binding(), "ownerBindingSetDigest"),
                trustedNow(), traceId);
        if (!checker.available()
                || !checker.checkerPrincipalDigests().contains(actorDigest)
                || !checker.checkerSetDigest().equals(
                        string(state.binding(), "checkerSetDigest"))
                || !checker.naturalPersonBindingSetDigest().equals(
                        string(state.binding(), "checkerNaturalPersonBindingSetDigest"))) {
            conflict(state.requestVersion());
        }
        String approvalTraceId = string(state.binding(), "approvalTraceId");
        HighRiskApprovalView approval = approvals.decide(new HighRiskApprovalDecision(
                state.approvalId(), expectedApprovalVersion,
                HighRiskApprovalDecision.Decision.valueOf(decision.toUpperCase()),
                actorDigest, checker.checkerSetDigest(),
                number(state.binding(), "authorizationGeneration"), trustedNow(),
                approvalTraceId, idempotencyKey));
        String receiptDigest = approval.receiptDigest();
        return store.bindApproval(replayKey, replayInput,
                approvalBinding(state, context, approval, receiptDigest,
                        checker.checkerSetDigest(),
                        checker.ownerBindingSetDigest(),
                        checker.naturalPersonBindingSetDigest(),
                        number(state.binding(), "authorizationGeneration"), approvalTraceId),
                trustedNow());
    }

    public QualityRecoveryExecutionCommit execute(
            UUID requestId, long expectedRequestVersion, String idempotencyKey,
            QualityRecoveryCommandActor actor, String traceId) {
        QualityRecoveryRequestState state = state(requestId);
        QualityRecoveryCommandContext context = store.loadContext(state.taskId()).orElseThrow(
                QualityFuseRecoveryService::notFound);
        authorizeRead(context, actor, traceId);
        String replayKey = digest(idempotencyKey);
        String replayInput = digest(String.join("\n", requestId.toString(),
                Long.toString(expectedRequestVersion), "execute"));
        QualityRecoveryExecutionCommit replay = store.findExecutionReplay(
                replayKey, replayInput, requestId).orElse(null);
        if (replay != null) return replay;
        if (!"approval-approved".equals(state.status())
                || state.requestVersion() != expectedRequestVersion
                || state.approvalId() == null || state.approvalVersion() == null) {
            conflict(state.requestVersion());
        }
        CompositeAuthorizationRequest authRequest = recoveryRequest(context, actor, traceId);
        CompositeAuthorizationDecision decision = recoveryAuthorization.authorize(authRequest);
        recoveryAuthorization.recheck(authRequest, decision);
        Map<String, Object> binding = state.binding();
        List<String> rules = context.affectedRules().stream()
                .map(QualityRecoveryCommandContext.AffectedRule::ruleVersionDigest).toList();
        RecoveryCheckerBindingResolver.Resolution currentCheckers = checkers.resolve(
                rules, string(binding, "ownerBindingSetDigest"), trustedNow(), traceId);
        if (!currentCheckers.available()
                || !currentCheckers.checkerSetDigest().equals(
                        string(binding, "checkerSetDigest"))
                || decision.decisionToken().invalidationVersion()
                        != number(binding, "authorizationGeneration")
                || !decisionDigest(decision).equals(
                        string(binding, "authorizationContextDigest"))
                || !authenticationStateDigest(actor).equals(
                        string(binding, "authenticationStateDigest"))) {
            conflict(state.requestVersion());
        }
        String approvalTraceId = string(binding, "approvalTraceId");
        String receipt = string(binding, "approvalReceiptDigest");
        String issuanceDigest = digest(String.join("\n", requestId.toString(),
                Long.toString(state.approvalVersion()), receipt, state.previewDigest(),
                Long.toString(expectedRequestVersion), approvalTraceId));
        HighRiskExecutionAuthorization lease = executionAuthorizations.issueOrReplay(
                new HighRiskExecutionAuthorizationRequest(
                        state.approvalId(), state.approvalVersion(), receipt,
                        string(binding, "requestDigest"), "quality-fuse.recover", "RECOVERY_TASK",
                        "sha256:" + digestHex(context.taskId().toString()), context.taskVersion(),
                        string(binding, "scopeDigest"), string(binding, "impactScopeDigest"),
                        state.previewDigest(), string(binding, "authorizationContextDigest"),
                        string(binding, "authenticationStateDigest"),
                        string(binding, "checkerSetDigest"),
                        number(binding, "authorizationGeneration"), "ingestion-quality",
                        digest(idempotencyKey + ":lease"), issuanceDigest, trustedNow(),
                        approvalTraceId));
        if ("issued".equals(lease.state())) {
            lease = executionAuthorizations.reserve(lease, trustedNow());
        } else if (!"reserved".equals(lease.state()) && !"executed".equals(lease.state())) {
            invalidState();
        }
        if (!executionAuthorizations.verify(lease)) unavailable();
        UUID outboxId = ids.get();
        UUID confirmationOutboxId = ids.get();
        String commitId = "iq-recovery:" + lease.executionJti();
        String ownerResultDigest = digest(String.join("\n",
                "QUALITY-RECOVERY-COMMITTED-RESULT-1.0.0",
                requestId.toString(), context.taskId().toString(),
                context.episodeId().toString(), "recovering", lease.executionJti().toString(),
                commitId, string(binding, "inputDigest"), state.validationResultDigest(),
                string(binding, "evidencePackDigest"), state.previewDigest()));
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("recoveryRequestId", requestId.toString());
        command.put("expectedRequestVersion", expectedRequestVersion);
        command.put("expectedTaskVersion", context.taskVersion());
        command.put("expectedEpisodeVersion", context.episodeVersion());
        command.put("authorizationGeneration", number(binding, "authorizationGeneration"));
        command.put("actionType", "quality-fuse.recover");
        command.put("approvalId", state.approvalId().toString());
        command.put("approvalVersion", state.approvalVersion());
        command.put("approvalReceiptDigest", receipt);
        command.put("leaseId", lease.leaseId().toString());
        command.put("leaseVersion", lease.leaseVersion());
        command.put("leaseDigest", lease.leaseDigest());
        command.put("leaseState", "reserved");
        command.put("leaseIssuer", lease.issuer());
        command.put("leaseAudience", lease.audience());
        command.put("executionJti", lease.executionJti().toString());
        command.put("authorizedUntil", lease.authorizedUntil().toString());
        command.put("inputDigest", string(binding, "inputDigest"));
        command.put("validationResultDigest", state.validationResultDigest());
        command.put("evidencePackDigest", string(binding, "evidencePackDigest"));
        command.put("previewDigest", state.previewDigest());
        command.put("ownerCommitId", commitId);
        command.put("ownerResultDigest", ownerResultDigest);
        command.put("outboxEventId", outboxId.toString());
        command.put("confirmationOutboxEventId", confirmationOutboxId.toString());
        command.put("auditId", ids.get().toString());
        command.put("traceId", traceId);
        Map<String, Object> payload = Map.of(
                "schemaVersion", "QUALITY-ELIGIBILITY-EVENT-1.1.0",
                "recoveryRequestId", requestId.toString(), "state", "recovering",
                "traceId", traceId);
        command.put("eventPayload", payload);
        command.put("eventPayloadDigest", digest(canonical(payload)));
        Map<String, Object> confirmationPayload = Map.of(
                "recoveryRequestId", requestId.toString(),
                "leaseId", lease.leaseId().toString(),
                "executionJti", lease.executionJti().toString(),
                "leaseDigest", lease.leaseDigest(),
                "ownerCommitId", commitId,
                "ownerResultDigest", ownerResultDigest,
                "authorizedUntil", lease.authorizedUntil().toString(),
                "requestDigest", string(binding, "requestDigest"),
                "traceId", traceId);
        command.put("confirmationPayload", confirmationPayload);
        command.put("confirmationPayloadDigest", digest(canonical(confirmationPayload)));
        command.put("idempotencyInputDigest", digest(String.join("\n",
                requestId.toString(), Long.toString(expectedRequestVersion),
                lease.leaseId().toString(), lease.executionJti().toString(),
                string(binding, "inputDigest"), state.previewDigest())));
        command.put("idempotencyReplayDigest", replayInput);
        String commandDigest = digest(canonical(command));
        command.put("commandDigest", commandDigest);
        QualityRecoveryExecutionCommit commit = store.execute(replayKey, command);
        String reconciliationDigest = reconciliationDigest(lease, state,
                commit.ownerCommitId(), commit.ownerCommittedAt(),
                commit.ownerResultDigest(), commit.confirmationOutboxEventId(),
                approvalTraceId);
        executionAuthorizations.reconcile(new HighRiskExecutionReconciliation(
                lease.leaseId(), lease.leaseVersion(), lease.leaseDigest(), lease.executionJti(),
                string(binding, "requestDigest"), commit.ownerCommitId(),
                commit.ownerCommittedAt(), commit.ownerResultDigest(),
                commit.confirmationOutboxEventId(), reconciliationDigest, approvalTraceId));
        return commit;
    }

    private CompositeAuthorizationDecision authorizeRead(
            QualityRecoveryCommandContext context,
            QualityRecoveryCommandActor actor, String traceId) {
        CompositeAuthorizationDecision result = authorization.authorize(new CompositeAuthorizationRequest(
                actor.sessionPseudonym(), "RECOVERY_TASK", "data-quality.read",
                digestHex(context.taskId().toString()), context.taskVersion(),
                Optional.empty(), Optional.empty(), traceId));
        if (result == null || result.outcome() != CompositeAuthorizationOutcome.ALLOW
                || !result.scopeAnchorSummary().contains("OWNED_SOURCE")) deny();
        return result;
    }

    private static CompositeAuthorizationRequest recoveryRequest(
            QualityRecoveryCommandContext context,
            QualityRecoveryCommandActor actor, String traceId) {
        return new CompositeAuthorizationRequest(
                actor.sessionPseudonym(), "RECOVERY_TASK", "quality-fuse.recover",
                digestHex(context.taskId().toString()), context.taskVersion(),
                Optional.empty(), Optional.empty(), traceId);
    }

    private static Map<String, Object> approvalBinding(
            QualityRecoveryRequestState state, QualityRecoveryCommandContext context,
            HighRiskApprovalView approval, String receiptDigest,
            String checkerSetDigest,
            String ownerBindingSetDigest,
            String checkerNaturalPersonBindingSetDigest,
            long authorizationGeneration, String traceId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("recoveryRequestId", state.recoveryRequestId().toString());
        value.put("expectedRequestVersion", state.requestVersion());
        value.put("actionType", "quality-fuse.recover");
        value.put("objectType", "RECOVERY_TASK");
        value.put("objectRefDigest", "sha256:" + digestHex(context.taskId().toString()));
        value.put("authorizationGeneration", authorizationGeneration);
        value.put("approvalId", approval.approvalId().toString());
        value.put("approvalVersion", approval.approvalVersion());
        value.put("approvalStatus", approval.status());
        value.put("approvalReceiptDigest", receiptDigest);
        value.put("checkerSetDigest", checkerSetDigest);
        value.put("ownerBindingSetDigest", ownerBindingSetDigest);
        value.put("checkerNaturalPersonBindingSetDigest",
                checkerNaturalPersonBindingSetDigest);
        value.put("previewDigest", state.previewDigest());
        value.put("approvalTraceId", traceId);
        value.put("traceId", traceId);
        return value;
    }

    private Instant trustedNow() {
        Instant value = java.util.Objects.requireNonNull(time.now());
        if (value.getNano() % 1_000 != 0) unavailable();
        return value;
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

    private static String reconciliationDigest(
            HighRiskExecutionAuthorization lease, QualityRecoveryRequestState state,
            String commitId, Instant committedAt, String resultDigest,
            UUID outboxId, String traceId) {
        return digest(String.join("\n", lease.leaseId().toString(),
                Long.toString(lease.leaseVersion()), lease.leaseDigest(),
                lease.executionJti().toString(), string(state.binding(), "requestDigest"),
                commitId, committedAt.toString(), resultDigest, outboxId.toString(), traceId));
    }

    private static String canonical(Map<String, Object> value) {
        return value.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static long number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Number number)) invalidState();
        return ((Number) value).longValue();
    }

    private static String string(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String text) || text.isBlank()) invalidState();
        return (String) value;
    }

    private static String digest(String value) { return "sha256:" + digestHex(value); }
    private static String digestHex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    private static IngestionQualityApplicationException notFound() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }
    private static void deny() { throw notFound(); }
    private static void unavailable() {
        throw new IngestionQualityApplicationException("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
    }
    private static void invalidState() {
        throw new IngestionQualityApplicationException("INGESTION_QUALITY_INVALID_STATE");
    }
    private static void conflict(long currentVersion) {
        throw new IngestionQualityApplicationException(
                "INGESTION_QUALITY_VERSION_CONFLICT", currentVersion);
    }
}
