package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchLineage;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchManifest;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.MeasuredQualityInputs;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DataBatchCommandServiceTest {
    private enum DataBatchAssessmentOutcome { PASSED, FAILED }

    private static final UUID BATCH_ID = uuid("019ff200-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019ff200-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final String MANIFEST_DIGEST = "sha256:" + "a".repeat(64);
    private static final VerifiedQualityContract CONTRACT =
            new FrozenExecutableQualityPolicyLoader(
                    Path.of("..").toAbsolutePath().normalize()).loadVerified();
    private static final TrustedTimeSource TIME = () -> new TrustedTime(
            NOW,
            new TimeSourceProfile(
                    "campus-ntp-batch", "AUDIT-CLOCK-BINDING-1.0.0", 7,
                    NOW.minusSeconds(5), NOW.plusSeconds(30),
                    "evidence://signed/clock/batch.json"));

    @Test
    void receiveSealEvaluateAndPublishUseOneApplicationBoundary() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);

        DataBatchView received = service.receive(receive(context("idem-receive")));
        DataBatchView sealed = service.seal(new SealDataBatchCommand(
                BATCH_ID, 1, manifest(), context("idem-seal")));
        DataBatchView assessed = service.evaluate(new EvaluateDataBatchCommand(
                BATCH_ID, 2, context("idem-evaluate")));
        DataBatchView published = service.publish(new PublishDataBatchCommand(
                BATCH_ID, 3, context("idem-publish")));

        assertEquals(DataBatchStatus.RECEIVING, received.status());
        assertEquals(DataBatchStatus.SEALED, sealed.status());
        assertEquals(DataBatchStatus.QUALITY_PASSED, assessed.status());
        assertEquals(DataBatchStatus.PUBLISHED, published.status());
        assertEquals(4, published.aggregateVersion());
        assertEquals(List.of(
                "data-batch.receive", "data-batch.seal",
                "data-batch.evaluate", "data-batch.publish"),
                ports.audits.stream().map(DataBatchAuditEvent::action).toList());
        assertEquals(4, ports.transactionCalls.get());
        assertEquals(4, ports.idempotency.size());
        assertTrue(ports.idempotency.values().stream().allMatch(result ->
                result.expiresAt().equals(result.completedAt().plus(Duration.ofDays(90)))));
        assertTrue(ports.idempotency.values().stream().allMatch(result ->
                result.requestDigest().matches("sha256:[0-9a-f]{64}")));
        QualitySnapshot snapshot = ports.snapshots.byBatch.get(BATCH_ID);
        assertEquals(QualitySnapshotRetentionScopeCanonicalizer.digest(snapshot),
                ports.lastEvaluationCommand.retentionScopeDigest());
        for (CanonicalOutboxPayload outbox : List.of(
                ports.lastEvaluationCommand.outbox(), ports.lastPublishCommand.outbox())) {
            DataBatchBusinessOutboxEvent event = outbox.businessEvent().orElseThrow();
            assertEquals(snapshot.snapshotId(), event.qualitySnapshot().snapshotId());
            assertEquals(MANIFEST_DIGEST, event.batch().manifest().manifestDigest());
            byte[] businessUtf8 = outbox.businessUtf8().orElseThrow();
            assertTrue(businessUtf8.length <= 65_536);
            String payload = new String(businessUtf8, StandardCharsets.UTF_8);
            assertTrue(payload.contains("\"qualitySnapshot\":"));
            assertTrue(payload.contains("\"metricResults\":"));
            assertTrue(payload.contains("\"watermark\":"));
            assertTrue(payload.contains("\"qualityMetricDecisionProfileDigest\":"));
        }
    }

    @Test
    void eachAtomicCommandCarriesItsAuthorizedTraceIntoAuditAndBusinessEvidence() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        String receiveTrace = "11111111111111111111111111111111";
        String sealTrace = "22222222222222222222222222222222";
        String evaluateTrace = "33333333333333333333333333333333";
        String publishTrace = "44444444444444444444444444444444";

        service.receive(receive(context("trace-receive", receiveTrace)));
        service.seal(new SealDataBatchCommand(
                BATCH_ID, 1, manifest(), context("trace-seal", sealTrace)));
        service.evaluate(new EvaluateDataBatchCommand(
                BATCH_ID, 2, context("trace-evaluate", evaluateTrace)));
        DataBatchView published = service.publish(new PublishDataBatchCommand(
                BATCH_ID, 3, context("trace-publish", publishTrace)));

        assertEquals(receiveTrace, published.traceId(),
                "the batch keeps its receive provenance trace");
        assertEquals(receiveTrace, ports.lastReceiveCommand.commit().traceId());
        assertEquals(sealTrace, ports.lastSealCommand.commit().traceId());
        assertEquals(evaluateTrace, ports.lastEvaluationCommand.commit().traceId());
        assertEquals(publishTrace, ports.lastPublishCommand.commit().traceId());
        assertEquals(receiveTrace,
                ports.lastReceiveCommand.outbox().auditRecord().fact().traceId());
        assertEquals(sealTrace,
                ports.lastSealCommand.outbox().auditRecord().fact().traceId());
        assertEquals(evaluateTrace,
                ports.lastEvaluationCommand.outbox().auditRecord().fact().traceId());
        assertEquals(publishTrace,
                ports.lastPublishCommand.outbox().auditRecord().fact().traceId());
        assertEquals(evaluateTrace,
                ports.lastEvaluationCommand.outbox().businessEvent().orElseThrow().traceId());
        assertEquals(publishTrace,
                ports.lastPublishCommand.outbox().businessEvent().orElseThrow().traceId());
        assertEquals(evaluateTrace, ports.snapshots.byBatch.get(BATCH_ID).traceId(),
                "the immutable snapshot is evaluated under the evaluate command trace");
        assertEquals(List.of(receiveTrace, sealTrace, evaluateTrace, publishTrace),
                ports.audits.stream().map(DataBatchAuditEvent::traceId).toList());
    }

    @Test
    void sameScopedKeyAndServerDerivedBodyDigestReplaysButDifferentBodyConflicts() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        ReceiveDataBatchCommand command = receive(context("idem-receive"));

        DataBatchView first = service.receive(command);
        DataBatchView replay = service.receive(command);

        assertEquals(first, replay);
        assertEquals(1, ports.insertCalls.get());
        assertEquals(1, ports.audits.size());
        assertEquals(1, ports.transactionCalls.get(),
                "completed replay should be returned before opening another transaction");

        ReceiveDataBatchCommand changedBody = new ReceiveDataBatchCommand(
                uuid("019ff200-0000-7000-8000-000000000099"),
                0,
                command.identity(),
                command.lineage(),
                "sha256:" + "b".repeat(64),
                command.context());
        IngestionQualityApplicationException mismatch = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(changedBody));
        assertEquals("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", mismatch.code());
        assertEquals(1, ports.insertCalls.get());

        DataBatchView independentScope = service.receive(new ReceiveDataBatchCommand(
                BATCH_ID, 0, command.identity(), command.lineage(), MANIFEST_DIGEST,
                new DataBatchCommandContext(
                        "tenant-b", "quality-worker-a", "idem-receive",
                        "ffeeddccbbaa99887766554433221100")));
        assertEquals(first, independentScope,
                "business replay remains idempotent even when the command scope differs");
    }

    @Test
    void businessIdentityReplayKeepsOriginalReceivedAtWhenTrustedTimeAdvances() {
        MemoryPorts ports = new MemoryPorts();
        AtomicInteger calls = new AtomicInteger();
        TrustedTimeSource advancing = () -> {
            Instant instant = NOW.plusSeconds(calls.getAndIncrement());
            return new TrustedTime(instant, new TimeSourceProfile(
                    "campus-ntp-batch", "AUDIT-CLOCK-BINDING-1.0.0", 7,
                    instant.minusSeconds(5), instant.plusSeconds(30),
                    "evidence://signed/clock/batch.json"));
        };
        DataBatchCommandService service = serviceWithMeasurement(
                ports,
                (batch, definitions) -> new QualityMeasurement(
                        QualityMeasurementAnchor.from(batch),
                        measurements(definitions, false), List.of("student-status")),
                DataBatchWorkloadAuthorizationTestFixture.guard("quality-worker-a"),
                advancing);

        DataBatchView original = service.receive(receive(context("idem-original")));
        DataBatchView replay = service.receive(new ReceiveDataBatchCommand(
                BATCH_ID, 0, original.identity(), original.lineage(), MANIFEST_DIGEST,
                context("idem-business-replay-after-time-advance")));

        assertEquals(original, replay);
        assertEquals(original.receivedAt(), ports.lastReceiveCommand.receivedBatch().receivedAt(),
                "the owner command must replay the persisted aggregate, not rebuild it at now");
    }

    @Test
    void staleExpectedVersionReturnsCurrentVersionBeforeMutation() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        service.receive(receive(context("idem-receive")));

        IngestionQualityApplicationException conflict = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.seal(new SealDataBatchCommand(
                        BATCH_ID, 2, manifest(), context("idem-seal-stale"))));

        assertEquals("INGESTION_QUALITY_VERSION_CONFLICT", conflict.code());
        assertEquals(1, conflict.currentVersion());
        assertEquals(DataBatchStatus.RECEIVING, ports.byId.get(BATCH_ID).status());
        assertEquals(0, ports.saveCalls.get());
        assertEquals(1, ports.audits.size());
        assertFalse(ports.idempotency.keySet().stream()
                .anyMatch(scope -> scope.idempotencyKey().equals("idem-seal-stale")));
    }

    @Test
    void changedBodyIdempotencyMismatchPrecedesChangedObjectLookup() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        service.receive(receive(context("idem-root")));
        SealDataBatchCommand original = new SealDataBatchCommand(
                BATCH_ID, 1, manifest(), context("idem-seal-stable"));
        service.seal(original);

        SealDataBatchCommand changedObject = new SealDataBatchCommand(
                uuid("019ff200-0000-7000-8000-000000000088"),
                1, manifest(), original.context());
        IngestionQualityApplicationException mismatch = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.seal(changedObject));

        assertEquals("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", mismatch.code());
    }

    @Test
    void concurrentIdempotencyClaimMismatchStillPrecedesChangedObjectLookup() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        service.receive(receive(context("idem-race-root")));
        SealDataBatchCommand original = new SealDataBatchCommand(
                BATCH_ID, 1, manifest(), context("idem-race-seal"));
        service.seal(original);
        int claimsBeforeRace = ports.claimCalls.get();
        int inspectionsBeforeRace = ports.precedenceInspectionCalls.get();
        ports.hideNextPrecedenceInspection = true;

        SealDataBatchCommand changedObject = new SealDataBatchCommand(
                uuid("019ff200-0000-7000-8000-000000000089"),
                1, manifest(), original.context());
        IngestionQualityApplicationException mismatch = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.seal(changedObject));

        assertEquals("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", mismatch.code());
        assertEquals(claimsBeforeRace, ports.claimCalls.get(),
                "a failed preparation must not invoke an owner write");
        assertEquals(inspectionsBeforeRace + 2, ports.precedenceInspectionCalls.get(),
                "a raced initial inspection is reconciled once after preparation fails");
    }

    @Test
    void finalOwnerClaimRaceRetainsStableIdempotencyMismatch() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        service.receive(receive(context("idem-final-claim-root")));
        int claimsBeforeRace = ports.claimCalls.get();
        ports.failNextOwnerClaimWithMismatch = true;

        IngestionQualityApplicationException mismatch = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.seal(new SealDataBatchCommand(
                        BATCH_ID, 1, manifest(), context("idem-final-claim-race"))));

        assertEquals("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", mismatch.code());
        assertEquals(claimsBeforeRace + 1, ports.claimCalls.get());
        assertEquals(DataBatchStatus.RECEIVING, ports.byId.get(BATCH_ID).status());
    }

    @Test
    void postflightReplayCannotBypassRevokedOrUnavailableWorkloadAuthority() {
        for (DataBatchWorkloadAuthorizationResult.Status status : List.of(
                DataBatchWorkloadAuthorizationResult.Status.DENY,
                DataBatchWorkloadAuthorizationResult.Status.DEPENDENCY_UNAVAILABLE)) {
            MemoryPorts ports = new MemoryPorts();
            ReceiveDataBatchCommand command = receive(context(
                    "idem-postflight-workload-" + status.name().toLowerCase()));
            service(ports, DataBatchAssessmentOutcome.PASSED).receive(command);
            int ownerCallsBefore = ports.transactionCalls.get();
            ports.hideNextPrecedenceInspection = true;

            IngestionQualityApplicationException failure = assertThrows(
                    IngestionQualityApplicationException.class,
                    () -> serviceWithWorkload(
                            ports, DataBatchAssessmentOutcome.PASSED,
                            failingRevalidation(status)).receive(command));

            assertEquals(
                    status == DataBatchWorkloadAuthorizationResult.Status.DENY
                            ? "INGESTION_QUALITY_FORBIDDEN"
                            : "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE",
                    failure.code());
            assertEquals(ownerCallsBefore, ports.transactionCalls.get(),
                    "postflight replay must not invoke this request's owner command");
        }
    }

    @Test
    void finalObjectAuthorizationDenialOrOutageAfterMeasurementPreventsOwnerWrite() {
        for (DataBatchAuthorizationDecision decision : List.of(
                DataBatchAuthorizationDecision.DENY,
                DataBatchAuthorizationDecision.DEPENDENCY_UNAVAILABLE)) {
            MemoryPorts ports = new MemoryPorts();
            DataBatchCommandService service = service(
                    ports, DataBatchAssessmentOutcome.PASSED);
            service.receive(receive(context("idem-final-object-root-" + decision)));
            service.seal(new SealDataBatchCommand(
                    BATCH_ID, 1, manifest(), context("idem-final-object-seal-" + decision)));
            int ownerCallsBefore = ports.transactionCalls.get();
            ports.authorizationDecisions.add(DataBatchAuthorizationDecision.ALLOW);
            ports.authorizationDecisions.add(DataBatchAuthorizationDecision.ALLOW);
            ports.authorizationDecisions.add(decision);

            IngestionQualityApplicationException failure = assertThrows(
                    IngestionQualityApplicationException.class,
                    () -> service.evaluate(new EvaluateDataBatchCommand(
                            BATCH_ID, 2, context("idem-final-object-evaluate-" + decision))));

            assertEquals(
                    decision == DataBatchAuthorizationDecision.DENY
                            ? "INGESTION_QUALITY_FORBIDDEN"
                            : "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE",
                    failure.code());
            assertEquals(ownerCallsBefore, ports.transactionCalls.get());
            assertTrue(ports.snapshots.byBatch.isEmpty());
            assertEquals(DataBatchStatus.SEALED, ports.byId.get(BATCH_ID).status());
        }
    }

    @Test
    void receiveIdentityWinnerWithDifferentAuditBindingRollsBackThisCommand() {
        MemoryPorts ports = new MemoryPorts();
        ReceiveDataBatchCommand command = receive(context("idem-receive-winner-race"));
        UUID winnerId = uuid("019ff200-0000-7000-8000-000000000099");
        ports.receiveWinnerAtOwner = DataBatch.receiving(
                winnerId, command.identity(), command.lineage(),
                command.declaredManifestDigest(), NOW,
                "ffeeddccbbaa99887766554433221100");

        IngestionQualityApplicationException invalid = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service(ports, DataBatchAssessmentOutcome.PASSED).receive(command));

        assertEquals("INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID", invalid.code());
        assertEquals(winnerId, ports.byId.values().iterator().next().batchId());
        assertEquals(0, ports.insertCalls.get());
        assertTrue(ports.audits.isEmpty());
        assertTrue(ports.idempotency.isEmpty());
    }

    @Test
    void acceptedCommitIsReturnedButAReplayStillUsesCurrentObjectAuthorization() {
        MemoryPorts ports = new MemoryPorts();
        ports.denyAuthorizationAfterOwnerCommit = true;
        ReceiveDataBatchCommand command = receive(context("idem-accepted-auth-race"));
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);

        DataBatchView accepted = service.receive(command);

        assertEquals(DataBatchStatus.RECEIVING, accepted.status());
        assertEquals(1, ports.audits.size());
        IngestionQualityApplicationException replayDenied = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(command));
        assertEquals("INGESTION_QUALITY_FORBIDDEN", replayDenied.code());
        assertEquals(1, ports.transactionCalls.get());
    }

    @Test
    void technicalAssessmentFailureLeavesBatchSealedAndRetryable() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, null);
        service.receive(receive(context("idem-receive")));
        service.seal(new SealDataBatchCommand(
                BATCH_ID, 1, manifest(), context("idem-seal")));

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.evaluate(new EvaluateDataBatchCommand(
                        BATCH_ID, 2, context("idem-evaluate-technical"))));

        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", failure.code());
        assertEquals(DataBatchStatus.SEALED, ports.byId.get(BATCH_ID).status());
        assertEquals(2, ports.byId.get(BATCH_ID).aggregateVersion());
        assertEquals(1, ports.saveCalls.get(), "only seal may have been saved");
        assertEquals(2, ports.audits.size());
        assertFalse(ports.idempotency.keySet().stream()
                .anyMatch(scope -> scope.idempotencyKey().equals("idem-evaluate-technical")));
    }

    @Test
    void deniedOrUnavailableWorkloadAuthorizationFailsClosed() {
        MemoryPorts deniedPorts = new MemoryPorts();
        deniedPorts.authorization = DataBatchAuthorizationDecision.DENY;
        IngestionQualityApplicationException denied = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service(deniedPorts, DataBatchAssessmentOutcome.PASSED)
                        .receive(receive(context("idem-denied"))));
        assertEquals("INGESTION_QUALITY_FORBIDDEN", denied.code());
        assertTrue(deniedPorts.byId.isEmpty());

        MemoryPorts unavailablePorts = new MemoryPorts();
        unavailablePorts.authorization = DataBatchAuthorizationDecision.DEPENDENCY_UNAVAILABLE;
        IngestionQualityApplicationException unavailable = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service(unavailablePorts, DataBatchAssessmentOutcome.PASSED)
                        .receive(receive(context("idem-unavailable"))));
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable.code());
        assertTrue(unavailablePorts.byId.isEmpty());
    }

    @Test
    void receiveRechecksResolvedBatchAuthorizationBeforeAtomicOwnerCall() {
        MemoryPorts ports = new MemoryPorts();
        ports.authorizationDecisions.add(DataBatchAuthorizationDecision.ALLOW);
        ports.authorizationDecisions.add(DataBatchAuthorizationDecision.DENY);

        IngestionQualityApplicationException denied = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service(ports, DataBatchAssessmentOutcome.PASSED)
                        .receive(receive(context("idem-revoked"))));

        assertEquals("INGESTION_QUALITY_FORBIDDEN", denied.code());
        assertTrue(ports.byId.isEmpty());
        assertTrue(ports.audits.isEmpty());
        assertTrue(ports.idempotency.isEmpty());
        assertEquals(0, ports.transactionCalls.get(),
                "a denied resolved-batch recheck must precede the atomic owner call");
    }

    @Test
    void completedReplayStillRequiresCurrentWorkloadAuthorization() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        ReceiveDataBatchCommand command = receive(context("idem-replay-auth"));
        service.receive(command);
        ports.authorization = DataBatchAuthorizationDecision.DENY;

        IngestionQualityApplicationException denied = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(command));

        assertEquals("INGESTION_QUALITY_FORBIDDEN", denied.code());
        assertEquals(1, ports.insertCalls.get());
        assertEquals(1, ports.transactionCalls.get());
    }

    @Test
    void receiveBusinessReplayAuthorizesTheExistingBatchItActuallyReturns() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        ReceiveDataBatchCommand original = receive(context("idem-existing-owner"));
        service.receive(original);
        ports.deniedAuthorizationBatchId = BATCH_ID;

        ReceiveDataBatchCommand requestedReplacement = new ReceiveDataBatchCommand(
                uuid("019ff200-0000-7000-8000-000000000090"),
                0,
                original.identity(),
                original.lineage(),
                original.declaredManifestDigest(),
                context("idem-existing-replay"));
        IngestionQualityApplicationException denied = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(requestedReplacement));

        assertEquals("INGESTION_QUALITY_FORBIDDEN", denied.code());
        assertTrue(ports.authorizationRequests.stream()
                .anyMatch(request -> BATCH_ID.equals(request.batchId())),
                "authorization must bind to the existing batch returned by business replay");
        assertEquals(1, ports.audits.size());
    }

    @Test
    void completedReceiveReplayAuthorizesTheCurrentAggregateVersion() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        ReceiveDataBatchCommand original = receive(context("idem-current-version"));
        DataBatchView first = service.receive(original);
        service.seal(new SealDataBatchCommand(
                BATCH_ID, 1, manifest(), context("idem-current-version-seal")));
        ports.authorizationRequests.clear();

        DataBatchView replay = service.receive(original);

        assertEquals(first, replay);
        assertEquals(2, ports.authorizationRequests.getLast().aggregateVersion());
        assertEquals(BATCH_ID, ports.authorizationRequests.getLast().batchId());
    }

    @Test
    void rawAssessmentAndAuditDependencyFailuresUseStableErrorCode() {
        MemoryPorts assessmentPorts = new MemoryPorts();
        DataBatchCommandService assessmentService = serviceWithMeasurement(
                assessmentPorts,
                (batch, definitions) -> {
                    throw new IllegalStateException("backend unavailable");
                });
        assessmentService.receive(receive(context("idem-assessment-root")));
        assessmentService.seal(new SealDataBatchCommand(
                BATCH_ID, 1, manifest(), context("idem-assessment-seal")));

        IngestionQualityApplicationException assessmentFailure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> assessmentService.evaluate(new EvaluateDataBatchCommand(
                        BATCH_ID, 2, context("idem-assessment-raw"))));
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", assessmentFailure.code());

        MemoryPorts auditPorts = new MemoryPorts();
        auditPorts.failAppend = true;
        IngestionQualityApplicationException auditFailure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service(auditPorts, DataBatchAssessmentOutcome.PASSED)
                        .receive(receive(context("idem-audit-raw"))));
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", auditFailure.code());

        MemoryPorts healthPorts = new MemoryPorts();
        healthPorts.failHealth = true;
        IngestionQualityApplicationException healthFailure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service(healthPorts, DataBatchAssessmentOutcome.PASSED)
                        .receive(receive(context("idem-audit-health"))));
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", healthFailure.code());
        assertEquals(1, healthPorts.transactionCalls.get(),
                "health is checked after the authoritative claim so a claim race cannot mask mismatch");
        assertTrue(healthPorts.byId.isEmpty());
        assertTrue(healthPorts.idempotency.isEmpty());
    }

    @Test
    void businessReplayConflictRegressionAndDirectSuccessorAreFailClosed() {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        DataBatchView original = service.receive(receive(context("idem-root")));
        DataBatch predecessorBefore = ports.byId.get(BATCH_ID);

        DataBatchView replay = service.receive(new ReceiveDataBatchCommand(
                uuid("019ff200-0000-7000-8000-000000000010"), 0,
                original.identity(), BatchLineage.root(LINEAGE_ID, NOW), MANIFEST_DIGEST,
                context("idem-business-replay")));
        assertEquals(BATCH_ID, replay.batchId());
        assertEquals(1, ports.insertCalls.get());

        IngestionQualityApplicationException identityConflict = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(new ReceiveDataBatchCommand(
                        uuid("019ff200-0000-7000-8000-000000000011"), 0,
                        original.identity(), BatchLineage.root(LINEAGE_ID, NOW),
                        "sha256:" + "b".repeat(64), context("idem-identity-conflict"))));
        assertEquals("INGESTION_QUALITY_BATCH_IDENTITY_CONFLICT", identityConflict.code());

        IngestionQualityApplicationException regression = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(new ReceiveDataBatchCommand(
                        uuid("019ff200-0000-7000-8000-000000000012"), 0,
                        new BatchIdentity(
                                original.identity().sourceId(),
                                original.identity().businessKey(), 6),
                        BatchLineage.root(
                                uuid("019ff200-0000-7000-8000-000000000020"), NOW),
                        "sha256:" + "c".repeat(64), context("idem-regression"))));
        assertEquals("INGESTION_QUALITY_SOURCE_VERSION_REGRESSION", regression.code());

        IngestionQualityApplicationException newRoot = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(new ReceiveDataBatchCommand(
                        uuid("019ff200-0000-7000-8000-000000000015"), 0,
                        new BatchIdentity(
                                original.identity().sourceId(),
                                original.identity().businessKey(), 8),
                        BatchLineage.root(
                                uuid("019ff200-0000-7000-8000-000000000021"), NOW),
                        "sha256:" + "f".repeat(64), context("idem-new-root"))));
        assertEquals("INGESTION_QUALITY_CORRECTION_INVALID", newRoot.code());

        IngestionQualityApplicationException earlySuccessor = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(new ReceiveDataBatchCommand(
                        uuid("019ff200-0000-7000-8000-000000000016"), 0,
                        new BatchIdentity(
                                original.identity().sourceId(),
                                original.identity().businessKey(), 8),
                        BatchLineage.successor(
                                LINEAGE_ID, BATCH_ID,
                                cn.edu.suda.scholarsense.ingestionquality.domain
                                        .BatchCorrectionReason.LATE_ARRIVAL,
                                NOW.minusNanos(1)),
                        "sha256:" + "0".repeat(64), context("idem-early-successor"))));
        assertEquals("INGESTION_QUALITY_CORRECTION_INVALID", earlySuccessor.code());

        UUID successorId = uuid("019ff200-0000-7000-8000-000000000013");
        DataBatchView successor = service.receive(new ReceiveDataBatchCommand(
                successorId, 0,
                new BatchIdentity(
                        original.identity().sourceId(), original.identity().businessKey(), 8),
                BatchLineage.successor(
                        LINEAGE_ID, BATCH_ID,
                        cn.edu.suda.scholarsense.ingestionquality.domain.BatchCorrectionReason
                                .LATE_ARRIVAL,
                        NOW.plusSeconds(1)),
                "sha256:" + "d".repeat(64), context("idem-successor")));
        assertEquals(DataBatchStatus.RECEIVING, successor.status());
        assertEquals(BATCH_ID, successor.lineage().supersedesBatchId());
        assertSame(predecessorBefore, ports.byId.get(BATCH_ID),
                "creating a successor must not mutate its predecessor");

        IngestionQualityApplicationException fork = assertThrows(
                IngestionQualityApplicationException.class,
                () -> service.receive(new ReceiveDataBatchCommand(
                        uuid("019ff200-0000-7000-8000-000000000014"), 0,
                        new BatchIdentity(
                                original.identity().sourceId(),
                                original.identity().businessKey(), 9),
                        BatchLineage.successor(
                                LINEAGE_ID, BATCH_ID,
                                cn.edu.suda.scholarsense.ingestionquality.domain
                                        .BatchCorrectionReason.SOURCE_CORRECTION,
                                NOW.plusSeconds(2)),
                        "sha256:" + "e".repeat(64), context("idem-fork"))));
        assertEquals("INGESTION_QUALITY_CORRECTION_FORK", fork.code());
        assertEquals(successorId, ports.lineageHead(LINEAGE_ID).orElseThrow().batchId());
    }

    @Test
    void concurrentSealWithOneExpectedVersionHasExactlyOneWinner() throws Exception {
        MemoryPorts ports = new MemoryPorts();
        DataBatchCommandService service = service(ports, DataBatchAssessmentOutcome.PASSED);
        service.receive(receive(context("idem-root")));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = sealAttempt(
                    service, "idem-concurrent-a", ready, start);
            Callable<Object> second = sealAttempt(
                    service, "idem-concurrent-b", ready, start);
            Future<Object> firstResult = executor.submit(first);
            Future<Object> secondResult = executor.submit(second);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Object> results = List.of(firstResult.get(), secondResult.get());

            assertEquals(1, results.stream().filter(DataBatchView.class::isInstance).count());
            assertEquals(1, results.stream()
                    .filter(IngestionQualityApplicationException.class::isInstance)
                    .map(IngestionQualityApplicationException.class::cast)
                    .filter(error -> "INGESTION_QUALITY_VERSION_CONFLICT".equals(error.code()))
                    .count());
            assertEquals(DataBatchStatus.SEALED, ports.byId.get(BATCH_ID).status());
            assertEquals(2, ports.byId.get(BATCH_ID).aggregateVersion());
            assertEquals(1, ports.saveCalls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<Object> sealAttempt(
            DataBatchCommandService service,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return service.seal(new SealDataBatchCommand(
                        BATCH_ID, 1, manifest(), context(idempotencyKey)));
            } catch (IngestionQualityApplicationException failure) {
                return failure;
            }
        };
    }

    private static DataBatchCommandService service(
            MemoryPorts ports, DataBatchAssessmentOutcome outcome) {
        return serviceWithWorkload(
                ports, outcome,
                DataBatchWorkloadAuthorizationTestFixture.guard("quality-worker-a"));
    }

    private static DataBatchCommandService serviceWithWorkload(
            MemoryPorts ports,
            DataBatchAssessmentOutcome outcome,
            DataBatchWorkloadAuthorizationGuard workloadAuthorization) {
        return serviceWithMeasurement(ports, (batch, definitions) -> {
            if (outcome == null) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
            }
            return new QualityMeasurement(
                    QualityMeasurementAnchor.from(batch),
                    measurements(
                            definitions, outcome == DataBatchAssessmentOutcome.FAILED),
                    List.of("student-status"));
        }, workloadAuthorization);
    }

    private static DataBatchCommandService serviceWithMeasurement(
            MemoryPorts ports,
            QualityMeasurementPort measurement) {
        return serviceWithMeasurement(
                ports, measurement,
                DataBatchWorkloadAuthorizationTestFixture.guard("quality-worker-a"));
    }

    private static DataBatchCommandService serviceWithMeasurement(
            MemoryPorts ports,
            QualityMeasurementPort measurement,
            DataBatchWorkloadAuthorizationGuard workloadAuthorization) {
        return serviceWithMeasurement(ports, measurement, workloadAuthorization, TIME);
    }

    private static DataBatchCommandService serviceWithMeasurement(
            MemoryPorts ports,
            QualityMeasurementPort measurement,
            DataBatchWorkloadAuthorizationGuard workloadAuthorization,
            TrustedTimeSource time) {
        ExecutableQualityPolicyGuard guard = new ExecutableQualityPolicyGuard(() -> CONTRACT);
        MemorySnapshots snapshots = new MemorySnapshots();
        AtomicInteger identifiers = new AtomicInteger();
        QualitySnapshotIdPort ids = ignored -> uuid(String.format(
                "019ff200-0000-7000-8000-%012d", identifiers.incrementAndGet() + 100));
        DataBatchQualityEvaluationService evaluation = new DataBatchQualityEvaluationService(
                guard, measurement, ports, snapshots, ids);
        ports.snapshots = snapshots;
        return new DataBatchCommandService(
                ports, snapshots, ports, ports, evaluation, guard, ports,
                workloadAuthorization,
                new DataBatchCanonicalOutboxFactory("scholarsense_iq_test_worker"), time);
    }

    private static DataBatchWorkloadAuthorizationGuard failingRevalidation(
            DataBatchWorkloadAuthorizationResult.Status status) {
        return new DataBatchWorkloadAuthorizationGuard(
                new DataBatchWorkloadAuthorizationPort() {
                    @Override
                    public DataBatchWorkloadAuthorizationResult capture(
                            DataBatchWorkloadAuthorizationRequest request) {
                        return DataBatchWorkloadAuthorizationResult.allow(
                                new DataBatchWorkloadAuthorizationEvidence(
                                        "test-fixture-deployment-input-required",
                                        "quality-worker-a",
                                        "spiffe://test.invalid/scholarsense/"
                                                + "ingestion-quality/quality-worker",
                                        request.audience(), request.capabilities(), 1,
                                        DataBatchWorkloadAuthorizationGuard.POLICY_VERSION,
                                        "sha256:" + "7".repeat(64),
                                        request.currentTime().minusSeconds(1),
                                        request.currentTime().plusSeconds(60), null),
                                1);
                    }

                    @Override
                    public DataBatchWorkloadAuthorizationResult revalidate(
                            DataBatchWorkloadAuthorizationEvidence captured,
                            DataBatchWorkloadAuthorizationRequest request) {
                        return status == DataBatchWorkloadAuthorizationResult.Status.DENY
                                ? DataBatchWorkloadAuthorizationResult.deny(2)
                                : DataBatchWorkloadAuthorizationResult.dependencyUnavailable();
                    }
                });
    }

    private static Map<String, MeasuredQualityInputs> measurements(
            List<MetricDefinition> definitions,
            boolean failFirst) {
        LinkedHashMap<String, MeasuredQualityInputs> result = new LinkedHashMap<>();
        for (int index = 0; index < definitions.size(); index++) {
            MetricDefinition definition = definitions.get(index);
            LinkedHashMap<String, BigInteger> operands = new LinkedHashMap<>();
            if (definition.calculation().numerator().operandId() != null) {
                operands.put(
                        definition.calculation().numerator().operandId(),
                        failFirst && index == 0
                                ? BigInteger.ZERO
                                : BigInteger.valueOf(definition.thresholdNumerator()));
            }
            if (definition.calculation().denominator().operandId() != null) {
                operands.put(
                        definition.calculation().denominator().operandId(),
                        BigInteger.valueOf(definition.thresholdDenominator()));
            }
            result.put(definition.formulaId(), new MeasuredQualityInputs(true, operands));
        }
        return Map.copyOf(result);
    }

    private static ReceiveDataBatchCommand receive(DataBatchCommandContext context) {
        return new ReceiveDataBatchCommand(
                BATCH_ID,
                0,
                new BatchIdentity("SRC-P0-STUDENT-001", "student-status:2026-08-09", 7),
                BatchLineage.root(LINEAGE_ID, NOW),
                MANIFEST_DIGEST,
                context);
    }

    private static DataBatchCommandContext context(String key) {
        return context(key, "00112233445566778899aabbccddeeff");
    }

    private static DataBatchCommandContext context(String key, String traceId) {
        return new DataBatchCommandContext(
                "tenant-a", "quality-worker-a", key, traceId);
    }

    private static BatchManifest manifest() {
        SourcePolicy source = CONTRACT.policy().sources().stream()
                .filter(candidate -> candidate.sourceId().equals("SRC-P0-STUDENT-001"))
                .findFirst().orElseThrow();
        Instant cutoffAt = NOW.plusSeconds(1);
        return new BatchManifest(
                10, 9, 1,
                new BatchObservationWindow(cutoffAt.minusSeconds(720L * 3_600L), cutoffAt),
                cutoffAt, "Asia/Shanghai", "sha256:" + "8".repeat(64),
                source.schemaBinding().version(), source.schemaBinding().canonicalDigest(),
                CONTRACT.policy().controlledInputs().dataCatalog().version(),
                CONTRACT.policy().controlledInputs().dataCatalog().canonicalDigest(),
                CONTRACT.policy().controlledInputs().qualityGate().version(),
                CONTRACT.policy().controlledInputs().qualityGate().canonicalDigest(),
                CONTRACT.policy().profileVersion(),
                CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                NOW.minusSeconds(1), NOW.plusSeconds(60), NOW,
                source.freshnessLanes().getFirst().laneId(), MANIFEST_DIGEST);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static final class MemorySnapshots implements QualitySnapshotRepository {
        private final Map<UUID, QualitySnapshot> byBatch = new ConcurrentHashMap<>();

        @Override
        public Optional<QualitySnapshot> findByBatchId(UUID batchId) {
            return Optional.ofNullable(byBatch.get(batchId));
        }

        @Override
        public void insert(VerifiedQualitySnapshot verified) {
            QualitySnapshot snapshot = verified.value();
            if (byBatch.putIfAbsent(snapshot.batchId(), snapshot) != null) {
                throw new IllegalStateException("duplicate snapshot for batch");
            }
        }
    }

    private static final class MemoryPorts implements
            DataBatchRepository,
            DataBatchIdempotencyPort,
            DataBatchTransactionPort,
            DataBatchAuthorizationPort,
            DataBatchAuditPort,
            DataBatchCommandReplayPort,
            DataBatchAtomicCommandPort {
        private final Map<UUID, DataBatch> byId = new ConcurrentHashMap<>();
        private final Map<DataBatchIdempotencyScope, DataBatchIdempotencyResult> idempotency =
                new ConcurrentHashMap<>();
        private final List<DataBatchAuditEvent> audits =
                Collections.synchronizedList(new java.util.ArrayList<>());
        private final AtomicInteger transactionCalls = new AtomicInteger();
        private DataBatchAuthorizationDecision authorization = DataBatchAuthorizationDecision.ALLOW;
        private final Queue<DataBatchAuthorizationDecision> authorizationDecisions =
                new ConcurrentLinkedQueue<>();
        private final List<DataBatchAuthorizationRequest> authorizationRequests =
                Collections.synchronizedList(new java.util.ArrayList<>());
        private UUID deniedAuthorizationBatchId;
        private boolean failHealth;
        private boolean failAppend;
        private boolean hideNextPrecedenceInspection;
        private boolean failNextOwnerClaimWithMismatch;
        private boolean denyAuthorizationAfterOwnerCommit;
        private DataBatch receiveWinnerAtOwner;
        private ReceiveDataBatchAtomicCommand lastReceiveCommand;
        private SealDataBatchAtomicCommand lastSealCommand;
        private CommitDataBatchQualityEvaluationCommand lastEvaluationCommand;
        private PublishDataBatchAtomicCommand lastPublishCommand;
        private final AtomicInteger precedenceInspectionCalls = new AtomicInteger();
        private final AtomicInteger claimCalls = new AtomicInteger();
        private final AtomicInteger insertCalls = new AtomicInteger();
        private final AtomicInteger saveCalls = new AtomicInteger();
        private MemorySnapshots snapshots;

        @Override
        public Optional<DataBatch> find(UUID batchId) {
            return Optional.ofNullable(byId.get(batchId));
        }

        @Override
        public Optional<DataBatch> findByIdentity(BatchIdentity identity) {
            return byId.values().stream().filter(batch -> batch.identity().equals(identity)).findFirst();
        }

        @Override
        public Optional<DataBatch> latestForBusinessKey(String sourceId, String businessKey) {
            return byId.values().stream()
                    .filter(batch -> batch.identity().sourceId().equals(sourceId)
                            && batch.identity().businessKey().equals(businessKey))
                    .max(java.util.Comparator.comparingLong(
                            batch -> batch.identity().sourceVersion()));
        }

        @Override
        public Optional<DataBatch> lineageHead(UUID lineageId) {
            return byId.values().stream()
                    .filter(batch -> batch.lineage().lineageId().equals(lineageId))
                    .max(java.util.Comparator.comparingLong(
                            batch -> batch.identity().sourceVersion()));
        }

        @Override
        public void insert(DataBatch batch) {
            if (byId.putIfAbsent(batch.batchId(), batch) != null) {
                throw new DataBatchVersionConflictException(
                        byId.get(batch.batchId()).aggregateVersion());
            }
            insertCalls.incrementAndGet();
        }

        @Override
        public void save(DataBatch batch, long expectedVersion) {
            byId.compute(batch.batchId(), (ignored, current) -> {
                if (current == null || current.aggregateVersion() != expectedVersion) {
                    throw new DataBatchVersionConflictException(
                            current == null ? 0 : current.aggregateVersion());
                }
                return batch;
            });
            saveCalls.incrementAndGet();
        }

        @Override
        public Optional<DataBatchIdempotencyResult> find(
                DataBatchIdempotencyScope scope, Instant at) {
            DataBatchIdempotencyResult result = idempotency.get(scope);
            return result == null || !at.isBefore(result.expiresAt())
                    ? Optional.empty() : Optional.of(result);
        }

        @Override
        public DataBatchCommandPrecedence inspect(
                DataBatchIdempotencyScope scope, String requestDigest) {
            precedenceInspectionCalls.incrementAndGet();
            if (hideNextPrecedenceInspection) {
                hideNextPrecedenceInspection = false;
                return DataBatchCommandPrecedence.fresh();
            }
            DataBatchIdempotencyResult existing = idempotency.get(scope);
            if (existing == null || !NOW.isBefore(existing.expiresAt())) {
                return DataBatchCommandPrecedence.fresh();
            }
            return existing.requestDigest().equals(requestDigest)
                    ? DataBatchCommandPrecedence.replay(existing)
                    : DataBatchCommandPrecedence.mismatch();
        }

        @Override
        public DataBatchAtomicCommandResult receive(ReceiveDataBatchAtomicCommand command) {
            lastReceiveCommand = command;
            return atomic(command.commit(), () -> {
                if (receiveWinnerAtOwner != null) {
                    byId.putIfAbsent(receiveWinnerAtOwner.batchId(), receiveWinnerAtOwner);
                }
                DataBatch value = findByIdentity(command.receivedBatch().identity())
                        .orElse(command.receivedBatch());
                CanonicalOutboxPayload actualBinding =
                        new DataBatchCanonicalOutboxFactory("scholarsense_iq_test_worker")
                                .create(
                                        DataBatchCommandType.RECEIVE,
                                        DataBatchView.from(value), command.commit());
                if (!actualBinding.auditDigest().equals(command.outbox().auditDigest())) {
                    throw new IngestionQualityApplicationException(
                            "INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID");
                }
                if (!byId.containsKey(value.batchId())) insert(value);
                return DataBatchView.from(value);
            });
        }

        @Override
        public DataBatchAtomicCommandResult seal(SealDataBatchAtomicCommand command) {
            lastSealCommand = command;
            return atomic(command.commit(), () -> {
                save(command.sealedBatch(), command.expectedAggregateVersion());
                return DataBatchView.from(command.sealedBatch());
            });
        }

        @Override
        public DataBatchAtomicCommandResult commitQualityEvaluation(
                CommitDataBatchQualityEvaluationCommand command) {
            lastEvaluationCommand = command;
            return atomic(command.commit(), () -> {
                save(command.assessment().updatedBatch(), command.expectedAggregateVersion());
                snapshots.insert(command.assessment().snapshot());
                return DataBatchView.from(command.assessment().updatedBatch());
            });
        }

        @Override
        public DataBatchAtomicCommandResult publish(PublishDataBatchAtomicCommand command) {
            lastPublishCommand = command;
            return atomic(command.commit(), () -> {
                save(command.publishedBatch(), command.expectedAggregateVersion());
                return DataBatchView.from(command.publishedBatch());
            });
        }

        private DataBatchAtomicCommandResult atomic(
                DataBatchAtomicCommitContext commit,
                java.util.function.Supplier<DataBatchView> fresh) {
            transactionCalls.incrementAndGet();
            claimCalls.incrementAndGet();
            if (failNextOwnerClaimWithMismatch) {
                failNextOwnerClaimWithMismatch = false;
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH");
            }
            DataBatchIdempotencyResult existing = idempotency.get(commit.idempotencyScope());
            if (existing != null
                    && commit.occurredAt().instant().isBefore(existing.expiresAt())) {
                if (!existing.requestDigest().equals(commit.requestDigest())) {
                    throw new IngestionQualityApplicationException(
                            "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH");
                }
                return new DataBatchAtomicCommandResult(
                        DataBatchAtomicCommandResult.Status.REPLAY, existing.response());
            }
            if (failHealth || failAppend) {
                throw new IllegalStateException("audit unavailable");
            }
            DataBatchView response = fresh.get();
            var fact = commit.commandId();
            audits.add(new DataBatchAuditEvent(
                    commit.idempotencyScope().commandType().action(), "accepted",
                    response.batchId(), response.aggregateVersion(),
                    commit.idempotencyScope().actorRef(), commit.traceId(),
                    commit.occurredAt().instant(), commit.occurredAt().profile(),
                    commit.requestDigest()));
            idempotency.put(commit.idempotencyScope(), new DataBatchIdempotencyResult(
                    commit.idempotencyScope(), commit.requestDigest(), response,
                    commit.occurredAt().instant(),
                    commit.occurredAt().instant().plus(DataBatchCommandService.IDEMPOTENCY_RETENTION)));
            if (denyAuthorizationAfterOwnerCommit) {
                denyAuthorizationAfterOwnerCommit = false;
                authorization = DataBatchAuthorizationDecision.DENY;
            }
            return new DataBatchAtomicCommandResult(
                    DataBatchAtomicCommandResult.Status.ACCEPTED, response);
        }

        @Override
        public DataBatchIdempotencyClaim claim(
                DataBatchIdempotencyScope scope, String requestDigest, Instant at) {
            claimCalls.incrementAndGet();
            DataBatchIdempotencyResult existing = idempotency.get(scope);
            if (existing == null || !at.isBefore(existing.expiresAt())) {
                return DataBatchIdempotencyClaim.fresh();
            }
            return existing.requestDigest().equals(requestDigest)
                    ? DataBatchIdempotencyClaim.replay(existing)
                    : DataBatchIdempotencyClaim.mismatch();
        }

        @Override
        public void complete(DataBatchIdempotencyResult result) {
            idempotency.put(result.scope(), result);
        }

        @Override
        public DataBatchView execute(java.util.function.Supplier<DataBatchView> work) {
            transactionCalls.incrementAndGet();
            return work.get();
        }

        @Override
        public DataBatchAuthorizationDecision authorize(DataBatchAuthorizationRequest request) {
            authorizationRequests.add(request);
            if (request.batchId().equals(deniedAuthorizationBatchId)) {
                return DataBatchAuthorizationDecision.DENY;
            }
            DataBatchAuthorizationDecision next = authorizationDecisions.poll();
            return next == null ? authorization : next;
        }

        @Override
        public void requireHealthy(String traceId, Instant at) {
            if (failHealth) throw new IllegalStateException("audit unavailable");
        }

        @Override
        public void append(DataBatchAuditEvent event) {
            if (failAppend) throw new IllegalStateException("audit append unavailable");
            audits.add(event);
        }
    }
}
