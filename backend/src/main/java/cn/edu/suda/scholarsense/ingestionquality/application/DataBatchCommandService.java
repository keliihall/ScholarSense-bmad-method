package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchLineage;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityException;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

public final class DataBatchCommandService {
    public static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(90);

    private static final String FORBIDDEN = "INGESTION_QUALITY_FORBIDDEN";
    private static final String DEPENDENCY_UNAVAILABLE =
            "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE";
    private static final String VERSION_CONFLICT = "INGESTION_QUALITY_VERSION_CONFLICT";
    private static final String IDEMPOTENCY_MISMATCH =
            "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH";
    private static final String BATCH_NOT_FOUND = "INGESTION_QUALITY_BATCH_NOT_FOUND";
    private static final String IDENTITY_CONFLICT =
            "INGESTION_QUALITY_BATCH_IDENTITY_CONFLICT";
    private static final String VERSION_REGRESSION =
            "INGESTION_QUALITY_SOURCE_VERSION_REGRESSION";
    private static final String CORRECTION_INVALID =
            "INGESTION_QUALITY_CORRECTION_INVALID";
    private static final String CORRECTION_FORK =
            "INGESTION_QUALITY_CORRECTION_FORK";

    private final DataBatchReadPort batches;
    private final DataBatchCommandReplayPort replays;
    private final DataBatchAtomicCommandPort atomic;
    private final DataBatchQualityEvaluationService qualityEvaluation;
    private final DataBatchAuthorizationPort authorization;
    private final DataBatchWorkloadAuthorizationGuard workloadAuthorization;
    private final DataBatchCanonicalOutboxFactory payloads;
    private final TrustedTimeSource time;

    public DataBatchCommandService(
            DataBatchReadPort batches,
            QualitySnapshotReadPort snapshotReads,
            DataBatchCommandReplayPort replays,
            DataBatchAtomicCommandPort atomic,
            DataBatchQualityEvaluationService qualityEvaluation,
            ExecutableQualityPolicyGuard contractGuard,
            DataBatchAuthorizationPort authorization,
            DataBatchWorkloadAuthorizationGuard workloadAuthorization,
            DataBatchCanonicalOutboxFactory payloads,
            TrustedTimeSource time) {
        this.batches = Objects.requireNonNull(batches);
        this.replays = Objects.requireNonNull(replays);
        this.atomic = Objects.requireNonNull(atomic);
        this.qualityEvaluation = Objects.requireNonNull(qualityEvaluation);
        if (!qualityEvaluation.uses(
                Objects.requireNonNull(contractGuard), this.batches,
                Objects.requireNonNull(snapshotReads))) {
            throw new IllegalArgumentException("quality evaluation boundary mismatch");
        }
        this.authorization = Objects.requireNonNull(authorization);
        this.workloadAuthorization = Objects.requireNonNull(workloadAuthorization);
        this.payloads = Objects.requireNonNull(payloads);
        this.time = Objects.requireNonNull(time);
    }

    public DataBatchView receive(ReceiveDataBatchCommand command) {
        Objects.requireNonNull(command);
        String requestDigest = DataBatchCommandFingerprint.digest(command);
        return execute(
                DataBatchCommandType.RECEIVE, command.context(), requestDigest,
                (completed, authorizedContext) -> authorizeReceiveOrReplay(
                        command, completed, authorizedContext),
                (trusted, digest, workloadEvidence, authorizedContext, invocation) ->
                        receiveInTransaction(
                                command, authorizedContext, trusted, digest,
                                workloadEvidence, invocation));
    }

    public DataBatchView seal(SealDataBatchCommand command) {
        Objects.requireNonNull(command);
        String requestDigest = DataBatchCommandFingerprint.digest(command);
        return execute(
                DataBatchCommandType.SEAL, command.context(), requestDigest,
                (completed, authorizedContext) -> authorizeMutationOrReplay(
                        completed, command.batchId(), authorizedContext,
                        DataBatchCommandType.SEAL),
                (trusted, digest, workloadEvidence, authorizedContext, invocation) ->
                        sealInTransaction(
                                command, authorizedContext, digest, trusted,
                                workloadEvidence, invocation));
    }

    public DataBatchView evaluate(EvaluateDataBatchCommand command) {
        Objects.requireNonNull(command);
        String requestDigest = DataBatchCommandFingerprint.digest(command);
        return execute(
                DataBatchCommandType.EVALUATE, command.context(), requestDigest,
                (completed, authorizedContext) -> authorizeMutationOrReplay(
                        completed, command.batchId(), authorizedContext,
                        DataBatchCommandType.EVALUATE),
                (trusted, digest, workloadEvidence, authorizedContext, invocation) ->
                        evaluateInTransaction(
                                command, authorizedContext, digest, trusted,
                                workloadEvidence, invocation));
    }

    public DataBatchView publish(PublishDataBatchCommand command) {
        Objects.requireNonNull(command);
        String requestDigest = DataBatchCommandFingerprint.digest(command);
        return execute(
                DataBatchCommandType.PUBLISH, command.context(), requestDigest,
                (completed, authorizedContext) -> authorizeMutationOrReplay(
                        completed, command.batchId(), authorizedContext,
                        DataBatchCommandType.PUBLISH),
                (trusted, digest, workloadEvidence, authorizedContext, invocation) ->
                        publishInTransaction(
                                command, authorizedContext, digest, trusted,
                                workloadEvidence, invocation));
    }

    private DataBatchView receiveInTransaction(
            ReceiveDataBatchCommand command,
            DataBatchCommandContext authorizedContext,
            TrustedTime trusted,
            String requestDigest,
            DataBatchWorkloadAuthorizationEvidence workloadEvidence,
            AtomicInvocation invocation) {
        Optional<DataBatch> exact = batches.findByIdentity(command.identity());
        DataBatch responseBatch;
        if (exact.isPresent()) {
            responseBatch = exact.orElseThrow();
            if (!responseBatch.declaredManifestDigest().equals(
                    command.declaredManifestDigest())) {
                throw new IngestionQualityApplicationException(IDENTITY_CONFLICT);
            }
        } else {
            Optional<DataBatch> latest = batches.latestForBusinessKey(
                    command.identity().sourceId(), command.identity().businessKey());
            if (latest.isPresent()
                    && command.identity().sourceVersion()
                    < latest.orElseThrow().identity().sourceVersion()) {
                throw new IngestionQualityApplicationException(VERSION_REGRESSION);
            }
            validateLineage(command, latest.orElse(null));
            responseBatch = DataBatch.receiving(
                    command.batchId(), command.identity(), command.lineage(),
                    command.declaredManifestDigest(), trusted.instant(),
                    authorizedContext.traceId());
        }
        authorize(responseBatch, authorizedContext, DataBatchCommandType.RECEIVE);
        DataBatch proposed = responseBatch;
        DataBatchView expectedResponse = DataBatchView.from(responseBatch);
        DataBatchAtomicCommitContext commit = commitContext(
                DataBatchCommandType.RECEIVE, authorizedContext, requestDigest,
                expectedResponse.batchId(), expectedResponse.aggregateVersion(), trusted);
        CanonicalOutboxPayload outbox = payloads.create(
                DataBatchCommandType.RECEIVE, expectedResponse, commit);
        TrustedTime finalCheck = trustedTime();
        revalidateWorkload(
                workloadEvidence, DataBatchCommandType.RECEIVE, finalCheck.instant());
        invocation.start();
        DataBatchAtomicCommandResult result = atomic.receive(
                new ReceiveDataBatchAtomicCommand(proposed, commit, outbox));
        if (result.status() == DataBatchAtomicCommandResult.Status.REPLAY) {
            authorize(
                    requireBatch(result.response().batchId()), authorizedContext,
                    DataBatchCommandType.RECEIVE);
        }
        return result.response();
    }

    private DataBatchView sealInTransaction(
            SealDataBatchCommand command,
            DataBatchCommandContext authorizedContext,
            String requestDigest,
            TrustedTime trusted,
            DataBatchWorkloadAuthorizationEvidence workloadEvidence,
            AtomicInvocation invocation) {
        DataBatch current = requireBatch(command.batchId());
        requireVersion(current, command.expectedAggregateVersion());
        authorize(current, authorizedContext, DataBatchCommandType.SEAL);
        var evidence = qualityEvaluation.prepareSeal(
                current, command.manifest(), trusted.instant());
        DataBatch updated = current.seal(command.manifest(), evidence, trusted.instant());
        DataBatchView response = DataBatchView.from(updated);
        DataBatchAtomicCommitContext commit = commitContext(
                DataBatchCommandType.SEAL, authorizedContext, requestDigest,
                updated.batchId(), updated.aggregateVersion(), trusted);
        CanonicalOutboxPayload outbox = payloads.create(
                DataBatchCommandType.SEAL, response, commit);
        qualityEvaluation.revalidate(evidence);
        DataBatch finalCurrent = requireBatch(command.batchId());
        requireVersion(finalCurrent, command.expectedAggregateVersion());
        authorize(finalCurrent, authorizedContext, DataBatchCommandType.SEAL);
        TrustedTime finalCheck = trustedTime();
        revalidateWorkload(
                workloadEvidence, DataBatchCommandType.SEAL, finalCheck.instant());
        invocation.start();
        DataBatchAtomicCommandResult result = atomic.seal(new SealDataBatchAtomicCommand(
                command.expectedAggregateVersion(), updated, evidence, commit, outbox));
        if (result.status() == DataBatchAtomicCommandResult.Status.REPLAY) {
            authorize(
                    requireBatch(result.response().batchId()), authorizedContext,
                    DataBatchCommandType.SEAL);
        }
        return result.response();
    }

    private DataBatchView evaluateInTransaction(
            EvaluateDataBatchCommand command,
            DataBatchCommandContext authorizedContext,
            String requestDigest,
            TrustedTime trusted,
            DataBatchWorkloadAuthorizationEvidence workloadEvidence,
            AtomicInvocation invocation) {
        DataBatch current = requireBatch(command.batchId());
        requireVersion(current, command.expectedAggregateVersion());
        authorize(current, authorizedContext, DataBatchCommandType.EVALUATE);
        PreparedDataBatchAssessment prepared = qualityEvaluation.evaluate(
                current, trusted.instant(), authorizedContext.traceId());
        DataBatchView response = DataBatchView.from(prepared.updatedBatch());
        DataBatchAtomicCommitContext commit = commitContext(
                DataBatchCommandType.EVALUATE, authorizedContext, requestDigest,
                response.batchId(), response.aggregateVersion(), trusted);
        CanonicalOutboxPayload outbox = payloads.create(
                DataBatchCommandType.EVALUATE, response, prepared.snapshot().value(), commit);
        qualityEvaluation.revalidate(prepared.contractEvidence());
        DataBatch finalCurrent = requireBatch(command.batchId());
        requireVersion(finalCurrent, command.expectedAggregateVersion());
        authorize(finalCurrent, authorizedContext, DataBatchCommandType.EVALUATE);
        TrustedTime finalCheck = trustedTime();
        revalidateWorkload(
                workloadEvidence, DataBatchCommandType.EVALUATE, finalCheck.instant());
        invocation.start();
        DataBatchAtomicCommandResult result = atomic.commitQualityEvaluation(
                new CommitDataBatchQualityEvaluationCommand(
                        command.expectedAggregateVersion(), prepared,
                        QualitySnapshotRetentionScopeCanonicalizer.digest(
                                prepared.snapshot().value()),
                        commit, outbox));
        if (result.status() == DataBatchAtomicCommandResult.Status.REPLAY) {
            authorize(
                    requireBatch(result.response().batchId()), authorizedContext,
                    DataBatchCommandType.EVALUATE);
        }
        return result.response();
    }

    private DataBatchView publishInTransaction(
            PublishDataBatchCommand command,
            DataBatchCommandContext authorizedContext,
            String requestDigest,
            TrustedTime trusted,
            DataBatchWorkloadAuthorizationEvidence workloadEvidence,
            AtomicInvocation invocation) {
        DataBatch current = requireBatch(command.batchId());
        requireVersion(current, command.expectedAggregateVersion());
        authorize(current, authorizedContext, DataBatchCommandType.PUBLISH);
        var qualitySnapshot = qualityEvaluation.validatePublish(current);
        DataBatch updated = current.publish(trusted.instant());
        DataBatchView response = DataBatchView.from(updated);
        DataBatchAtomicCommitContext commit = commitContext(
                DataBatchCommandType.PUBLISH, authorizedContext, requestDigest,
                response.batchId(), response.aggregateVersion(), trusted);
        CanonicalOutboxPayload outbox = payloads.create(
                DataBatchCommandType.PUBLISH, response, qualitySnapshot, commit);
        DataBatch finalCurrent = requireBatch(command.batchId());
        requireVersion(finalCurrent, command.expectedAggregateVersion());
        authorize(finalCurrent, authorizedContext, DataBatchCommandType.PUBLISH);
        TrustedTime finalCheck = trustedTime();
        revalidateWorkload(
                workloadEvidence, DataBatchCommandType.PUBLISH, finalCheck.instant());
        invocation.start();
        DataBatchAtomicCommandResult result = atomic.publish(new PublishDataBatchAtomicCommand(
                command.expectedAggregateVersion(), updated, commit, outbox));
        if (result.status() == DataBatchAtomicCommandResult.Status.REPLAY) {
            authorize(
                    requireBatch(result.response().batchId()), authorizedContext,
                    DataBatchCommandType.PUBLISH);
        }
        return result.response();
    }

    private DataBatchView execute(
            DataBatchCommandType commandType,
            DataBatchCommandContext context,
            String requestDigest,
            BiConsumer<Optional<DataBatchIdempotencyResult>, DataBatchCommandContext>
                    authorizationCheck,
            AuthorizedCommandWork work) {
        try {
            TrustedTime trusted = trustedTime();
            DataBatchWorkloadAuthorizationEvidence workloadEvidence =
                    workloadAuthorization.capture(
                            commandType, context.actorRef(), trusted.instant());
            DataBatchCommandContext authorizedContext = new DataBatchCommandContext(
                    context.tenantId(), workloadEvidence.principalRef(),
                    context.idempotencyKey(), context.traceId());
            DataBatchIdempotencyScope scope = DataBatchIdempotencyScope.of(
                    authorizedContext, commandType);
            DataBatchCommandPrecedence precedence = inspectPrecedence(scope, requestDigest);
            if (precedence.status() == DataBatchCommandPrecedence.Status.MISMATCH) {
                throw new IngestionQualityApplicationException(IDEMPOTENCY_MISMATCH);
            }
            Optional<DataBatchIdempotencyResult> completed = findReplay(precedence);
            if (completed.isPresent()) {
                if (!completed.orElseThrow().requestDigest().equals(requestDigest)) {
                    throw new IngestionQualityApplicationException(IDEMPOTENCY_MISMATCH);
                }
                authorizationCheck.accept(completed, authorizedContext);
                return completed.orElseThrow().response();
            }
            AtomicInvocation invocation = new AtomicInvocation();
            try {
                authorizationCheck.accept(Optional.empty(), authorizedContext);
                return work.apply(
                        trusted, requestDigest, workloadEvidence, authorizedContext,
                        invocation);
            } catch (RuntimeException failure) {
                if (invocation.started()) throw failure;
                return reconcilePrecedence(
                        scope, requestDigest, commandType, workloadEvidence,
                        authorizationCheck, authorizedContext, failure);
            }
        } catch (DataBatchVersionConflictException conflict) {
            throw new IngestionQualityApplicationException(
                    VERSION_CONFLICT, conflict.currentVersion());
        } catch (IngestionQualityException domain) {
            throw new IngestionQualityApplicationException(domain.code());
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private Optional<DataBatchIdempotencyResult> findReplay(
            DataBatchCommandPrecedence precedence) {
        return precedence.replay();
    }

    private DataBatchCommandPrecedence inspectPrecedence(
            DataBatchIdempotencyScope scope, String requestDigest) {
        try {
            return Objects.requireNonNull(replays.inspect(scope, requestDigest));
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private DataBatchView reconcilePrecedence(
            DataBatchIdempotencyScope scope,
            String requestDigest,
            DataBatchCommandType commandType,
            DataBatchWorkloadAuthorizationEvidence workloadEvidence,
            BiConsumer<Optional<DataBatchIdempotencyResult>, DataBatchCommandContext>
                    authorizationCheck,
            DataBatchCommandContext authorizedContext,
            RuntimeException failure) {
        DataBatchCommandPrecedence precedence = inspectPrecedence(scope, requestDigest);
        if (precedence.status() == DataBatchCommandPrecedence.Status.MISMATCH) {
            throw new IngestionQualityApplicationException(IDEMPOTENCY_MISMATCH);
        }
        Optional<DataBatchIdempotencyResult> completed = precedence.replay();
        if (completed.isPresent()) {
            TrustedTime finalCheck = trustedTime();
            revalidateWorkload(workloadEvidence, commandType, finalCheck.instant());
            authorizationCheck.accept(completed, authorizedContext);
            return completed.orElseThrow().response();
        }
        throw failure;
    }

    private void validateLineage(ReceiveDataBatchCommand command, DataBatch latest) {
        BatchLineage lineage = command.lineage();
        if (!lineage.successor()) {
            if (latest != null) {
                throw new IngestionQualityApplicationException(CORRECTION_INVALID);
            }
            if (batches.lineageHead(lineage.lineageId()).isPresent()) {
                throw new IngestionQualityApplicationException(CORRECTION_FORK);
            }
            return;
        }
        DataBatch predecessor = batches.find(lineage.supersedesBatchId())
                .orElseThrow(() -> new IngestionQualityApplicationException(CORRECTION_INVALID));
        DataBatch head = batches.lineageHead(lineage.lineageId())
                .orElseThrow(() -> new IngestionQualityApplicationException(CORRECTION_INVALID));
        if (!predecessor.batchId().equals(lineage.supersedesBatchId())
                || !head.batchId().equals(predecessor.batchId())
                || latest == null
                || !latest.batchId().equals(predecessor.batchId())) {
            throw new IngestionQualityApplicationException(CORRECTION_FORK);
        }
        boolean identityMatches = predecessor.lineage().lineageId().equals(lineage.lineageId())
                && predecessor.identity().sourceId().equals(command.identity().sourceId())
                && predecessor.identity().businessKey().equals(command.identity().businessKey())
                && command.identity().sourceVersion() > predecessor.identity().sourceVersion();
        boolean timeMonotonic = predecessor.lineage().effectiveAt() == null
                || !lineage.effectiveAt().isBefore(predecessor.lineage().effectiveAt());
        if (!identityMatches || !timeMonotonic
                || predecessor.batchId().equals(command.batchId())) {
            throw new IngestionQualityApplicationException(CORRECTION_INVALID);
        }
    }

    private void authorize(
            DataBatch batch,
            DataBatchCommandContext context,
            DataBatchCommandType commandType) {
        authorize(new DataBatchAuthorizationRequest(
                context, commandType, batch.identity().sourceId(), batch.batchId(),
                batch.aggregateVersion()));
    }

    private void authorizeMutationOrReplay(
            Optional<DataBatchIdempotencyResult> completed,
            java.util.UUID requestedBatchId,
            DataBatchCommandContext context,
            DataBatchCommandType commandType) {
        if (completed.isPresent()) {
            DataBatchView replay = completed.orElseThrow().response();
            authorize(requireBatch(replay.batchId()), context, commandType);
            return;
        }
        authorize(requireBatch(requestedBatchId), context, commandType);
    }

    private void authorizeReceiveOrReplay(
            ReceiveDataBatchCommand command,
            Optional<DataBatchIdempotencyResult> completed,
            DataBatchCommandContext authorizedContext) {
        if (completed.isPresent()) {
            DataBatchView replay = completed.orElseThrow().response();
            authorize(
                    requireBatch(replay.batchId()), authorizedContext,
                    DataBatchCommandType.RECEIVE);
            return;
        }
        authorize(new DataBatchAuthorizationRequest(
                authorizedContext, DataBatchCommandType.RECEIVE,
                command.identity().sourceId(), command.batchId(), 0));
    }

    private void authorize(DataBatchAuthorizationRequest request) {
        DataBatchAuthorizationDecision decision;
        try {
            decision = authorization.authorize(request);
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(DEPENDENCY_UNAVAILABLE);
        }
        if (decision == DataBatchAuthorizationDecision.DENY) {
            throw new IngestionQualityApplicationException(FORBIDDEN);
        }
        if (decision != DataBatchAuthorizationDecision.ALLOW) {
            throw new IngestionQualityApplicationException(DEPENDENCY_UNAVAILABLE);
        }
    }

    private DataBatch requireBatch(java.util.UUID batchId) {
        try {
            DataBatch batch = Objects.requireNonNull(batches.find(batchId))
                    .orElseThrow(() -> new IngestionQualityApplicationException(BATCH_NOT_FOUND));
            if (!batch.batchId().equals(batchId)) {
                throw new IngestionQualityApplicationException(BATCH_NOT_FOUND);
            }
            return batch;
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private static void requireVersion(DataBatch batch, long expectedVersion) {
        if (batch.aggregateVersion() != expectedVersion) {
            throw new IngestionQualityApplicationException(
                    VERSION_CONFLICT, batch.aggregateVersion());
        }
    }

    private DataBatchAtomicCommitContext commitContext(
            DataBatchCommandType type,
            DataBatchCommandContext context,
            String requestDigest,
            java.util.UUID batchId,
            long responseVersion,
            TrustedTime trusted) {
        DataBatchIdempotencyScope scope = DataBatchIdempotencyScope.of(context, type);
        return new DataBatchAtomicCommitContext(
                DataBatchOwnerIds.commandId(
                        batchId, type, responseVersion, trusted.instant()),
                scope, scope.digest(), requestDigest, context.traceId(), trusted);
    }

    private TrustedTime trustedTime() {
        try {
            return Objects.requireNonNull(time.now(), "trusted time");
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private void revalidateWorkload(
            DataBatchWorkloadAuthorizationEvidence captured,
            DataBatchCommandType commandType,
            Instant currentTime) {
        workloadAuthorization.revalidate(captured, commandType, currentTime);
    }

    @FunctionalInterface
    private interface AuthorizedCommandWork {
        DataBatchView apply(
                TrustedTime trusted,
                String requestDigest,
                DataBatchWorkloadAuthorizationEvidence workloadEvidence,
                DataBatchCommandContext authorizedContext,
                AtomicInvocation invocation);
    }

    private static final class AtomicInvocation {
        private boolean started;

        private void start() {
            if (started) throw new IllegalStateException("atomic owner already invoked");
            started = true;
        }

        private boolean started() {
            return started;
        }
    }
}
