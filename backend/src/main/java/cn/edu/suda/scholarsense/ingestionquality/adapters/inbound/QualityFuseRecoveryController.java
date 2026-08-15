package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContextQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalQuery;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.CurrentNaturalPersonPrincipalResult;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseRecoveryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryCommandActor;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryExecutionCommit;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryRequestState;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationContext;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationCommit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Strict command surface. Trusted approval, identity, preview and lease fields stay server-side. */
@RestController
@ConditionalOnBean(QualityFuseRecoveryService.class)
@Validated
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class QualityFuseRecoveryController {
    private final QualityFuseRecoveryService recovery;
    private final InternalSessionIdentityPort sessions;
    private final AuthoritativeIdentityContextQueryPort identities;
    private final CurrentNaturalPersonPrincipalQueryPort naturalPersons;
    private final QualityRecoveryFinalizationService finalization;

    public QualityFuseRecoveryController(
            QualityFuseRecoveryService recovery,
            InternalSessionIdentityPort sessions,
            AuthoritativeIdentityContextQueryPort identities,
            CurrentNaturalPersonPrincipalQueryPort naturalPersons) {
        this(recovery, sessions, identities, naturalPersons, null);
    }

    @Autowired
    public QualityFuseRecoveryController(
            QualityFuseRecoveryService recovery,
            InternalSessionIdentityPort sessions,
            AuthoritativeIdentityContextQueryPort identities,
            CurrentNaturalPersonPrincipalQueryPort naturalPersons,
            QualityRecoveryFinalizationService finalization) {
        this.recovery = java.util.Objects.requireNonNull(recovery);
        this.sessions = java.util.Objects.requireNonNull(sessions);
        this.identities = java.util.Objects.requireNonNull(identities);
        this.naturalPersons = java.util.Objects.requireNonNull(naturalPersons);
        this.finalization = finalization;
    }

    @PostMapping(value = "/quality-recovery-tasks/{taskId}/recovery-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RequestView> request(
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestBody JsonNode rawBody,
            HttpServletRequest request) {
        requireUuidV7(taskId);
        requireIdempotencyKey(idempotencyKey);
        RecoveryRequestBody body = recoveryRequestBody(rawBody);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(view(recovery.request(
                taskId, body.expectedTaskVersion(), body.reasonCode(), idempotencyKey,
                actor(request, traceId), traceId)));
    }

    @GetMapping("/quality-recovery-requests/{requestId}")
    public ResponseEntity<RequestView> status(
            @PathVariable UUID requestId, HttpServletRequest request) {
        requireUuidV7(requestId);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(view(recovery.status(requestId, actor(request, traceId), traceId)));
    }

    @PostMapping(value = "/quality-recovery-requests/{requestId}/approval-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RequestView> approvalRequest(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestBody JsonNode rawBody,
            HttpServletRequest request) {
        requireUuidV7(requestId);
        requireIdempotencyKey(idempotencyKey);
        ExpectedVersionBody body = expectedVersionBody(rawBody);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(view(recovery.requestApproval(
                requestId, body.expectedVersion(), idempotencyKey,
                actor(request, traceId), traceId)));
    }

    @PostMapping(value = "/quality-recovery-requests/{requestId}/approval-decisions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RequestView> approvalDecision(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestBody JsonNode rawBody,
            HttpServletRequest request) {
        requireUuidV7(requestId);
        requireIdempotencyKey(idempotencyKey);
        ApprovalDecisionBody body = approvalDecisionBody(rawBody);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(view(recovery.decide(
                requestId, body.expectedVersion(), body.expectedApprovalVersion(),
                body.decision(), idempotencyKey, actor(request, traceId), traceId)));
    }

    @PostMapping(value = "/quality-recovery-requests/{requestId}/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExecutionView> execute(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestBody JsonNode rawBody,
            HttpServletRequest request) {
        requireUuidV7(requestId);
        requireIdempotencyKey(idempotencyKey);
        ExpectedVersionBody body = expectedVersionBody(rawBody);
        String traceId = DataSourceCatalogController.trace(request);
        QualityRecoveryExecutionCommit value = recovery.execute(
                requestId, body.expectedVersion(), idempotencyKey,
                actor(request, traceId), traceId);
        return ok(new ExecutionView(
                value.recoveryRequestId(), value.taskId(), value.episodeId(),
                value.state(), value.transitionApplied(), value.ownerCommittedAt(),
                value.traceId()));
    }

    @PostMapping(value = "/quality-recovery-requests/{requestId}/final-approval-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FinalizationView> finalApprovalRequest(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestBody JsonNode rawBody, HttpServletRequest request) {
        requireUuidV7(requestId);
        requireIdempotencyKey(idempotencyKey);
        FinalCommandBody body = finalCommandBody(rawBody);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(finalizationView(callFinal(() -> requireFinalization().requestApproval(
                finalCommand(requestId, body, idempotencyKey), actor(request, traceId),
                traceId))));
    }

    @PostMapping(value = "/quality-recovery-requests/{requestId}/final-approval-decisions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FinalizationView> finalApprovalDecision(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestBody JsonNode rawBody, HttpServletRequest request) {
        requireUuidV7(requestId);
        requireIdempotencyKey(idempotencyKey);
        FinalDecisionBody body = finalDecisionBody(rawBody);
        String traceId = DataSourceCatalogController.trace(request);
        return ok(finalizationView(callFinal(() -> requireFinalization().decide(
                requestId, body.expectedApprovalVersion(), body.decision(), idempotencyKey,
                actor(request, traceId), traceId))));
    }

    @PostMapping(value = "/quality-recovery-requests/{requestId}/finalize",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FinalExecutionView> finalizeRecovery(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestBody JsonNode rawBody, HttpServletRequest request) {
        requireUuidV7(requestId);
        requireIdempotencyKey(idempotencyKey);
        FinalCommandBody body = finalCommandBody(rawBody);
        String traceId = DataSourceCatalogController.trace(request);
        QualityRecoveryFinalizationCommit value = callFinal(
                () -> requireFinalization().execute(
                        finalCommand(requestId, body, idempotencyKey),
                        actor(request, traceId), traceId));
        return ok(new FinalExecutionView(
                value.recoveryId(), value.taskId(), value.generation(),
                value.eligibilityStatus(), value.episodeStatus(), value.taskStatus(),
                value.taskVersion(), value.recoveryCompletedAt(),
                value.windowOutcomesDigest(), value.ownerResultDigest(),
                value.deliveryStatus(), value.traceId()));
    }

    private QualityRecoveryCommandActor actor(
            HttpServletRequest request, String traceId) {
        HttpSession http;
        try {
            http = request.getSession(false);
        } catch (IllegalStateException invalidated) {
            throw forbidden();
        }
        if (http == null) throw forbidden();
        InternalSessionIdentity session;
        try {
            session = sessions.current(http.getId(), request.getRemoteAddr(), traceId);
        } catch (InternalSessionIdentityException failure) {
            if (failure.reason()
                    == InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE) {
                throw unavailable();
            }
            throw forbidden();
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        if (session == null || !session.authenticated()) throw forbidden();
        AuthoritativeIdentityContext identity;
        try {
            identity = identities.findCurrent(session.actorPseudonym())
                    .orElseThrow(QualityFuseRecoveryController::forbidden);
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        CurrentNaturalPersonPrincipalResult naturalPerson;
        try {
            naturalPerson = naturalPersons.resolve(
                    new CurrentNaturalPersonPrincipalQuery(identity.accountId(), traceId));
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
        if (naturalPerson == null
                || naturalPerson.availability()
                    != CurrentNaturalPersonPrincipalResult.Availability.AVAILABLE) {
            throw unavailable();
        }
        return new QualityRecoveryCommandActor(
                session.sessionPseudonym(), session.actorPseudonym(), identity.accountId(),
                naturalPerson.naturalPersonPrincipalDigest(),
                naturalPerson.bindingSetDigest(),
                java.util.Set.copyOf(identity.roleIds()), session.sessionVersion(),
                session.expiresAt(), session.profileVersion());
    }

    private static RequestView view(QualityRecoveryRequestState value) {
        return new RequestView(
                value.recoveryRequestId(), value.requestVersion(), value.taskId(),
                value.status(), value.validationJobId(), value.validationStatus(),
                value.validationResultDigest(), value.previewId(), value.previewVersion(),
                value.previewDigest(), value.previewExpiresAt(), value.previewSummary(),
                value.approvalId(),
                value.approvalVersion(), value.approvalStatus(), value.traceId());
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new CatalogRequestException();
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new CatalogRequestException();
        }
    }

    private static RecoveryRequestBody recoveryRequestBody(JsonNode body) {
        requireExactObject(body, java.util.Set.of("expectedTaskVersion", "reasonCode"));
        long version = version(body, "expectedTaskVersion");
        JsonNode reason = body.get("reasonCode");
        if (reason == null || !reason.isTextual()
                || !"QUALITY_EVIDENCE_REVALIDATION_REQUESTED".equals(reason.asText())) {
            throw new CatalogRequestException();
        }
        return new RecoveryRequestBody(version, reason.asText());
    }

    private static ExpectedVersionBody expectedVersionBody(JsonNode body) {
        requireExactObject(body, java.util.Set.of("expectedVersion"));
        return new ExpectedVersionBody(version(body, "expectedVersion"));
    }

    private static ApprovalDecisionBody approvalDecisionBody(JsonNode body) {
        requireExactObject(body, java.util.Set.of(
                "expectedVersion", "expectedApprovalVersion", "decision"));
        JsonNode decision = body.get("decision");
        if (decision == null || !decision.isTextual()
                || !java.util.Set.of("approve", "reject", "cancel")
                        .contains(decision.asText())) {
            throw new CatalogRequestException();
        }
        return new ApprovalDecisionBody(version(body, "expectedVersion"),
                version(body, "expectedApprovalVersion"), decision.asText());
    }

    private static FinalCommandBody finalCommandBody(JsonNode body) {
        requireExactObject(body, java.util.Set.of(
                "expectedRecoveryVersion", "expectedTaskVersion",
                "finalObservationWatermark"));
        JsonNode watermark = body.get("finalObservationWatermark");
        if (watermark == null || !watermark.isTextual() || watermark.asText().isBlank()
                || watermark.asText().length() > 256) throw new CatalogRequestException();
        return new FinalCommandBody(version(body, "expectedRecoveryVersion"),
                version(body, "expectedTaskVersion"), watermark.asText());
    }

    private static FinalDecisionBody finalDecisionBody(JsonNode body) {
        requireExactObject(body, java.util.Set.of("expectedApprovalVersion", "decision"));
        JsonNode decision = body.get("decision");
        if (decision == null || !decision.isTextual()
                || !java.util.Set.of("approve", "reject", "cancel")
                        .contains(decision.asText())) throw new CatalogRequestException();
        return new FinalDecisionBody(
                version(body, "expectedApprovalVersion"), decision.asText());
    }

    private static QualityRecoveryFinalizationCommand finalCommand(
            UUID requestId, FinalCommandBody body, String idempotencyKey) {
        try {
            return new QualityRecoveryFinalizationCommand(
                    requestId, body.expectedRecoveryVersion(), body.expectedTaskVersion(),
                    body.finalObservationWatermark(), idempotencyKey);
        } catch (IllegalArgumentException invalid) {
            throw new CatalogRequestException(invalid);
        }
    }

    private QualityRecoveryFinalizationService requireFinalization() {
        if (finalization == null) throw unavailable();
        return finalization;
    }

    private static <T> T callFinal(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (org.springframework.dao.DataAccessException failure) {
            Throwable root = failure.getMostSpecificCause();
            String state = root instanceof java.sql.SQLException sql ? sql.getSQLState() : null;
            if ("40001".equals(state) || "23505".equals(state)) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_VERSION_CONFLICT");
            }
            if ("23514".equals(state)) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_INVALID_STATE");
            }
            if ("P0002".equals(state)) throw forbidden();
            throw unavailable();
        } catch (IllegalStateException failure) {
            String code = failure.getMessage();
            if ("QUALITY_FINALIZATION_CONFLICT".equals(code)) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_VERSION_CONFLICT");
            }
            if ("QUALITY_FINALIZATION_NOT_FOUND".equals(code)
                    || "QUALITY_RECOVERY_NOT_FOUND".equals(code)) throw forbidden();
            throw unavailable();
        }
    }

    private static FinalizationView finalizationView(
            QualityRecoveryFinalizationContext value) {
        return new FinalizationView(
                value.recoveryId(), value.recoveryVersion(), value.taskId(),
                value.taskVersion(), value.observationStatus(), value.finalizationState(),
                value.approvalId(), value.approvalVersion(), value.policyVersion(),
                value.finalPreviewDigest(), value.observationDecisionDigest(),
                value.finalObservationWatermark(), value.traceId());
    }

    private static void requireExactObject(JsonNode body, java.util.Set<String> fields) {
        if (body == null || !body.isObject()
                || !body.properties().stream().map(java.util.Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(fields)) {
            throw new CatalogRequestException();
        }
    }

    private static long version(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber()) throw new CatalogRequestException();
        long parsed = value.asLong();
        if (parsed < 1 || parsed > 9007199254740991L) throw new CatalogRequestException();
        return parsed;
    }

    private static IngestionQualityApplicationException forbidden() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }

    private static IngestionQualityApplicationException unavailable() {
        return new IngestionQualityApplicationException(
                "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").body(body);
    }

    public record RecoveryRequestBody(long expectedTaskVersion, String reasonCode) {}

    public record ExpectedVersionBody(long expectedVersion) {}

    public record ApprovalDecisionBody(
            long expectedVersion, long expectedApprovalVersion, String decision) {}

    public record FinalCommandBody(
            long expectedRecoveryVersion,
            long expectedTaskVersion,
            String finalObservationWatermark) {}

    public record FinalDecisionBody(long expectedApprovalVersion, String decision) {}

    public record RequestView(
            UUID recoveryRequestId,
            long requestVersion,
            UUID taskId,
            String status,
            UUID validationJobId,
            String validationStatus,
            String validationResultDigest,
            UUID previewId,
            Long previewVersion,
            String previewDigest,
            Instant previewExpiresAt,
            QualityRecoveryRequestState.PreviewSummary previewSummary,
            UUID approvalId,
            Long approvalVersion,
            String approvalStatus,
            String traceId) {}

    public record ExecutionView(
            UUID recoveryRequestId,
            UUID taskId,
            UUID episodeId,
            String state,
            boolean transitionApplied,
            Instant ownerCommittedAt,
            String traceId) {}

    public record FinalizationView(
            UUID recoveryId,
            long recoveryVersion,
            UUID taskId,
            long taskVersion,
            String observationStatus,
            String finalizationState,
            UUID approvalId,
            Long approvalVersion,
            String policyVersion,
            String finalPreviewDigest,
            String observationDecisionDigest,
            String finalObservationWatermark,
            String traceId) {}

    public record FinalExecutionView(
            UUID recoveryId,
            UUID taskId,
            long generation,
            String eligibilityStatus,
            String episodeStatus,
            String taskStatus,
            long taskVersion,
            Instant recoveryCompletedAt,
            String windowOutcomesDigest,
            String ownerResultDigest,
            String deliveryStatus,
            String traceId) {}
}
