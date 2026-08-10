package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import cn.edu.suda.scholarsense.ingestionquality.domain.SealedQualityContractEvidence;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Task 3.3 application contract for the quality-worker authorization channel.
 *
 * <p>All identity values below are test fixtures. Production environment, principal and mTLS SAN
 * values remain deployment inputs and this test deliberately supplies no production default.
 */
class DataBatchWorkloadAuthorizationGuardContractTest {
    private static final String AUDIENCE =
            "urn:scholarsense:ingestion-quality:data-batch-commands";
    private static final String ENVIRONMENT = "test-fixture-deployment-input-required";
    private static final String PRINCIPAL = "test-fixture-quality-worker";
    private static final String MTLS_SAN =
            "spiffe://test.invalid/scholarsense/ingestion-quality/quality-worker";
    private static final String POLICY_VERSION =
            "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0";
    private static final String POLICY_DIGEST = "sha256:" + "9".repeat(64);
    private static final long GENERATION = 7;
    private static final long MAX_SAFE_GENERATION = 9_007_199_254_740_991L;
    private static final Instant CAPTURE_TIME = Instant.parse("2026-08-10T02:00:00Z");
    private static final Instant REVALIDATE_TIME = CAPTURE_TIME.plusSeconds(2);
    private static final UUID BATCH_ID = uuid("019ff300-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019ff300-0000-7000-8000-000000000002");
    private static final String MANIFEST_DIGEST = "sha256:" + "a".repeat(64);
    private static final VerifiedQualityContract QUALITY_CONTRACT =
            new FrozenExecutableQualityPolicyLoader(
                    Path.of("..").toAbsolutePath().normalize()).loadVerified();

    @Test
    void guardBuildsTheExactAudienceAndOneCapabilityPerCommandWithoutForwardingActorRef() {
        RecordingWorkloadAuthorizationPort port = new RecordingWorkloadAuthorizationPort();
        DataBatchWorkloadAuthorizationGuard guard =
                new DataBatchWorkloadAuthorizationGuard(port);
        Map<DataBatchCommandType, String> expected = Map.of(
                DataBatchCommandType.RECEIVE, "data-batch.receive",
                DataBatchCommandType.SEAL, "data-batch.seal",
                DataBatchCommandType.EVALUATE, "data-batch.evaluate",
                DataBatchCommandType.PUBLISH, "data-batch.publish");

        for (var entry : expected.entrySet()) {
            DataBatchWorkloadAuthorizationEvidence captured = guard.capture(
                    entry.getKey(), PRINCIPAL, CAPTURE_TIME);
            guard.revalidate(captured, entry.getKey(), REVALIDATE_TIME);
        }

        assertEquals(List.of(
                "commandType", "audience", "capabilities", "currentTime"),
                recordFields(DataBatchWorkloadAuthorizationRequest.class));
        assertEquals(List.of(
                "environment", "principalRef", "mtlsSanUriRef", "audience",
                "capabilities", "authorizationGeneration", "policyVersion",
                "policyDigest", "effectiveAt", "expiresAt", "revokedAt"),
                recordFields(DataBatchWorkloadAuthorizationEvidence.class));
        assertEquals(4, port.captureRequests.size());
        assertEquals(4, port.revalidationRequests.size());
        for (int index = 0; index < port.captureRequests.size(); index++) {
            DataBatchWorkloadAuthorizationRequest capture = port.captureRequests.get(index);
            DataBatchWorkloadAuthorizationRequest revalidation =
                    port.revalidationRequests.get(index);
            assertEquals(AUDIENCE, capture.audience());
            assertEquals(Set.of(expected.get(capture.commandType())), capture.capabilities());
            assertEquals(CAPTURE_TIME, capture.currentTime());
            assertEquals(capture.commandType(), revalidation.commandType());
            assertEquals(capture.audience(), revalidation.audience());
            assertEquals(capture.capabilities(), revalidation.capabilities());
            assertEquals(REVALIDATE_TIME, revalidation.currentTime());
        }
    }

    @Test
    void evidenceHasACompleteBoundedShapeAndAJavaScriptSafeAuthorizationGeneration() {
        assertDoesNotThrow(() -> evidence(
                Set.of("data-batch.evaluate"), MAX_SAFE_GENERATION,
                CAPTURE_TIME.minusSeconds(1), CAPTURE_TIME.plusSeconds(30), null));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                Set.of("data-batch.evaluate"), 0,
                CAPTURE_TIME.minusSeconds(1), CAPTURE_TIME.plusSeconds(30), null));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                Set.of("data-batch.evaluate"), MAX_SAFE_GENERATION + 1,
                CAPTURE_TIME.minusSeconds(1), CAPTURE_TIME.plusSeconds(30), null));
        assertThrows(IllegalArgumentException.class, () -> new DataBatchWorkloadAuthorizationEvidence(
                " ", PRINCIPAL, MTLS_SAN, AUDIENCE, Set.of("data-batch.evaluate"),
                GENERATION, POLICY_VERSION, POLICY_DIGEST,
                CAPTURE_TIME.minusSeconds(1), CAPTURE_TIME.plusSeconds(30), null));
        assertThrows(IllegalArgumentException.class, () -> new DataBatchWorkloadAuthorizationEvidence(
                ENVIRONMENT, PRINCIPAL, MTLS_SAN, AUDIENCE, Set.of(),
                GENERATION, POLICY_VERSION, POLICY_DIGEST,
                CAPTURE_TIME.minusSeconds(1), CAPTURE_TIME.plusSeconds(30), null));
    }

    @Test
    void guardRejectsSpoofedActorsWrongScopeAndExactEffectivityBoundaries() {
        RecordingWorkloadAuthorizationPort port = new RecordingWorkloadAuthorizationPort();
        DataBatchWorkloadAuthorizationGuard guard =
                new DataBatchWorkloadAuthorizationGuard(port);

        assertCode("INGESTION_QUALITY_FORBIDDEN", () -> guard.capture(
                DataBatchCommandType.EVALUATE, "request-supplied-spoof", CAPTURE_TIME));
        assertEquals(1, port.captureRequests.size());
        assertFalse(recordFields(DataBatchWorkloadAuthorizationRequest.class)
                .contains("actorRef"), "request actorRef must never reach the mTLS authority port");

        List<DataBatchWorkloadAuthorizationEvidence> inactive = List.of(
                evidence(Set.of("data-batch.evaluate"), GENERATION,
                        CAPTURE_TIME.plusNanos(1_000), CAPTURE_TIME.plusSeconds(30), null),
                evidence(Set.of("data-batch.evaluate"), GENERATION,
                        CAPTURE_TIME.minusSeconds(1), CAPTURE_TIME, null),
                evidence(Set.of("data-batch.evaluate"), GENERATION,
                        CAPTURE_TIME.minusSeconds(1), CAPTURE_TIME.plusSeconds(30), CAPTURE_TIME));
        for (DataBatchWorkloadAuthorizationEvidence candidate : inactive) {
            port.nextCapture = DataBatchWorkloadAuthorizationResult.allow(candidate, GENERATION);
            assertCode("INGESTION_QUALITY_FORBIDDEN", () -> guard.capture(
                    DataBatchCommandType.EVALUATE, PRINCIPAL, CAPTURE_TIME));
        }

        port.nextCapture = DataBatchWorkloadAuthorizationResult.allow(
                copy(baseEvidence(Set.of("data-batch.evaluate")),
                        AUDIENCE + ":wrong", Set.of("data-batch.evaluate"), GENERATION),
                GENERATION);
        assertCode("INGESTION_QUALITY_FORBIDDEN", () -> guard.capture(
                DataBatchCommandType.EVALUATE, PRINCIPAL, CAPTURE_TIME));
        port.nextCapture = DataBatchWorkloadAuthorizationResult.allow(
                copy(baseEvidence(Set.of("data-batch.evaluate")),
                        AUDIENCE, Set.of("data-batch.evaluate", "data-batch.publish"), GENERATION),
                GENERATION);
        assertCode("INGESTION_QUALITY_FORBIDDEN", () -> guard.capture(
                DataBatchCommandType.EVALUATE, PRINCIPAL, CAPTURE_TIME));
        port.nextCapture = DataBatchWorkloadAuthorizationResult.allow(
                baseEvidence(Set.of("data-batch.evaluate")), GENERATION + 1);
        assertCode("INGESTION_QUALITY_FORBIDDEN", () -> guard.capture(
                DataBatchCommandType.EVALUATE, PRINCIPAL, CAPTURE_TIME));
        port.nextCapture = DataBatchWorkloadAuthorizationResult.allow(
                withPolicyVersion(
                        baseEvidence(Set.of("data-batch.evaluate")),
                        POLICY_VERSION + "-drift"),
                GENERATION);
        assertCode("INGESTION_QUALITY_FORBIDDEN", () -> guard.capture(
                DataBatchCommandType.EVALUATE, PRINCIPAL, CAPTURE_TIME));
    }

    @Test
    void evaluateRevalidatesAtFreshTrustedTimeImmediatelyBeforeOwnerWrites() {
        EvaluationRig rig = EvaluationRig.sealed();

        DataBatchView result = rig.service.evaluate(new EvaluateDataBatchCommand(
                BATCH_ID, 2, context("evaluate-success")));

        assertEquals(DataBatchStatus.QUALITY_PASSED, result.status());
        assertEquals(CAPTURE_TIME, rig.workload.captureRequests.getFirst().currentTime());
        assertEquals(REVALIDATE_TIME,
                rig.workload.revalidationRequests.getFirst().currentTime());
        assertTrue(rig.events.indexOf("workload-capture")
                < rig.events.indexOf("precedence-inspect"));
        assertEquals(List.of(
                "workload-revalidate", "batch-save", "snapshot-insert",
                "audit-append", "idempotency-complete"),
                rig.events.stream().filter(DataBatchWorkloadAuthorizationGuardContractTest
                        ::isFinalCommitEvent).toList());
    }

    @Test
    void allowThenRevokedUnavailableOrChangedEvidenceLeavesEvaluateEntirelyUncommitted() {
        List<FinalAuthorizationScenario> scenarios = List.of(
                new FinalAuthorizationScenario(
                        "revoked",
                        captured -> DataBatchWorkloadAuthorizationResult.deny(GENERATION + 1),
                        "INGESTION_QUALITY_FORBIDDEN"),
                new FinalAuthorizationScenario(
                        "unavailable",
                        captured -> DataBatchWorkloadAuthorizationResult.dependencyUnavailable(),
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"),
                new FinalAuthorizationScenario(
                        "evidence-drift",
                        captured -> DataBatchWorkloadAuthorizationResult.allow(
                                withPolicyDigest(captured, "sha256:" + "8".repeat(64)),
                                captured.authorizationGeneration()),
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"));

        for (FinalAuthorizationScenario scenario : scenarios) {
            EvaluationRig rig = EvaluationRig.sealed();
            rig.workload.finalResult = scenario.result().apply(
                    baseEvidence(Set.of("data-batch.evaluate")));

            assertCode(scenario.errorCode(), () -> rig.service.evaluate(
                    new EvaluateDataBatchCommand(
                            BATCH_ID, 2, context("evaluate-" + scenario.name()))));

            assertEquals(DataBatchStatus.SEALED, rig.store.batch.status(), scenario.name());
            assertEquals(0, rig.store.saveCalls.get(), scenario.name());
            assertEquals(0, rig.snapshots.insertCalls.get(), scenario.name());
            assertEquals(0, rig.store.auditCalls.get(), scenario.name());
            assertEquals(0, rig.store.completeCalls.get(), scenario.name());
            assertEquals(List.of("workload-capture", "workload-revalidate"),
                    rig.events.stream().filter(event -> event.startsWith("workload-")).toList(),
                    scenario.name());
        }
    }

    @Test
    void completedReplayCapturesCurrentAuthorizationAgainBeforeReadingTheCachedResult() {
        EvaluationRig rig = EvaluationRig.empty();
        ReceiveDataBatchCommand command = receive(context("receive-replay"));
        DataBatchView first = rig.service.receive(command);
        int idempotencyReadsAfterFirst = rig.store.findIdempotencyCalls.get();
        rig.workload.nextCapture = DataBatchWorkloadAuthorizationResult.deny(GENERATION + 1);

        assertCode("INGESTION_QUALITY_FORBIDDEN", () -> rig.service.receive(command));

        assertEquals(DataBatchStatus.RECEIVING, first.status());
        assertEquals(2, rig.workload.captureRequests.size());
        assertEquals(1, rig.workload.revalidationRequests.size());
        assertEquals(idempotencyReadsAfterFirst, rig.store.findIdempotencyCalls.get(),
                "revoked replay must stop before cached idempotency data is read");
        assertEquals(1, rig.store.insertCalls.get());
        assertEquals(1, rig.store.auditCalls.get());
        assertEquals(1, rig.store.completeCalls.get());
    }

    private static boolean isFinalCommitEvent(String event) {
        return Set.of(
                "workload-revalidate", "batch-save", "snapshot-insert",
                "audit-append", "idempotency-complete").contains(event);
    }

    private static void assertCode(String expected, Runnable action) {
        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class, action::run);
        assertEquals(expected, failure.code());
    }

    private static List<String> recordFields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static DataBatchWorkloadAuthorizationEvidence baseEvidence(Set<String> capabilities) {
        return evidence(
                capabilities, GENERATION, CAPTURE_TIME.minusSeconds(30),
                CAPTURE_TIME.plusSeconds(60), null);
    }

    private static DataBatchWorkloadAuthorizationEvidence evidence(
            Set<String> capabilities,
            long generation,
            Instant effectiveAt,
            Instant expiresAt,
            Instant revokedAt) {
        return new DataBatchWorkloadAuthorizationEvidence(
                ENVIRONMENT, PRINCIPAL, MTLS_SAN, AUDIENCE, capabilities, generation,
                POLICY_VERSION, POLICY_DIGEST, effectiveAt, expiresAt, revokedAt);
    }

    private static DataBatchWorkloadAuthorizationEvidence copy(
            DataBatchWorkloadAuthorizationEvidence source,
            String audience,
            Set<String> capabilities,
            long generation) {
        return new DataBatchWorkloadAuthorizationEvidence(
                source.environment(), source.principalRef(), source.mtlsSanUriRef(), audience,
                capabilities, generation, source.policyVersion(), source.policyDigest(),
                source.effectiveAt(), source.expiresAt(), source.revokedAt());
    }

    private static DataBatchWorkloadAuthorizationEvidence withPolicyDigest(
            DataBatchWorkloadAuthorizationEvidence source,
            String policyDigest) {
        return new DataBatchWorkloadAuthorizationEvidence(
                source.environment(), source.principalRef(), source.mtlsSanUriRef(),
                source.audience(), source.capabilities(), source.authorizationGeneration(),
                source.policyVersion(), policyDigest, source.effectiveAt(), source.expiresAt(),
                source.revokedAt());
    }

    private static DataBatchWorkloadAuthorizationEvidence withPolicyVersion(
            DataBatchWorkloadAuthorizationEvidence source,
            String policyVersion) {
        return new DataBatchWorkloadAuthorizationEvidence(
                source.environment(), source.principalRef(), source.mtlsSanUriRef(),
                source.audience(), source.capabilities(), source.authorizationGeneration(),
                policyVersion, source.policyDigest(), source.effectiveAt(), source.expiresAt(),
                source.revokedAt());
    }

    private static DataBatchCommandContext context(String idempotencyKey) {
        return new DataBatchCommandContext(
                "tenant-test", PRINCIPAL, idempotencyKey,
                "00112233445566778899aabbccddeeff");
    }

    private static ReceiveDataBatchCommand receive(DataBatchCommandContext context) {
        return new ReceiveDataBatchCommand(
                BATCH_ID, 0,
                new BatchIdentity("SRC-P0-STUDENT-001", "student-status:2026-08-10", 1),
                BatchLineage.root(LINEAGE_ID, CAPTURE_TIME),
                MANIFEST_DIGEST, context);
    }

    private static BatchManifest manifest() {
        SourcePolicy source = QUALITY_CONTRACT.policy().sources().stream()
                .filter(candidate -> candidate.sourceId().equals("SRC-P0-STUDENT-001"))
                .findFirst().orElseThrow();
        Instant cutoffAt = CAPTURE_TIME.plusSeconds(1);
        return new BatchManifest(
                10, 9, 1,
                new BatchObservationWindow(cutoffAt.minusSeconds(720L * 3_600L), cutoffAt),
                cutoffAt, "Asia/Shanghai", "sha256:" + "8".repeat(64),
                source.schemaBinding().version(), source.schemaBinding().canonicalDigest(),
                QUALITY_CONTRACT.policy().controlledInputs().dataCatalog().version(),
                QUALITY_CONTRACT.policy().controlledInputs().dataCatalog().canonicalDigest(),
                QUALITY_CONTRACT.policy().controlledInputs().qualityGate().version(),
                QUALITY_CONTRACT.policy().controlledInputs().qualityGate().canonicalDigest(),
                QUALITY_CONTRACT.policy().profileVersion(),
                QUALITY_CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                CAPTURE_TIME.minusSeconds(1), CAPTURE_TIME.plusSeconds(60), CAPTURE_TIME,
                source.freshnessLanes().getFirst().laneId(), MANIFEST_DIGEST);
    }

    private static SealedQualityContractEvidence sealedQualityEvidence() {
        QualityContractAttestation value = QUALITY_CONTRACT.attestation();
        return new SealedQualityContractEvidence(
                value.qmdpProfileVersion(), value.qmdpPolicyRawDigest(),
                value.qmdpPolicyCanonicalDigest(), value.qmdpContractLockVersion(),
                value.qmdpContractLockRawDigest(), value.qmdpContractLockCanonicalDigest(),
                value.qmdpAuthorityRef(), value.qmdpApprovalRef(), value.qmdpEffectiveAt(),
                value.qshmProfileVersion(), value.qshmProfileRawDigest(),
                value.qshmProfileCanonicalDigest(), value.qshmContractLockVersion(),
                value.qshmContractLockRawDigest(), value.qshmAuthorityRef(),
                value.qshmApprovalRef(), value.qshmEffectiveAt());
    }

    private static Map<String, MeasuredQualityInputs> passingMeasurements(
            List<MetricDefinition> definitions) {
        LinkedHashMap<String, MeasuredQualityInputs> result = new LinkedHashMap<>();
        for (MetricDefinition definition : definitions) {
            LinkedHashMap<String, BigInteger> operands = new LinkedHashMap<>();
            if (definition.calculation().numerator().operandId() != null) {
                operands.put(
                        definition.calculation().numerator().operandId(),
                        BigInteger.valueOf(definition.thresholdNumerator()));
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

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private record FinalAuthorizationScenario(
            String name,
            Function<DataBatchWorkloadAuthorizationEvidence,
                    DataBatchWorkloadAuthorizationResult> result,
            String errorCode) {}

    private static final class RecordingWorkloadAuthorizationPort
            implements DataBatchWorkloadAuthorizationPort {
        private final List<DataBatchWorkloadAuthorizationRequest> captureRequests =
                new ArrayList<>();
        private final List<DataBatchWorkloadAuthorizationRequest> revalidationRequests =
                new ArrayList<>();
        private final List<String> events;
        private DataBatchWorkloadAuthorizationResult nextCapture;
        private DataBatchWorkloadAuthorizationResult finalResult;

        private RecordingWorkloadAuthorizationPort() {
            this(new ArrayList<>());
        }

        private RecordingWorkloadAuthorizationPort(List<String> events) {
            this.events = events;
        }

        @Override
        public DataBatchWorkloadAuthorizationResult capture(
                DataBatchWorkloadAuthorizationRequest request) {
            events.add("workload-capture");
            captureRequests.add(request);
            if (nextCapture != null) {
                DataBatchWorkloadAuthorizationResult selected = nextCapture;
                nextCapture = null;
                return selected;
            }
            return DataBatchWorkloadAuthorizationResult.allow(
                    baseEvidence(request.capabilities()), GENERATION);
        }

        @Override
        public DataBatchWorkloadAuthorizationResult revalidate(
                DataBatchWorkloadAuthorizationEvidence captured,
                DataBatchWorkloadAuthorizationRequest request) {
            events.add("workload-revalidate");
            revalidationRequests.add(request);
            if (finalResult != null) return finalResult;
            return DataBatchWorkloadAuthorizationResult.allow(captured, GENERATION);
        }
    }

    private static final class EvaluationRig {
        private final List<String> events = new ArrayList<>();
        private final RecordingWorkloadAuthorizationPort workload =
                new RecordingWorkloadAuthorizationPort(events);
        private final MemoryStore store;
        private final MemorySnapshots snapshots = new MemorySnapshots(events);
        private final DataBatchCommandService service;

        private EvaluationRig(DataBatch initial) {
            store = new MemoryStore(initial, events);
            ExecutableQualityPolicyGuard qualityGuard =
                    new ExecutableQualityPolicyGuard(() -> QUALITY_CONTRACT);
            QualityMeasurementPort measurement = (batch, definitions) -> {
                events.add("measure");
                return new QualityMeasurement(
                        QualityMeasurementAnchor.from(batch),
                        passingMeasurements(definitions), List.of("test-impact-scope"));
            };
            DataBatchQualityEvaluationService evaluation =
                    new DataBatchQualityEvaluationService(
                            qualityGuard, measurement, store, snapshots,
                            ignored -> uuid("019ff300-0000-7000-8000-000000000101"));
            DataBatchWorkloadAuthorizationGuard workloadGuard =
                    new DataBatchWorkloadAuthorizationGuard(workload);
            store.snapshots = snapshots;
            service = new DataBatchCommandService(
                    store, snapshots, store, store, evaluation, qualityGuard,
                    request -> DataBatchAuthorizationDecision.ALLOW,
                    workloadGuard,
                    new DataBatchCanonicalOutboxFactory("scholarsense_iq_test_worker"),
                    new ScriptedTimeSource(CAPTURE_TIME, REVALIDATE_TIME));
        }

        private static EvaluationRig sealed() {
            DataBatch sealed = DataBatch.receiving(
                    BATCH_ID,
                    new BatchIdentity(
                            "SRC-P0-STUDENT-001", "student-status:2026-08-10", 1),
                    BatchLineage.root(LINEAGE_ID, CAPTURE_TIME), MANIFEST_DIGEST,
                    CAPTURE_TIME, "00112233445566778899aabbccddeeff")
                    .seal(manifest(), sealedQualityEvidence(), CAPTURE_TIME);
            return new EvaluationRig(sealed);
        }

        private static EvaluationRig empty() {
            return new EvaluationRig(null);
        }
    }

    private static final class ScriptedTimeSource implements TrustedTimeSource {
        private final Deque<Instant> times = new ArrayDeque<>();
        private Instant last;

        private ScriptedTimeSource(Instant... times) {
            this.times.addAll(List.of(times));
            this.last = times[times.length - 1];
        }

        @Override
        public TrustedTime now() {
            if (!times.isEmpty()) last = times.removeFirst();
            return new TrustedTime(
                    last,
                    new TimeSourceProfile(
                            "test-clock", "AUDIT-CLOCK-BINDING-1.0.0", 1,
                            last.minusSeconds(5), last.plusSeconds(5),
                            "evidence://signed/test-clock.json"));
        }
    }

    private static final class MemorySnapshots implements QualitySnapshotRepository {
        private final List<String> events;
        private final AtomicInteger insertCalls = new AtomicInteger();
        private QualitySnapshot snapshot;

        private MemorySnapshots(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<QualitySnapshot> findByBatchId(UUID batchId) {
            return snapshot == null || !snapshot.batchId().equals(batchId)
                    ? Optional.empty() : Optional.of(snapshot);
        }

        @Override
        public void insert(VerifiedQualitySnapshot verified) {
            events.add("snapshot-insert");
            snapshot = verified.value();
            insertCalls.incrementAndGet();
        }
    }

    private static final class MemoryStore implements
            DataBatchRepository,
            DataBatchIdempotencyPort,
            DataBatchTransactionPort,
            DataBatchAuditPort,
            DataBatchCommandReplayPort,
            DataBatchAtomicCommandPort {
        private final List<String> events;
        private final Map<DataBatchIdempotencyScope, DataBatchIdempotencyResult> idempotency =
                new LinkedHashMap<>();
        private final AtomicInteger findIdempotencyCalls = new AtomicInteger();
        private final AtomicInteger insertCalls = new AtomicInteger();
        private final AtomicInteger saveCalls = new AtomicInteger();
        private final AtomicInteger auditCalls = new AtomicInteger();
        private final AtomicInteger completeCalls = new AtomicInteger();
        private DataBatch batch;
        private MemorySnapshots snapshots;

        private MemoryStore(DataBatch initial, List<String> events) {
            this.batch = initial;
            this.events = events;
        }

        @Override
        public Optional<DataBatch> find(UUID batchId) {
            return batch == null || !batch.batchId().equals(batchId)
                    ? Optional.empty() : Optional.of(batch);
        }

        @Override
        public Optional<DataBatch> findByIdentity(BatchIdentity identity) {
            return batch == null || !batch.identity().equals(identity)
                    ? Optional.empty() : Optional.of(batch);
        }

        @Override
        public Optional<DataBatch> latestForBusinessKey(String sourceId, String businessKey) {
            return batch == null
                    || !batch.identity().sourceId().equals(sourceId)
                    || !batch.identity().businessKey().equals(businessKey)
                    ? Optional.empty() : Optional.of(batch);
        }

        @Override
        public Optional<DataBatch> lineageHead(UUID lineageId) {
            return batch == null || !batch.lineage().lineageId().equals(lineageId)
                    ? Optional.empty() : Optional.of(batch);
        }

        @Override
        public void insert(DataBatch value) {
            events.add("batch-insert");
            batch = value;
            insertCalls.incrementAndGet();
        }

        @Override
        public void save(DataBatch value, long expectedVersion) {
            events.add("batch-save");
            batch = value;
            saveCalls.incrementAndGet();
        }

        @Override
        public Optional<DataBatchIdempotencyResult> find(
                DataBatchIdempotencyScope scope, Instant at) {
            events.add("idempotency-find");
            findIdempotencyCalls.incrementAndGet();
            return Optional.ofNullable(idempotency.get(scope));
        }

        @Override
        public DataBatchCommandPrecedence inspect(
                DataBatchIdempotencyScope scope, String requestDigest) {
            events.add("precedence-inspect");
            DataBatchIdempotencyResult existing = idempotency.get(scope);
            if (existing == null) return DataBatchCommandPrecedence.fresh();
            return existing.requestDigest().equals(requestDigest)
                    ? DataBatchCommandPrecedence.replay(existing)
                    : DataBatchCommandPrecedence.mismatch();
        }

        @Override
        public DataBatchAtomicCommandResult receive(ReceiveDataBatchAtomicCommand command) {
            return atomic(command.commit(), () -> {
                DataBatch value = findByIdentity(command.receivedBatch().identity())
                        .orElse(command.receivedBatch());
                if (batch == null) insert(value);
                return DataBatchView.from(value);
            });
        }

        @Override
        public DataBatchAtomicCommandResult seal(SealDataBatchAtomicCommand command) {
            return atomic(command.commit(), () -> {
                save(command.sealedBatch(), command.expectedAggregateVersion());
                return DataBatchView.from(command.sealedBatch());
            });
        }

        @Override
        public DataBatchAtomicCommandResult commitQualityEvaluation(
                CommitDataBatchQualityEvaluationCommand command) {
            return atomic(command.commit(), () -> {
                save(command.assessment().updatedBatch(), command.expectedAggregateVersion());
                snapshots.insert(command.assessment().snapshot());
                return DataBatchView.from(command.assessment().updatedBatch());
            });
        }

        @Override
        public DataBatchAtomicCommandResult publish(PublishDataBatchAtomicCommand command) {
            return atomic(command.commit(), () -> {
                save(command.publishedBatch(), command.expectedAggregateVersion());
                return DataBatchView.from(command.publishedBatch());
            });
        }

        private DataBatchAtomicCommandResult atomic(
                DataBatchAtomicCommitContext commit,
                java.util.function.Supplier<DataBatchView> fresh) {
            DataBatchIdempotencyResult existing = idempotency.get(commit.idempotencyScope());
            if (existing != null) {
                if (!existing.requestDigest().equals(commit.requestDigest())) {
                    throw new IngestionQualityApplicationException(
                            "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH");
                }
                return new DataBatchAtomicCommandResult(
                        DataBatchAtomicCommandResult.Status.REPLAY, existing.response());
            }
            DataBatchView response = fresh.get();
            append(new DataBatchAuditEvent(
                    commit.idempotencyScope().commandType().action(), "accepted",
                    response.batchId(), response.aggregateVersion(),
                    commit.idempotencyScope().actorRef(), commit.traceId(),
                    commit.occurredAt().instant(), commit.occurredAt().profile(),
                    commit.requestDigest()));
            complete(new DataBatchIdempotencyResult(
                    commit.idempotencyScope(), commit.requestDigest(), response,
                    commit.occurredAt().instant(),
                    commit.occurredAt().instant().plus(DataBatchCommandService.IDEMPOTENCY_RETENTION)));
            return new DataBatchAtomicCommandResult(
                    DataBatchAtomicCommandResult.Status.ACCEPTED, response);
        }

        @Override
        public DataBatchIdempotencyClaim claim(
                DataBatchIdempotencyScope scope, String requestDigest, Instant at) {
            DataBatchIdempotencyResult existing = idempotency.get(scope);
            if (existing == null) return DataBatchIdempotencyClaim.fresh();
            return existing.requestDigest().equals(requestDigest)
                    ? DataBatchIdempotencyClaim.replay(existing)
                    : DataBatchIdempotencyClaim.mismatch();
        }

        @Override
        public void complete(DataBatchIdempotencyResult result) {
            events.add("idempotency-complete");
            idempotency.put(result.scope(), result);
            completeCalls.incrementAndGet();
        }

        @Override
        public DataBatchView execute(java.util.function.Supplier<DataBatchView> work) {
            return work.get();
        }

        @Override
        public void requireHealthy(String traceId, Instant at) {
            // Healthy test fixture.
        }

        @Override
        public void append(DataBatchAuditEvent event) {
            events.add("audit-append");
            auditCalls.incrementAndGet();
        }
    }
}
