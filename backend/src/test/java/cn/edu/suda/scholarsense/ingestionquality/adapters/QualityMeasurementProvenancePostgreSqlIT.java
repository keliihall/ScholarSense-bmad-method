package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityContractAttestation;
import cn.edu.suda.scholarsense.ingestionquality.application.VerifiedQualityContract;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Operand;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricCalculator;
import cn.edu.suda.scholarsense.ingestionquality.domain.SealedQualityContractEvidence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Story 2.3 RED evidence for database-derived measurement and observation-window provenance. */
class QualityMeasurementProvenancePostgreSqlIT {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VerifiedQualityContract CONTRACT =
            new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified();
    private static final SealedQualityContractEvidence SEALED_EVIDENCE = evidence();
    private static final Duration FRESHNESS_WINDOW = Duration.ofHours(720);
    private static final String MANIFEST_COUNT_OPERAND = "manifest-declared-record-count";
    private static final String WORKER_LOGIN =
            "scholarsense_iq_measurement_provenance_login";
    private static final List<ManifestDenominatorCase> MANIFEST_DENOMINATORS = List.of(
            new ManifestDenominatorCase(
                    "SRC-P0-CARD-001",
                    "QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP"),
            new ManifestDenominatorCase(
                    "SRC-P0-CARD-001",
                    "QMDP-1.0.0/REQUIRED_FIELD_VALIDITY_BP"),
            new ManifestDenominatorCase(
                    "SRC-P0-CARD-001",
                    "QMDP-1.0.0/VALID_RECORD_RATE_BP"),
            new ManifestDenominatorCase(
                    "SRC-P0-CARD-001",
                    "QMDP-1.0.0/SCHEMA_ALLOWLIST_COMPATIBILITY_BP"),
            new ManifestDenominatorCase(
                    "SRC-P0-RESPONSIBILITY-001",
                    "QMDP-1.0.0/SRC-P0-RESPONSIBILITY-001/manifest-reconcile"),
            new ManifestDenominatorCase(
                    "SRC-P1-ACADEMIC-001",
                    "QMDP-1.0.0/SRC-P1-ACADEMIC-001/manifest-reconcile"));

    @Test
    void sealDerivesManifestDenominatorsForTheExactSixApprovedFormulasAndRejectsForgery()
            throws Exception {
        ensureWorkerLogin();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload();
        Set<String> expectedFormulaIds = MANIFEST_DENOMINATORS.stream()
                .map(ManifestDenominatorCase::formulaId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(expectedFormulaIds, manifestDenominatorFormulaIds(),
                "only the six approved formulas bind this DB-derivable denominator");

        List<Executable> cases = new ArrayList<>();
        for (int index = 0; index < MANIFEST_DENOMINATORS.size(); index++) {
            ManifestDenominatorCase testCase = MANIFEST_DENOMINATORS.get(index);
            int seed = 10_000 + index;
            cases.add(() -> {
                Scenario scenario = stagedEmptyBatch(
                        jdbc, worker, seed, testCase.sourceId(), testCase.formulaId());
                assertRejectedSealIsAtomic(
                        jdbc, worker, scenario, exactWindow(scenario.cutoffAt()),
                        "forged " + testCase.formulaId());
            });
        }
        assertAll(cases);
    }

    @Test
    void sealRequiresTheExactRollingSevenHundredTwentyHourHalfOpenObservationWindow()
            throws Exception {
        ensureWorkerLogin();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload();
        assertEquals(720, CONTRACT.policy().windowSemantics().freshnessWindowHours());
        assertEquals(
                "[windowStartAt,windowEndAt)",
                CONTRACT.policy().windowSemantics().interval());

        List<WindowMutation> mutations = List.of(
                new WindowMutation("start one microsecond early", -1, 0),
                new WindowMutation("start one microsecond late", 1, 0),
                new WindowMutation("end one microsecond early", 0, -1),
                new WindowMutation("end one microsecond late", 0, 1));
        List<Executable> cases = new ArrayList<>();
        for (int index = 0; index < mutations.size(); index++) {
            WindowMutation mutation = mutations.get(index);
            int seed = 11_000 + index;
            cases.add(() -> {
                Scenario scenario = stagedEmptyBatch(
                        jdbc, worker, seed, "SRC-P0-CARD-001", null);
                ObservationWindow exact = exactWindow(scenario.cutoffAt());
                ObservationWindow forged = new ObservationWindow(
                        exact.startAt().plus(mutation.startMicros(), ChronoUnit.MICROS),
                        exact.endAt().plus(mutation.endMicros(), ChronoUnit.MICROS),
                        exact.cutoffAt());
                assertRejectedSealIsAtomic(
                        jdbc, worker, scenario, forged, mutation.label());
            });
        }
        assertAll(cases);
    }

    @Test
    void correctEmptyBatchSealsButEvaluationReturnsStableTechnicalZeroDenominator()
            throws Exception {
        ensureWorkerLogin();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload();
        Scenario scenario = stagedEmptyBatch(
                jdbc, worker, 12_000, "SRC-P0-CARD-001", null);
        ObservationWindow window = exactWindow(scenario.cutoffAt());

        assertTrue(seal(worker, scenario, window));
        StoredWindow stored = jdbc.queryForObject("""
                select observation_start_at, observation_end_at, cutoff_at
                  from ingestion_quality.iq_data_batch where batch_id=?
                """, (row, ignored) -> new StoredWindow(
                        row.getTimestamp("observation_start_at").toInstant(),
                        row.getTimestamp("observation_end_at").toInstant(),
                        row.getTimestamp("cutoff_at").toInstant()), scenario.batchId());
        assertEquals(new StoredWindow(window.startAt(), window.endAt(), window.cutoffAt()), stored);
        assertEquals(FRESHNESS_WINDOW, Duration.between(stored.startAt(), stored.endAt()));
        assertEquals(stored.cutoffAt(), stored.endAt(),
                "the persisted end is the excluded cutoff boundary");
        assertEquals("sealed", status(jdbc, scenario.batchId()));
        assertEquals(2L, aggregateVersion(jdbc, scenario.batchId()));

        EvaluationSpec evaluation = evaluation(scenario);
        EvaluationFootprint before = evaluationFootprint(jdbc, scenario, evaluation);
        DataAccessException failure = captureFailure(() -> evaluate(worker, scenario, evaluation));
        EvaluationFootprint after = evaluationFootprint(jdbc, scenario, evaluation);

        assertAll(
                () -> assertNotNull(failure, "zero denominator must be a technical error"),
                () -> assertEquals(
                        "ERROR: QUALITY_POLICY_ZERO_DENOMINATOR",
                        firstDatabaseMessageLine(failure)),
                () -> assertFalse(
                        databaseMessage(failure)
                                .contains("INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID"),
                        databaseMessage(failure)),
                () -> assertEquals(before, after,
                        "technical evaluation failure must roll back every write"),
                () -> assertEquals(new EvaluationFootprint(
                        "sealed", 2L, 0, 0, 0, 0, 0, 0, 0), after));
    }

    private static Set<String> manifestDenominatorFormulaIds() {
        Stream<MetricDefinition> definitions = Stream.concat(
                CONTRACT.policy().commonMetrics().stream(),
                CONTRACT.policy().sources().stream()
                        .flatMap(source -> source.sourceGates().stream()));
        return definitions
                .filter(definition -> {
                    Operand denominator = definition.calculation().denominator();
                    return "measured".equals(denominator.kind())
                            && MANIFEST_COUNT_OPERAND.equals(denominator.operandId());
                })
                .map(MetricDefinition::formulaId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Scenario stagedEmptyBatch(
            JdbcTemplate jdbc,
            JdbcTemplate worker,
            int seed,
            String sourceId,
            String forgedFormulaId) {
        SourcePolicy source = source(sourceId);
        Instant receivedAt = databaseNow(jdbc)
                .truncatedTo(ChronoUnit.MICROS)
                .minusSeconds(120);
        UUID batchId = uuid(seed * 100L + 1);
        UUID lineageId = uuid(seed * 100L + 2);
        byte[] businessKey = bytes("measurement-provenance-business-key-" + seed);
        String manifestDigest = digest("measurement-provenance-manifest-" + seed);
        String receiveTrace = trace(seed);
        jdbc.update("""
                insert into ingestion_quality.iq_data_batch
                  (batch_id, source_id, business_key_utf8, business_key_digest,
                   source_version, lineage_id, effective_at, declared_manifest_digest,
                   status, aggregate_version, received_at, trace_id)
                values (?, ?, ?, ?, 1, ?, ?, ?, 'receiving', 1, ?, ?)
                """, batchId, sourceId, businessKey, sha256(businessKey), lineageId,
                Timestamp.from(receivedAt.minusSeconds(60)), manifestDigest,
                Timestamp.from(receivedAt), receiveTrace);

        List<MetricDefinition> definitions =
                QualityMetricCalculator.orderedDefinitions(CONTRACT.policy(), sourceId);
        assertTrue(definitions.stream().anyMatch(definition ->
                        forgedFormulaId == null
                                || definition.formulaId().equals(forgedFormulaId)),
                "the selected source must expose the target formula");
        for (int ordinal = 0; ordinal < definitions.size(); ordinal++) {
            MetricDefinition definition = definitions.get(ordinal);
            Map<String, Long> operands = zeroOperands(definition);
            if (definition.formulaId().equals(forgedFormulaId)) {
                assertTrue(operands.containsKey(MANIFEST_COUNT_OPERAND));
                operands.put(MANIFEST_COUNT_OPERAND, 1L);
            }
            String encoded = write(operands);
            boolean applicable = !"overlap-records-present"
                    .equals(definition.applicability().predicateId());
            assertTrue(Boolean.TRUE.equals(worker.queryForObject("""
                    select ingestion_quality.iq_record_batch_quality_measurement(
                      ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """, Boolean.class, batchId, definition.formulaId(), ordinal,
                    applicable, encoded, "sha256:" + canonicalDigest(encoded),
                    Timestamp.from(receivedAt.plusSeconds(ordinal + 1L)))));
        }

        Instant cutoffAt = receivedAt.minusSeconds(10);
        Instant sealedAt = receivedAt.plusSeconds(30);
        UUID sealCommandId = uuid(seed * 100L + 3);
        String sealTraceId = trace(seed + 100_000);
        String sealScopeDigest = sha256(bytes("measurement-provenance-seal-scope-" + seed));
        String sealRequestDigest = digest("measurement-provenance-seal-request-" + seed);
        AuditEnvelope sealAudit = auditEnvelope(
                sealCommandId, batchId, "data-batch.seal", 2L,
                sealTraceId, sealRequestDigest, sealedAt);
        return new Scenario(
                batchId, source, manifestDigest, receivedAt, cutoffAt, sealedAt,
                definitions.size(), sealCommandId, sealTraceId, sealScopeDigest,
                sealRequestDigest, sealAudit, uuid(seed * 100L + 4),
                uuid(seed * 100L + 5));
    }

    private static Map<String, Long> zeroOperands(MetricDefinition definition) {
        Map<String, Long> result = new TreeMap<>();
        addZero(result, definition.calculation().numerator());
        addZero(result, definition.calculation().denominator());
        return result;
    }

    private static void addZero(Map<String, Long> operands, Operand operand) {
        if (operand.operandId() != null) operands.put(operand.operandId(), 0L);
    }

    private static ObservationWindow exactWindow(Instant cutoffAt) {
        return new ObservationWindow(cutoffAt.minus(FRESHNESS_WINDOW), cutoffAt, cutoffAt);
    }

    private static void assertRejectedSealIsAtomic(
            JdbcTemplate jdbc,
            JdbcTemplate worker,
            Scenario scenario,
            ObservationWindow window,
            String label) {
        SealFootprint before = sealFootprint(jdbc, scenario);
        DataAccessException failure = captureFailure(() -> seal(worker, scenario, window));
        SealFootprint after = sealFootprint(jdbc, scenario);
        assertAll(label,
                () -> assertNotNull(failure, "seal must reject non-derived evidence"),
                () -> assertEquals(
                        "ERROR: INGESTION_QUALITY_SEAL_EVIDENCE_INVALID",
                        firstDatabaseMessageLine(failure)),
                () -> assertEquals(before, after,
                        "rejected seal must roll back the entire command"),
                () -> assertEquals(new SealFootprint(
                        "receiving", 1L, scenario.measurementCount(), 0,
                        0, 0, 0, 0, 0, 0, 0), after));
    }

    private static boolean seal(
            JdbcTemplate worker, Scenario scenario, ObservationWindow window) {
        var dataCatalog = CONTRACT.policy().controlledInputs().dataCatalog();
        var qualityGate = CONTRACT.policy().controlledInputs().qualityGate();
        var sourceSchema = scenario.source().schemaBinding();
        String laneId = scenario.source().freshnessLanes().getFirst().laneId();
        return Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_seal_data_batch(
                  ?, ?, 1, 0, 0, 0, ?, ?, ?, 'Asia/Shanghai', ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                  ?, ?, ?, ?, ?::jsonb, ?)
                """, Boolean.class,
                scenario.sealCommandId(), scenario.batchId(),
                Timestamp.from(window.startAt()), Timestamp.from(window.endAt()),
                Timestamp.from(window.cutoffAt()), bytes("sha256:" + "8".repeat(64)),
                sourceSchema.version(), sourceSchema.canonicalDigest(),
                dataCatalog.version(), dataCatalog.canonicalDigest(),
                qualityGate.version(), qualityGate.canonicalDigest(),
                CONTRACT.policy().profileVersion(),
                CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                Timestamp.from(scenario.cutoffAt().minusSeconds(60)),
                Timestamp.from(scenario.cutoffAt()), Timestamp.from(scenario.receivedAt()),
                laneId, scenario.manifestDigest(), write(SEALED_EVIDENCE),
                Timestamp.from(scenario.sealedAt()), scenario.sealTraceId(),
                scenario.sealScopeDigest(), scenario.sealRequestDigest(),
                scenario.sealAudit().payload(), scenario.sealAudit().digest()));
    }

    private static EvaluationSpec evaluation(Scenario scenario) {
        UUID commandId = uuid(id(scenario.snapshotId()) + 10);
        Instant evaluatedAt = scenario.sealedAt().plusSeconds(10);
        String traceId = trace((int) (id(commandId) % Integer.MAX_VALUE));
        String scopeDigest = sha256(bytes("measurement-provenance-evaluate-" + commandId));
        String requestDigest = digest("measurement-provenance-evaluate-request-" + commandId);
        AuditEnvelope audit = auditEnvelope(
                commandId, scenario.batchId(), "data-batch.evaluate", 3L,
                traceId, requestDigest, evaluatedAt);
        return new EvaluationSpec(
                commandId, evaluatedAt, traceId, scopeDigest, requestDigest, audit);
    }

    private static boolean evaluate(
            JdbcTemplate worker, Scenario scenario, EvaluationSpec evaluation) {
        byte[] unusedBusinessPayload = bytes("{}");
        return Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_commit_batch_quality_evaluation(
                  ?, ?, 2, ?, ?, ?, 'quality-passed', ?, ?, ?, ?, ?, '[]'::jsonb,
                  ?, ?, ?::jsonb, ?, ?,
                  'scholarsense.ingestion-quality.data-batch.quality-assessed.v1',
                  'DATA-BATCH-QUALITY-ASSESSED-1.0.0', ?, ?)
                """, Boolean.class,
                evaluation.commandId(), scenario.batchId(), scenario.snapshotId(),
                token("ost", scenario.snapshotId().toString()),
                token("agt", scenario.snapshotId().toString()),
                scenario.source().owner(), Timestamp.from(evaluation.evaluatedAt()),
                evaluation.traceId(), digest("unused-immutable-hash"),
                digest("unused-retention-scope"), evaluation.scopeDigest(),
                evaluation.requestDigest(), evaluation.audit().payload(),
                evaluation.audit().digest(), scenario.businessEventId(),
                unusedBusinessPayload, sha256(unusedBusinessPayload)));
    }

    private static SealFootprint sealFootprint(JdbcTemplate jdbc, Scenario scenario) {
        return new SealFootprint(
                status(jdbc, scenario.batchId()), aggregateVersion(jdbc, scenario.batchId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_batch_quality_measurement
                         where batch_id=? and sealed_at is null
                        """, Integer.class, scenario.batchId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_batch_quality_measurement
                         where batch_id=? and sealed_at is not null
                        """, Integer.class, scenario.batchId()),
                countByScope(jdbc, scenario.sealScopeDigest()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_fact
                         where audit_id=? and action='data-batch.seal'
                        """, Integer.class, scenario.sealCommandId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_outbox
                         where event_id=?
                        """, Integer.class, scenario.sealCommandId()),
                snapshotCount(jdbc, scenario.batchId()),
                snapshotMetricCount(jdbc, scenario.batchId()),
                snapshotImpactCount(jdbc, scenario.batchId()),
                businessOutboxCount(jdbc, scenario.batchId()));
    }

    private static EvaluationFootprint evaluationFootprint(
            JdbcTemplate jdbc, Scenario scenario, EvaluationSpec evaluation) {
        return new EvaluationFootprint(
                status(jdbc, scenario.batchId()), aggregateVersion(jdbc, scenario.batchId()),
                countByScope(jdbc, evaluation.scopeDigest()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_fact
                         where audit_id=? and action='data-batch.evaluate'
                        """, Integer.class, evaluation.commandId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_outbox
                         where event_id=?
                        """, Integer.class, evaluation.commandId()),
                snapshotCount(jdbc, scenario.batchId()),
                snapshotMetricCount(jdbc, scenario.batchId()),
                snapshotImpactCount(jdbc, scenario.batchId()),
                businessOutboxCount(jdbc, scenario.batchId()));
    }

    private static int snapshotCount(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_quality_snapshot where batch_id=?
                """, Integer.class, batchId);
    }

    private static int snapshotMetricCount(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_metric metric
                  join ingestion_quality.iq_quality_snapshot snapshot
                    on snapshot.snapshot_id=metric.snapshot_id
                 where snapshot.batch_id=?
                """, Integer.class, batchId);
    }

    private static int snapshotImpactCount(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_impact_scope impact
                  join ingestion_quality.iq_quality_snapshot snapshot
                    on snapshot.snapshot_id=impact.snapshot_id
                 where snapshot.batch_id=?
                """, Integer.class, batchId);
    }

    private static int businessOutboxCount(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_quality_outbox
                 where aggregate_id=?
                """, Integer.class, batchId);
    }

    private static int countByScope(JdbcTemplate jdbc, String scopeDigest) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_idempotency
                 where scope_digest=?
                """, Integer.class, scopeDigest);
    }

    private static String status(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject("""
                select status from ingestion_quality.iq_data_batch where batch_id=?
                """, String.class, batchId);
    }

    private static long aggregateVersion(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject("""
                select aggregate_version from ingestion_quality.iq_data_batch where batch_id=?
                """, Long.class, batchId);
    }

    private static DataAccessException captureFailure(Runnable command) {
        try {
            command.run();
            return null;
        } catch (DataAccessException failure) {
            return failure;
        }
    }

    private static String firstDatabaseMessageLine(DataAccessException failure) {
        return databaseMessage(failure).lines().findFirst().orElseThrow();
    }

    private static String databaseMessage(DataAccessException failure) {
        assertNotNull(failure);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        return message;
    }

    private static AuditEnvelope auditEnvelope(
            UUID commandId,
            UUID batchId,
            String action,
            long aggregateVersion,
            String traceId,
            String requestDigest,
            Instant occurredAt) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("eventId", commandId.toString());
        payload.put("auditId", commandId.toString());
        payload.put("eventType", "ingestion-quality.local-audit-fact.recorded.v1");
        payload.put("schemaVersion", "LOCAL-AUDIT-OUTBOX-1.0.0");
        payload.put("producer", "ingestion-quality");
        payload.put("createdAt", occurredAt.toString());

        ObjectNode fact = payload.putObject("fact");
        fact.put("auditId", commandId.toString());
        fact.put("schemaVersion", "LOCAL-AUDIT-FACT-1.0.0");
        fact.put("producerModule", "ingestion-quality");
        fact.put("actorType", "SERVICE");
        fact.put("actorSearchToken", token("ast", WORKER_LOGIN));
        fact.putArray("roleIds").add("QUALITY_WORKER");
        ObjectNode authorization = fact.putObject("authorizationContext");
        authorization.put("decision", "allow");
        authorization.put(
                "policyVersion", "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0");
        authorization.putArray("scopeCodes").add("QUALITY_WORKLOAD");
        authorization.putArray("grantSearchTokens");
        authorization.putNull("notApplicableReason");
        fact.put("action", action);
        fact.put("objectType", "data-batch");
        fact.put("objectSearchToken", token("ost", batchId.toString()));
        fact.put("outcome", "accepted");
        fact.put("reasonCode", "INGESTION_QUALITY_COMMAND_ACCEPTED");
        fact.put("purpose", "DATA_QUALITY");
        fact.put("projectionScope", "QUALITY_WORKLOAD");
        fact.put("occurredAt", occurredAt.toString());
        fact.put("recordedAt", occurredAt.toString());
        ObjectNode time = fact.putObject("timeSourceProfile");
        time.put("sourceId", "iq-db-clock");
        time.put("profileVersion", "AUDIT-CLOCK-BINDING-1.0.0");
        time.put("offsetMs", 0);
        time.put("observedAt", occurredAt.minusSeconds(1).toString());
        time.put("freshUntil", occurredAt.plusSeconds(60).toString());
        time.put("evidenceRef", "evidence://signed/ingestion-quality/db-clock");
        fact.putNull("sourceIpSearchToken");
        fact.put("tokenizationProfileVersion", "AUDIT-TOKENIZATION-1.0.0");
        fact.put("keyVersion", "k1");
        fact.put("traceId", traceId);
        fact.put("aggregateType", "data-batch");
        fact.put("aggregateIdSearchToken", token("agt", batchId.toString()));
        fact.put("aggregateVersion", aggregateVersion);
        fact.put("idempotencyKeyDigest", requestDigest.substring("sha256:".length()));
        fact.putObject("policyVersions").put(
                "workloadAuthorization",
                "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0");
        fact.put("retentionScheduleVersion", "RS-1.0.0");

        String encoded = write(payload);
        return new AuditEnvelope(encoded, canonicalDigest(encoded));
    }

    private static SealedQualityContractEvidence evidence() {
        QualityContractAttestation value = CONTRACT.attestation();
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

    private static SourcePolicy source(String sourceId) {
        return CONTRACT.policy().sources().stream()
                .filter(source -> source.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
    }

    private static void ensureWorkerLogin() {
        admin().execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_measurement_provenance_login') then
                        create role scholarsense_iq_measurement_provenance_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_measurement_provenance_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_batch_owner
                  from scholarsense_iq_measurement_provenance_login;
                grant scholarsense_ingestion_quality_quality_worker
                  to scholarsense_iq_measurement_provenance_login
                  with inherit true, set false;
                """);
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(dataSource(required("scholarsense.audit.pg.user")));
    }

    private static JdbcTemplate workload() {
        return new JdbcTemplate(dataSource(WORKER_LOGIN));
    }

    private static DataSource dataSource(String username) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(username);
        source.setPassword(System.getProperty("scholarsense.audit.pg.password", ""));
        return source;
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select statement_timestamp()", Timestamp.class).toInstant();
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }

    private static String token(String prefix, String value) {
        return prefix + "_v1_k1_" + sha256(bytes(value));
    }

    private static String trace(int seed) {
        return sha256(bytes("measurement-provenance-trace-" + seed)).substring(0, 32);
    }

    private static String digest(String value) {
        return "sha256:" + sha256(bytes(value));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String canonicalDigest(String encoded) {
        return sha256(canonicalBytes(encoded));
    }

    private static byte[] canonicalBytes(String encoded) {
        try {
            return JSON.writeValueAsBytes(canonicalValue(JSON.readTree(encoded)));
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("invalid JSON fixture", failure);
        }
    }

    private static Object canonicalValue(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.bigIntegerValue();
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            value.forEach(item -> result.add(canonicalValue(item)));
            return result;
        }
        if (value.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            value.forEachEntry((name, item) -> result.put(name, canonicalValue(item)));
            return result;
        }
        throw new IllegalArgumentException("non-canonical JSON scalar");
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("invalid JSON fixture", failure);
        }
    }

    private static UUID uuid(long value) {
        return UUID.fromString("019fea23-0000-7000-8000-%012x".formatted(value));
    }

    private static long id(UUID value) {
        return value.getLeastSignificantBits() & 0x0000ffffffffffffL;
    }

    private record ManifestDenominatorCase(String sourceId, String formulaId) {}

    private record WindowMutation(String label, long startMicros, long endMicros) {}

    private record ObservationWindow(Instant startAt, Instant endAt, Instant cutoffAt) {}

    private record StoredWindow(Instant startAt, Instant endAt, Instant cutoffAt) {}

    private record AuditEnvelope(String payload, String digest) {}

    private record Scenario(
            UUID batchId,
            SourcePolicy source,
            String manifestDigest,
            Instant receivedAt,
            Instant cutoffAt,
            Instant sealedAt,
            int measurementCount,
            UUID sealCommandId,
            String sealTraceId,
            String sealScopeDigest,
            String sealRequestDigest,
            AuditEnvelope sealAudit,
            UUID snapshotId,
            UUID businessEventId) {}

    private record EvaluationSpec(
            UUID commandId,
            Instant evaluatedAt,
            String traceId,
            String scopeDigest,
            String requestDigest,
            AuditEnvelope audit) {}

    private record SealFootprint(
            String status,
            long aggregateVersion,
            int unsealedMeasurements,
            int sealedMeasurements,
            int idempotency,
            int auditFacts,
            int auditOutbox,
            int snapshots,
            int snapshotMetrics,
            int snapshotImpacts,
            int businessOutbox) {}

    private record EvaluationFootprint(
            String status,
            long aggregateVersion,
            int idempotency,
            int auditFacts,
            int auditOutbox,
            int snapshots,
            int snapshotMetrics,
            int snapshotImpacts,
            int businessOutbox) {}
}
