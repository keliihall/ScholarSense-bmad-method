package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityContractAttestation;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionScopeCanonicalizer;
import cn.edu.suda.scholarsense.ingestionquality.application.VerifiedQualityContract;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.MeasuredQualityInputs;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityAssessment;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotCanonicalizer;
import cn.edu.suda.scholarsense.ingestionquality.domain.SealedQualityContractEvidence;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Story 2.3 RED evidence for the application-to-PostgreSQL atomic command boundary. */
class DataBatchAtomicEvidencePostgreSqlIT {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSION = Pattern.compile("^V(\\d{6})__.+\\.sql$");
    private static final String FEATURE_SUFFIX =
            "__ingestion-quality__data_batch_quality_snapshot_v1.sql";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VerifiedQualityContract CONTRACT =
            new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified();
    private static final QualitySnapshotCanonicalizer QSHM =
            new QualitySnapshotCanonicalizer(CONTRACT.policy(), CONTRACT.hashProfile());
    private static final SourcePolicy SOURCE = CONTRACT.policy().sources().stream()
            .filter(value -> value.sourceId().equals("SRC-P0-CARD-001"))
            .findFirst()
            .orElseThrow();
    private static final SealedQualityContractEvidence SEALED_EVIDENCE = evidence();
    private static final Set<String> SEALED_EVIDENCE_FIELDS = Set.of(
            "qmdpProfileVersion", "qmdpPolicyRawDigest", "qmdpPolicyCanonicalDigest",
            "qmdpContractLockVersion", "qmdpContractLockRawDigest",
            "qmdpContractLockCanonicalDigest", "qmdpAuthorityRef", "qmdpApprovalRef",
            "qmdpEffectiveAt", "qshmProfileVersion", "qshmProfileRawDigest",
            "qshmProfileCanonicalDigest", "qshmContractLockVersion",
            "qshmContractLockRawDigest", "qshmAuthorityRef", "qshmApprovalRef",
            "qshmEffectiveAt");

    private static final String QUALITY_WORKER =
            "scholarsense_ingestion_quality_quality_worker";
    private static final String WORKER_LOGIN =
            "scholarsense_iq_batch_atomic_evidence_login";
    private static final String EVENT_TYPE =
            "scholarsense.ingestion-quality.data-batch.quality-assessed.v1";
    private static final String EVENT_SCHEMA = "DATA-BATCH-QUALITY-ASSESSED-1.0.0";
    private static final byte[] IMPACT_SCOPE =
            "PRIMARY_KEY_COMPLETENESS_BP".getBytes(StandardCharsets.UTF_8);

    @Test
    void receiveRequiresTypedCanonicalAuditAndReplaysOnlyAClosedOutcome() throws Exception {
        requireDynamicallyAppliedFeatureMigration();
        ensureWorkerLogin();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload();

        ReceiveSpec emptyAudit = receiveSpec(jdbc, 10);
        ReceiveSpec wrongDigest = receiveSpec(jdbc, 20);
        ReceiveSpec valid = receiveSpec(jdbc, 30);

        assertAll(
                () -> assertReceiveRejected(
                        jdbc, worker, emptyAudit.withAudit(new AuditEnvelope(
                                "{}", canonicalDigest("{}"), null)),
                        "INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID"),
                () -> assertReceiveRejected(
                        jdbc, worker, wrongDigest.withAudit(new AuditEnvelope(
                                wrongDigest.audit().payload(), "0".repeat(64),
                                wrongDigest.audit().record())),
                        "INGESTION_QUALITY_AUDIT_PAYLOAD_DIGEST_MISMATCH"));

        ReceiveOutcome accepted = receiveOutcome(worker, valid);
        ReceiveOutcome replay = receiveOutcome(worker, valid);
        assertEquals(new ReceiveOutcome(valid.batchId(), "accepted"), accepted);
        assertEquals(new ReceiveOutcome(valid.batchId(), "replay"), replay,
                "same command must return the original response with an explicit replay disposition");

        String storedPayload = jdbc.queryForObject("""
                select payload::text from ingestion_quality.iq_local_audit_outbox
                 where event_id=?
                """, String.class, valid.commandId());
        assertNotNull(storedPayload);
        LocalAuditOutboxRecord decoded = read(storedPayload, LocalAuditOutboxRecord.class);
        assertEquals(valid.audit().record(), decoded,
                "the database payload must round-trip as the shared immutable audit type");
        assertEquals(canonicalDigest(storedPayload), jdbc.queryForObject("""
                select payload_digest from ingestion_quality.iq_local_audit_outbox
                 where event_id=?
                """, String.class, valid.commandId()).trim());

        AuditBinding binding = jdbc.queryForObject("""
                select fact.audit_id, outbox.event_id, fact.batch_id, fact.action, fact.result,
                       fact.aggregate_version, fact.trace_id, fact.request_digest,
                       outbox.event_type, outbox.schema_version, outbox.producer
                  from ingestion_quality.iq_local_audit_fact fact
                  join ingestion_quality.iq_local_audit_outbox outbox
                    on outbox.audit_id=fact.audit_id
                 where fact.audit_id=?
                """, (row, ignored) -> new AuditBinding(
                        row.getObject("audit_id", UUID.class),
                        row.getObject("event_id", UUID.class),
                        row.getObject("batch_id", UUID.class), row.getString("action"),
                        row.getString("result"), row.getLong("aggregate_version"),
                        row.getString("trace_id"), row.getString("request_digest"),
                        row.getString("event_type"), row.getString("schema_version"),
                        row.getString("producer")), valid.commandId());
        assertEquals(new AuditBinding(
                valid.commandId(), valid.commandId(), valid.batchId(), "data-batch.receive",
                "accepted", 1L, valid.traceId(), valid.requestDigest(),
                "ingestion-quality.local-audit-fact.recorded.v1",
                "LOCAL-AUDIT-OUTBOX-1.0.0", "ingestion-quality"), binding);
        assertEquals(Set.of("accepted"), Set.copyOf(jdbc.queryForList("""
                select result from ingestion_quality.iq_local_audit_fact where batch_id=?
                """, String.class, valid.batchId())),
                "replay must never invent the non-contract outcome 'replayed'");
        assertTrue(Set.of("accepted", "rejected").contains(decoded.fact().outcome()));
    }

    @Test
    void sealRequiresTheExactSeventeenFieldApprovedEvidenceAndCanonicalDigests() {
        ensureWorkerLogin();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload();
        JsonNode exact = readTree(write(SEALED_EVIDENCE));

        assertAll(
                () -> assertSealRejected(jdbc, worker, 100,
                        mutated(exact, value -> value.remove("qmdpProfileVersion"))),
                () -> assertSealRejected(jdbc, worker, 110,
                        mutated(exact, value -> value.put("unapprovedField", "forged"))),
                () -> assertSealRejected(jdbc, worker, 120,
                        mutated(exact, value -> value.put("qshmEffectiveAt", 1))),
                () -> assertSealRejected(jdbc, worker, 130,
                        mutated(exact, value -> value.put(
                                "qmdpPolicyCanonicalDigest", digest("policy-drift")))));

        Scenario valid = receiving(jdbc, worker, 140);
        assertTrue(seal(worker, valid, write(exact)));
        JsonNode stored = readTree(jdbc.queryForObject("""
                select sealed_contract_evidence::text
                  from ingestion_quality.iq_data_batch where batch_id=?
                """, String.class, valid.batchId()));
        assertEquals(SEALED_EVIDENCE_FIELDS, Set.copyOf(stored.propertyNames()));
        assertEquals(CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                stored.required("qmdpPolicyCanonicalDigest").asText());
        assertEquals(CONTRACT.attestation().qshmProfileCanonicalDigest(),
                stored.required("qshmProfileCanonicalDigest").asText());
        assertEquals(CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                jdbc.queryForObject("""
                        select qmdp_digest from ingestion_quality.iq_data_batch where batch_id=?
                        """, String.class, valid.batchId()).trim());
    }

    @Test
    void postgresRetentionScopeMatchesTask0GoldensAndRuntimeFractionConvention()
            throws Exception {
        requireDynamicallyAppliedFeatureMigration();
        JdbcTemplate jdbc = admin();
        var exact = retentionMaterial(
                Instant.parse("2024-08-09T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:00Z"));
        var leap = retentionMaterial(
                Instant.parse("2024-02-29T12:00:00Z"),
                Instant.parse("2026-02-28T12:00:00Z"));
        var fractional = retentionMaterial(
                Instant.parse("2024-08-09T00:00:00.999000Z"),
                Instant.parse("2026-08-09T00:00:00.999000Z"));

        assertEquals(
                "sha256:083f0779c7b4926163dd9f8df9fe500c0f2d8672b8f6e1b54ae83bf4c203ff62",
                postgresRetentionScopeDigest(jdbc, exact));
        assertEquals(
                "sha256:8a2ad943c1f7dc49a738c4794ad70f1c560b9585ee72eaf8eca3721380fd751e",
                postgresRetentionScopeDigest(jdbc, leap));
        assertEquals(QualitySnapshotRetentionScopeCanonicalizer.digest(fractional),
                postgresRetentionScopeDigest(jdbc, fractional));
    }

    @Test
    void evaluateRejectsTypedPolicyTraceHashAndPicForgeries() {
        ensureWorkerLogin();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload();

        assertAll(
                () -> assertEvaluationRejected(jdbc, worker, 200,
                        call -> call.withMetrics(mutatedMetrics(call.metrics(), metrics ->
                                ((ObjectNode) metrics.get(0)).put("applicable", "true"))),
                        "INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 210,
                        call -> call.withMetrics(mutatedMetrics(call.metrics(), metrics ->
                                ((ObjectNode) metrics.get(0)).put(
                                        "numerator", 9_007_199_254_740_992L))),
                        "INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 220,
                        call -> call.withMetrics(mutatedMetrics(call.metrics(), metrics ->
                                ((ObjectNode) metrics.get(0)).put("metricId", "FORGED"))),
                        "INGESTION_QUALITY_ASSESSMENT_POLICY_BINDING_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 230,
                        call -> call.withMetrics(mutatedMetrics(call.metrics(), metrics -> {
                            JsonNode first = metrics.get(0);
                            JsonNode second = metrics.get(1);
                            metrics.set(0, second);
                            metrics.set(1, first);
                        })), "INGESTION_QUALITY_ASSESSMENT_FORMULA_SET_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 240,
                        call -> call.withOverallResult("quality-failed"),
                        "INGESTION_QUALITY_ASSESSMENT_OVERALL_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 250,
                        call -> call.withTraceId(trace(9_999)),
                        "INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 260,
                        call -> call.withImmutableHash(digest("arbitrary-hash")),
                        "INGESTION_QUALITY_ASSESSMENT_HASH_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 265,
                        call -> call.withRetentionScopeDigest(digest("retention-scope-drift")),
                        "INGESTION_QUALITY_RETENTION_SCOPE_DIGEST_MISMATCH"),
                () -> assertEvaluationRejected(jdbc, worker, 266,
                        call -> mutateBusiness(jdbc, call, snapshot -> snapshot.put(
                                "snapshotId", uuid(999_001).toString())),
                        "INGESTION_QUALITY_BUSINESS_SNAPSHOT_BINDING_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 267,
                        call -> mutateBusiness(jdbc, call, snapshot -> snapshot.put(
                                "immutableHash", digest("forged-event-hash"))),
                        "INGESTION_QUALITY_BUSINESS_SNAPSHOT_BINDING_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 268,
                        call -> mutateBusiness(jdbc, call, snapshot ->
                                ((ObjectNode) snapshot.withArray("metricResults").get(0))
                                        .put("numerator", 0)),
                        "INGESTION_QUALITY_BUSINESS_SNAPSHOT_BINDING_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 269,
                        call -> mutateBusinessPayload(jdbc, call, payload -> {
                            ObjectNode data = (ObjectNode) payload.required("data");
                            ((ObjectNode) data.required("batch"))
                                    .put("sourceId", "SRC-P0-STUDENT-001");
                            ((ObjectNode) data.required("qualitySnapshot"))
                                    .put("sourceId", "SRC-P0-STUDENT-001");
                        }), "INGESTION_QUALITY_BUSINESS_SNAPSHOT_BINDING_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 2691,
                        call -> mutateBusiness(jdbc, call, snapshot ->
                                snapshot.withArray("impactScopeCodes")
                                        .add("FORGED_IMPACT_SCOPE")),
                        "INGESTION_QUALITY_BUSINESS_SNAPSHOT_BINDING_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 270,
                        call -> call.withBusiness(new BusinessEnvelope(
                                "{}".getBytes(StandardCharsets.UTF_8), sha256(bytes("{}")))),
                        "INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID"),
                () -> assertEvaluationRejected(jdbc, worker, 280,
                        call -> call.withBusiness(new BusinessEnvelope(
                                call.business().payload(), "0".repeat(64))),
                        "INGESTION_QUALITY_BUSINESS_PAYLOAD_DIGEST_MISMATCH"),
                () -> assertEvaluationRejected(jdbc, worker, 290,
                        call -> {
                            byte[] nonCanonical = (" " + new String(
                                    call.business().payload(), StandardCharsets.UTF_8))
                                    .getBytes(StandardCharsets.UTF_8);
                            return call.withBusiness(
                                    new BusinessEnvelope(nonCanonical, sha256(nonCanonical)));
                        }, "INGESTION_QUALITY_BUSINESS_PAYLOAD_NOT_CANONICAL"));

        Scenario valid = sealed(jdbc, worker, 300);
        EvaluationCall call = validEvaluation(valid);
        assertTrue(evaluate(worker, call));
        assertNotEquals(valid.receive().traceId(), call.traceId(),
                "evaluate commands carry an independent trace from batch receipt");
        assertEquals(call.traceId(), jdbc.queryForObject("""
                select trace_id from ingestion_quality.iq_quality_snapshot
                 where snapshot_id=?
                """, String.class, valid.snapshotId()).trim());
        assertEquals(call.traceId(), jdbc.queryForObject("""
                select trace_id from ingestion_quality.iq_local_audit_fact
                 where audit_id=?
                """, String.class, call.commandId()).trim());
        assertEquals(call.traceId(), jdbc.queryForObject("""
                select payload #>> '{fact,traceId}'
                  from ingestion_quality.iq_local_audit_outbox
                 where audit_id=?
                """, String.class, call.commandId()));
        assertEquals(call.immutableHash(), jdbc.queryForObject("""
                select immutable_hash from ingestion_quality.iq_quality_snapshot
                 where snapshot_id=?
                """, String.class, valid.snapshotId()).trim());
        assertEquals(token("ost", valid.snapshotId().toString()), jdbc.queryForObject("""
                select object_search_token
                  from ingestion_quality.iq_quality_snapshot_audit_token_binding
                 where snapshot_id=?
                """, String.class, valid.snapshotId()));
        assertEquals(valid.plan().definitions().stream()
                        .map(MetricDefinition::formulaId).toList(),
                jdbc.queryForList("""
                        select formula_id from ingestion_quality.iq_quality_snapshot_metric
                         where snapshot_id=? order by metric_ordinal
                        """, String.class, valid.snapshotId()),
                "the persisted order must be the approved common-then-source QMDP order");
        assertEquals(valid.plan().assessment().metricResults().stream()
                        .map(QualityMetricResult::metricId).toList(),
                jdbc.queryForList("""
                        select metric_id from ingestion_quality.iq_quality_snapshot_metric
                         where snapshot_id=? order by metric_ordinal
                        """, String.class, valid.snapshotId()));

        byte[] storedBusinessPayload = jdbc.queryForObject("""
                select payload_utf8 from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, byte[].class, valid.businessEventId());
        assertArrayEquals(call.business().payload(), storedBusinessPayload);
        assertEquals(sha256(storedBusinessPayload), jdbc.queryForObject("""
                select payload_digest from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, String.class, valid.businessEventId()).trim());
        assertPicBindings(readTree(new String(storedBusinessPayload, StandardCharsets.UTF_8)),
                valid, call);
    }

    @Test
    void lateAuditAndBusinessOutboxFailuresRollBackEveryEvaluationWrite() {
        ensureWorkerLogin();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload();

        Scenario auditFailure = sealed(jdbc, worker, 400);
        EvaluationCall auditCall = validEvaluation(auditFailure);
        seedAuditEnvelopeEventCollision(jdbc, auditFailure, auditCall.commandId());
        EvaluationFootprint auditBefore = footprint(jdbc, auditFailure, auditCall);
        assertThrows(DataAccessException.class, () -> evaluate(worker, auditCall));
        assertEquals(auditBefore, footprint(jdbc, auditFailure, auditCall),
                "an audit-envelope insert failure must undo snapshot/CAS/metrics/impact/idem");

        Scenario seededBusiness = sealed(jdbc, worker, 410);
        EvaluationCall seededCall = validEvaluation(seededBusiness);
        assertTrue(evaluate(worker, seededCall));
        Scenario businessFailure = sealed(jdbc, worker, 420);
        EvaluationCall businessCall = validEvaluation(businessFailure)
                .withBusinessEventId(seededBusiness.businessEventId());
        businessCall = businessCall.withBusiness(businessEnvelope(
                businessFailure, businessCall.commandId(), businessCall.traceId(),
                seededBusiness.businessEventId()));
        EvaluationFootprint businessBefore = footprint(jdbc, businessFailure, businessCall);
        EvaluationCall finalBusinessCall = businessCall;
        assertThrows(DataAccessException.class, () -> evaluate(worker, finalBusinessCall));
        assertEquals(businessBefore, footprint(jdbc, businessFailure, finalBusinessCall),
                "a business-outbox unique failure after audit must undo every target write");
    }

    @Test
    void snapshotReadAuditRejectsNonExactFactsAndForgedObjectBindings() {
        ensureWorkerLogin();
        JdbcTemplate jdbc = admin();
        JdbcTemplate worker = workload();
        Scenario scenario = sealed(jdbc, worker, 430);
        assertTrue(evaluate(worker, validEvaluation(scenario)));
        Instant occurredAt = databaseNow(jdbc).truncatedTo(ChronoUnit.MICROS);

        assertAll(
                () -> assertReadAuditRejected(jdbc, scenario, occurredAt, 1, fact ->
                        fact.put("unapprovedField", "forged")),
                () -> assertReadAuditRejected(jdbc, scenario, occurredAt, 2, fact ->
                        fact.put("objectSearchToken", token("ost", "other-snapshot"))),
                () -> assertReadAuditRejected(jdbc, scenario, occurredAt, 3, fact ->
                        fact.put("aggregateIdSearchToken", token("agt", "other-snapshot"))),
                () -> assertReadAuditRejected(
                        jdbc, scenario, occurredAt, 4, fact -> {
                            fact.put("objectSearchToken", token("ost", "other-snapshot"));
                            fact.put("aggregateIdSearchToken", token("agt", "other-snapshot"));
                        }, token("ost", "other-snapshot"),
                        token("agt", "other-snapshot")));

        assertReadAuditAccepted(jdbc, scenario, occurredAt, 5);
    }

    private static void assertReceiveRejected(
            JdbcTemplate jdbc, JdbcTemplate worker, ReceiveSpec spec, String code) {
        ReceiveFootprint before = receiveFootprint(jdbc, spec);
        assertDatabaseFailure(code, () -> receive(worker, spec));
        assertEquals(before, receiveFootprint(jdbc, spec),
                "invalid audit evidence must leave zero batch/idempotency/audit writes");
    }

    private static void assertSealRejected(
            JdbcTemplate jdbc, JdbcTemplate worker, int seed, JsonNode evidence) {
        Scenario scenario = receiving(jdbc, worker, seed);
        assertDatabaseFailure("INGESTION_QUALITY_SEALED_CONTRACT_INVALID",
                () -> seal(worker, scenario, write(evidence)));
        assertEquals("receiving", status(jdbc, scenario.batchId()));
        assertEquals(0, countByScope(jdbc, scenario.sealScopeDigest()));
    }

    private static void assertEvaluationRejected(
            JdbcTemplate jdbc,
            JdbcTemplate worker,
            int seed,
            java.util.function.UnaryOperator<EvaluationCall> mutation,
            String code) {
        Scenario scenario = sealed(jdbc, worker, seed);
        EvaluationCall call = mutation.apply(validEvaluation(scenario));
        EvaluationFootprint before = footprint(jdbc, scenario, call);
        assertDatabaseFailure(code, () -> evaluate(worker, call));
        assertEquals(before, footprint(jdbc, scenario, call),
                "rejected evaluation evidence must roll back the complete command footprint");
    }

    private static void assertReadAuditRejected(
            JdbcTemplate jdbc,
            Scenario scenario,
            Instant occurredAt,
            int ordinal,
            java.util.function.Consumer<ObjectNode> mutation) {
        assertReadAuditRejected(jdbc, scenario, occurredAt, ordinal, mutation,
                token("ost", scenario.snapshotId().toString()),
                token("agt", scenario.snapshotId().toString()));
    }

    private static void assertReadAuditRejected(
            JdbcTemplate jdbc,
            Scenario scenario,
            Instant occurredAt,
            int ordinal,
            java.util.function.Consumer<ObjectNode> mutation,
            String objectToken,
            String aggregateToken) {
        UUID auditId = uuid(id(scenario.snapshotId()) + 100L + ordinal * 2L);
        UUID eventId = uuid(id(scenario.snapshotId()) + 101L + ordinal * 2L);
        String actorToken = token("ast", "read-actor");
        String sourceIpToken = token("ipt", "127.0.0.1");
        String traceId = trace(43_000 + ordinal);
        AuditEnvelope envelope = readAuditEnvelope(
                eventId, auditId, scenario.snapshotId(), scenario.evaluatedAt(),
                traceId, occurredAt, actorToken);
        ObjectNode payload = (ObjectNode) readTree(envelope.payload());
        mutation.accept((ObjectNode) payload.required("fact"));
        String encoded = write(payload);
        String digest = canonicalDigest(encoded);
        int before = jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                 where audit_id=?
                """, Integer.class, auditId);

        assertDatabaseFailure("INGESTION_QUALITY_SNAPSHOT_READ_AUDIT_INVALID", () ->
                jdbc.queryForObject("""
                        select ingestion_quality.iq_append_quality_snapshot_read_audit(
                          ?, ?, ?, ?, ?, ?, ?, 'quality-snapshot-detail-read', 3, ?, ?, ?::jsonb, ?)
                        """, Void.class, auditId, eventId, scenario.snapshotId(), actorToken,
                        objectToken, sourceIpToken, aggregateToken, traceId,
                        Timestamp.from(occurredAt), encoded, digest));
        assertEquals(before, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                 where audit_id=?
                """, Integer.class, auditId));
    }

    private static void assertReadAuditAccepted(
            JdbcTemplate jdbc,
            Scenario scenario,
            Instant occurredAt,
            int ordinal) {
        UUID auditId = uuid(id(scenario.snapshotId()) + 100L + ordinal * 2L);
        UUID eventId = uuid(id(scenario.snapshotId()) + 101L + ordinal * 2L);
        String actorToken = token("ast", "read-actor");
        String objectToken = token("ost", scenario.snapshotId().toString());
        String sourceIpToken = token("ipt", "127.0.0.1");
        String aggregateToken = token("agt", scenario.snapshotId().toString());
        String traceId = trace(43_000 + ordinal);
        AuditEnvelope envelope = readAuditEnvelope(
                eventId, auditId, scenario.snapshotId(), scenario.evaluatedAt(),
                traceId, occurredAt, actorToken);

        jdbc.query("""
                select ingestion_quality.iq_append_quality_snapshot_read_audit(
                  ?, ?, ?, ?, ?, ?, ?, 'quality-snapshot-detail-read', 3, ?, ?, ?::jsonb, ?)
                """, (row, ignored) -> row.getObject(1),
                auditId, eventId, scenario.snapshotId(), actorToken,
                objectToken, sourceIpToken, aggregateToken, traceId,
                Timestamp.from(occurredAt), envelope.payload(), envelope.digest());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                 where audit_id=? and snapshot_id=?
                """, Integer.class, auditId, scenario.snapshotId()));
    }

    private static Scenario receiving(JdbcTemplate jdbc, JdbcTemplate worker, int seed) {
        ReceiveSpec receive = receiveSpec(jdbc, seed);
        assertEquals(receive.batchId(), DataBatchAtomicEvidencePostgreSqlIT.receive(worker, receive));
        Plan plan = passingPlan();
        for (int ordinal = 0; ordinal < plan.definitions().size(); ordinal++) {
            MetricDefinition definition = plan.definitions().get(ordinal);
            MeasuredQualityInputs input = plan.inputs().get(definition.formulaId());
            String operands = write(input.operands());
            assertTrue(Boolean.TRUE.equals(worker.queryForObject("""
                    select ingestion_quality.iq_record_batch_quality_measurement(
                      ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """, Boolean.class, receive.batchId(), definition.formulaId(), ordinal,
                    input.applicable(), operands, "sha256:" + canonicalDigest(operands),
                    Timestamp.from(receive.receivedAt().plusSeconds(ordinal + 1L)))));
        }
        assertTrue(Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_record_batch_quality_impact_scope(?, ?, ?)
                """, Boolean.class, receive.batchId(), IMPACT_SCOPE,
                Timestamp.from(receive.receivedAt().plusSeconds(20)))));
        Instant sealedAt = receive.receivedAt().plusSeconds(30);
        UUID sealCommand = uuid(seed * 100L + 20);
        String sealScope = sha256(bytes("seal-scope-" + seed));
        String sealRequest = digest("seal-request-" + seed);
        String sealTrace = trace(seed + 10_000);
        AuditEnvelope sealAudit = auditEnvelope(
                sealCommand, sealCommand, receive.batchId(), "data-batch.seal", 2,
                sealTrace, sealRequest, sealedAt);
        String evaluationTrace = trace(seed + 20_000);
        return new Scenario(
                receive, plan, sealCommand, sealScope, sealRequest, sealAudit,
                sealTrace, sealedAt,
                uuid(seed * 100L + 30), uuid(seed * 100L + 31),
                receive.receivedAt().plusSeconds(40), evaluationTrace);
    }

    private static Scenario sealed(JdbcTemplate jdbc, JdbcTemplate worker, int seed) {
        Scenario scenario = receiving(jdbc, worker, seed);
        assertTrue(seal(worker, scenario, write(SEALED_EVIDENCE)));
        return scenario;
    }

    private static ReceiveSpec receiveSpec(JdbcTemplate jdbc, int seed) {
        Instant receivedAt = databaseNow(jdbc).truncatedTo(ChronoUnit.MICROS).minusSeconds(120);
        UUID commandId = uuid(seed * 100L + 10);
        UUID batchId = uuid(seed * 100L + 11);
        UUID lineageId = uuid(seed * 100L + 12);
        String traceId = trace(seed);
        String scope = sha256(bytes("receive-scope-" + seed));
        String request = digest("receive-request-" + seed);
        AuditEnvelope audit = auditEnvelope(
                commandId, commandId, batchId, "data-batch.receive", 1,
                traceId, request, receivedAt);
        return new ReceiveSpec(
                commandId, batchId, lineageId,
                bytes("atomic-evidence-business-key-" + seed), receivedAt,
                receivedAt.minusSeconds(60), digest("manifest-" + seed), traceId,
                scope, request, audit);
    }

    private static UUID receive(JdbcTemplate worker, ReceiveSpec spec) {
        return receiveOutcome(worker, spec).batchId();
    }

    private static ReceiveOutcome receiveOutcome(JdbcTemplate worker, ReceiveSpec spec) {
        return worker.queryForObject("""
                select batch_id, disposition from ingestion_quality.iq_receive_data_batch(
                  ?, ?, 'SRC-P0-CARD-001', ?, 1, ?, null, null, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, (row, ignored) -> new ReceiveOutcome(
                        row.getObject("batch_id", UUID.class), row.getString("disposition")),
                spec.commandId(), spec.batchId(), spec.businessKeyUtf8(),
                spec.lineageId(), Timestamp.from(spec.effectiveAt()), spec.manifestDigest(),
                Timestamp.from(spec.receivedAt()), spec.traceId(), spec.scopeDigest(),
                spec.requestDigest(), spec.audit().payload(), spec.audit().digest());
    }

    private static boolean seal(JdbcTemplate worker, Scenario scenario, String evidenceJson) {
        var dataCatalog = CONTRACT.policy().controlledInputs().dataCatalog();
        var qualityGate = CONTRACT.policy().controlledInputs().qualityGate();
        var sourceSchema = SOURCE.schemaBinding();
        String laneId = SOURCE.freshnessLanes().getFirst().laneId();
        ReceiveSpec receive = scenario.receive();
        Instant cutoffAt = receive.receivedAt().minusSeconds(10);
        return Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_seal_data_batch(
                  ?, ?, 1, 1, 0, 1, ?, ?, ?, 'Asia/Shanghai', ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                  ?, ?, ?, ?, ?::jsonb, ?)
                """, Boolean.class,
                scenario.sealCommandId(), receive.batchId(),
                Timestamp.from(cutoffAt.minusSeconds(720L * 3_600L)),
                Timestamp.from(cutoffAt), Timestamp.from(cutoffAt),
                bytes("src-p0-card-001@2026-08-10"),
                sourceSchema.version(), sourceSchema.canonicalDigest(),
                dataCatalog.version(), dataCatalog.canonicalDigest(),
                qualityGate.version(), qualityGate.canonicalDigest(),
                CONTRACT.policy().profileVersion(),
                CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                Timestamp.from(receive.receivedAt().minusSeconds(20)),
                Timestamp.from(receive.receivedAt().plusSeconds(3_600)),
                Timestamp.from(receive.receivedAt()), laneId, receive.manifestDigest(),
                evidenceJson, Timestamp.from(scenario.sealedAt()),
                scenario.sealTraceId(), scenario.sealScopeDigest(),
                scenario.sealRequestDigest(),
                scenario.sealAudit().payload(), scenario.sealAudit().digest()));
    }

    private static EvaluationCall validEvaluation(Scenario scenario) {
        QualitySnapshot snapshot = expectedSnapshot(scenario);
        UUID commandId = uuid(id(scenario.snapshotId()) + 2);
        String scope = sha256(bytes("evaluate-scope-" + scenario.snapshotId()));
        String request = digest("evaluate-request-" + scenario.snapshotId());
        AuditEnvelope audit = auditEnvelope(
                commandId, commandId, scenario.batchId(), "data-batch.evaluate", 3,
                scenario.evaluationTraceId(), request, scenario.evaluatedAt());
        BusinessEnvelope business = businessEnvelope(
                scenario, commandId, scenario.evaluationTraceId(),
                scenario.businessEventId());
        return new EvaluationCall(
                commandId, scenario.batchId(), scenario.snapshotId(),
                scenario.plan().assessment().overallResult().wireValue(),
                scenario.evaluatedAt(), scenario.evaluationTraceId(),
                snapshot.immutableHash(),
                QualitySnapshotRetentionScopeCanonicalizer.digest(snapshot),
                scenario.plan().metricsJson(),
                scope, request, audit, scenario.businessEventId(), business);
    }

    private static boolean evaluate(JdbcTemplate worker, EvaluationCall call) {
        return Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_commit_batch_quality_evaluation(
                  ?, ?, 2, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?,
                  ?, ?, ?, ?, ?)
                """, Boolean.class,
                call.commandId(), call.batchId(), call.snapshotId(),
                token("ost", call.snapshotId().toString()),
                token("agt", call.snapshotId().toString()), call.overallResult(),
                SOURCE.owner(), Timestamp.from(call.evaluatedAt()), call.traceId(),
                call.immutableHash(), call.retentionScopeDigest(), call.metrics(),
                call.scopeDigest(), call.requestDigest(), call.audit().payload(),
                call.audit().digest(), call.businessEventId(), EVENT_TYPE, EVENT_SCHEMA,
                call.business().payload(), call.business().digest()));
    }

    private static EvaluationCall mutateBusiness(
            JdbcTemplate jdbc,
            EvaluationCall call,
            java.util.function.Consumer<ObjectNode> mutation) {
        return mutateBusinessPayload(jdbc, call, payload -> mutation.accept(
                (ObjectNode) payload.required("data").required("qualitySnapshot")));
    }

    private static EvaluationCall mutateBusinessPayload(
            JdbcTemplate jdbc,
            EvaluationCall call,
            java.util.function.Consumer<ObjectNode> mutation) {
        ObjectNode payload = (ObjectNode) readTree(
                new String(call.business().payload(), StandardCharsets.UTF_8));
        mutation.accept(payload);
        byte[] canonical = jdbc.queryForObject("""
                select convert_to(ingestion_quality.iq_json_canonical(?::jsonb), 'UTF8')
                """, byte[].class, write(payload));
        return call.withBusiness(new BusinessEnvelope(canonical, sha256(canonical)));
    }

    private static Plan passingPlan() {
        DataBatchPostgreSqlEvidenceFixtures.Plan fixture =
                DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD;
        QualityAssessment assessment = fixture.assessment();
        assertEquals(QualityOverallResult.QUALITY_PASSED, assessment.overallResult());
        return new Plan(
                fixture.definitions(), fixture.inputs(), assessment, fixture.metricsJson());
    }

    private static String metricsJson(List<QualityMetricResult> results) {
        ArrayNode metrics = JSON.createArrayNode();
        for (QualityMetricResult metric : results) {
            ObjectNode value = JSON.createObjectNode();
            value.put("metricId", metric.metricId());
            value.put("formulaId", metric.formulaId());
            value.put("formulaVersion", metric.formulaVersion());
            value.put("result", metric.result().wireValue());
            value.put("applicable", metric.applicable());
            value.put("numerator", metric.numerator());
            value.put("denominator", metric.denominator());
            if (metric.valueBasisPoints() == null) value.putNull("valueBasisPoints");
            else value.put("valueBasisPoints", metric.valueBasisPoints());
            value.put("unit", metric.unit().wireValue());
            value.put("operator", metric.operator().wireValue());
            value.put("thresholdNumerator", metric.thresholdNumerator());
            value.put("thresholdDenominator", metric.thresholdDenominator());
            value.put("boundary", metric.boundary().wireValue());
            if (metric.reasonCode() == null) value.putNull("reasonCode");
            else value.put("reasonCode", metric.reasonCode());
            metrics.add(value);
        }
        return write(metrics);
    }

    private static QualitySnapshotRetentionScopeCanonicalizer.Material retentionMaterial(
            Instant evaluatedAt, Instant retentionDueAt) {
        return new QualitySnapshotRetentionScopeCanonicalizer.Material(
                "QualitySnapshot",
                UUID.fromString("019d2c7d-4000-7000-8000-000000000110"),
                "SRC-P0-STUDENT-001",
                9L,
                evaluatedAt,
                retentionDueAt,
                "sha256:" + "1".repeat(64),
                "QUALITY-SNAPSHOT-RETENTION-1.0.0",
                "RS-1.0.0");
    }

    private static String postgresRetentionScopeDigest(
            JdbcTemplate jdbc,
            QualitySnapshotRetentionScopeCanonicalizer.Material material) {
        return jdbc.queryForObject("""
                with scope_value as (
                  select ?::text as object_type, ?::uuid as snapshot_id,
                         ?::text as source_id, ?::bigint as aggregate_version,
                         ?::timestamptz as evaluated_at,
                         ?::timestamptz as retention_due_at,
                         ?::text as immutable_hash, ?::text as policy_version,
                         ?::text as schedule_version
                )
                select 'sha256:' || encode(sha256(convert_to(
                  ingestion_quality.iq_json_canonical(jsonb_build_object(
                    'objectType', object_type,
                    'snapshotId', snapshot_id::text,
                    'sourceId', source_id,
                    'snapshotAggregateVersion', aggregate_version,
                    'evaluatedAt', case
                      when date_trunc('second', evaluated_at) = evaluated_at
                        then to_char(evaluated_at at time zone 'UTC',
                                     'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                      else ingestion_quality.iq_canonical_instant(evaluated_at)
                    end,
                    'retentionDueAt', case
                      when date_trunc('second', retention_due_at) = retention_due_at
                        then to_char(retention_due_at at time zone 'UTC',
                                     'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                      else ingestion_quality.iq_canonical_instant(retention_due_at)
                    end,
                    'snapshotImmutableHash', immutable_hash,
                    'retentionPolicyVersion', policy_version,
                    'retentionScheduleVersion', schedule_version)), 'UTF8')), 'hex')
                  from scope_value
                """, String.class,
                material.objectType(), material.snapshotId(), material.sourceId(),
                material.snapshotAggregateVersion(), Timestamp.from(material.evaluatedAt()),
                Timestamp.from(material.retentionDueAt()), material.snapshotImmutableHash(),
                material.retentionPolicyVersion(), material.retentionScheduleVersion());
    }

    private static QualitySnapshot expectedSnapshot(Scenario scenario) {
        QualitySnapshot provisional = expectedSnapshot(scenario, digest("provisional"));
        return expectedSnapshot(scenario, QSHM.immutableHash(provisional));
    }

    private static QualitySnapshot expectedSnapshot(
            Scenario scenario, String immutableHash) {
        ReceiveSpec receive = scenario.receive();
        Instant cutoffAt = receive.receivedAt().minusSeconds(10);
        return new QualitySnapshot(
                CONTRACT.hashProfile().domainTag(), CONTRACT.hashProfile().hashProfileVersion(),
                CONTRACT.attestation().qshmProfileCanonicalDigest(), receive.batchId(),
                SOURCE.sourceId(), DataBatchStatus.QUALITY_PASSED,
                scenario.plan().assessment().overallResult(),
                new BatchObservationWindow(
                        cutoffAt.minusSeconds(720L * 3_600L), cutoffAt),
                cutoffAt,
                "src-p0-card-001@2026-08-10",
                scenario.plan().assessment().metricResults(),
                List.of("PRIMARY_KEY_COMPLETENESS_BP"),
                SOURCE.owner(), CONTRACT.policy().approvalRef(), CONTRACT.policy().effectiveAt(),
                "RS-1.0.0", CONTRACT.policy().profileVersion(),
                CONTRACT.attestation().qmdpPolicyCanonicalDigest(),
                CONTRACT.policy().controlledInputs().qualityGate().version(),
                CONTRACT.policy().controlledInputs().qualityGate().canonicalDigest(),
                CONTRACT.policy().canonicalization().profile(), receive.manifestDigest(),
                SOURCE.schemaBinding().version(), SOURCE.schemaBinding().canonicalDigest(),
                receive.lineageId(), null, scenario.snapshotId(), scenario.evaluatedAt(),
                scenario.evaluationTraceId(), 3L, immutableHash);
    }

    private static AuditEnvelope auditEnvelope(
            UUID eventId,
            UUID auditId,
            UUID batchId,
            String action,
            long aggregateVersion,
            String traceId,
            String requestDigest,
            Instant occurredAt) {
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("decision", "allow");
        authorization.put("policyVersion",
                "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0");
        authorization.put("scopeCodes", List.of("QUALITY_WORKLOAD"));
        authorization.put("grantSearchTokens", List.of());
        authorization.put("notApplicableReason", null);
        LocalAuditFact fact = new LocalAuditFact(
                auditId, "LOCAL-AUDIT-FACT-1.0.0", "ingestion-quality",
                ActorType.SERVICE, token("ast", WORKER_LOGIN), List.of("QUALITY_WORKER"),
                authorization, action, "data-batch", token("ost", batchId.toString()),
                "accepted", "INGESTION_QUALITY_COMMAND_ACCEPTED", "DATA_QUALITY",
                "QUALITY_WORKLOAD", occurredAt, occurredAt,
                new TimeSourceProfile(
                        "iq-db-clock", "AUDIT-CLOCK-BINDING-1.0.0", 0,
                        occurredAt.minusSeconds(1), occurredAt.plusSeconds(60),
                        "evidence://signed/ingestion-quality/db-clock"),
                null, "AUDIT-TOKENIZATION-1.0.0", "k1", traceId,
                "data-batch", token("agt", batchId.toString()), aggregateVersion,
                requestDigest.substring("sha256:".length()),
                Map.of("workloadAuthorization",
                        "INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0"), "RS-1.0.0");
        LocalAuditOutboxRecord record = new LocalAuditOutboxRecord(
                eventId, auditId, "ingestion-quality.local-audit-fact.recorded.v1",
                "LOCAL-AUDIT-OUTBOX-1.0.0", "ingestion-quality", occurredAt, fact);
        String payload = write(record);
        return new AuditEnvelope(payload, canonicalDigest(payload), record);
    }

    private static AuditEnvelope readAuditEnvelope(
            UUID eventId,
            UUID auditId,
            UUID snapshotId,
            Instant evaluatedAt,
            String traceId,
            Instant occurredAt,
            String actorToken) {
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("decision", "allow");
        authorization.put("policyVersion", "RFP-1.0.0");
        authorization.put("scopeCodes", List.of("OWNED_SOURCE"));
        authorization.put("grantSearchTokens", List.of());
        authorization.put("notApplicableReason", null);
        LocalAuditFact fact = new LocalAuditFact(
                auditId, "LOCAL-AUDIT-FACT-1.0.0", "ingestion-quality",
                ActorType.USER, actorToken, List.of("R6"), authorization,
                "quality-snapshot-detail-read", "quality-snapshot",
                token("ost", snapshotId.toString()), "accepted",
                "INGESTION_QUALITY_SNAPSHOT_READ_ALLOWED", "DATA_QUALITY",
                "OWNED_SOURCE", occurredAt, occurredAt,
                new TimeSourceProfile(
                        "iq-read-clock", "AUDIT-CLOCK-BINDING-1.0.0", 0,
                        evaluatedAt.minusSeconds(1), occurredAt.plusSeconds(60),
                        "evidence://signed/ingestion-quality/read-clock"),
                token("ipt", "127.0.0.1"), "AUDIT-TOKENIZATION-1.0.0", "k1",
                traceId, "quality-snapshot", token("agt", snapshotId.toString()), 3L,
                null, Map.of("roleFieldPolicy", "RFP-1.0.0"), "RS-1.0.0");
        LocalAuditOutboxRecord record = new LocalAuditOutboxRecord(
                eventId, auditId, "ingestion-quality.local-audit-fact.recorded.v1",
                "LOCAL-AUDIT-OUTBOX-1.0.0", "ingestion-quality", occurredAt, fact);
        String payload = write(record);
        return new AuditEnvelope(payload, canonicalDigest(payload), record);
    }

    private static BusinessEnvelope businessEnvelope(
            Scenario scenario, UUID commandId, String traceId, UUID eventId) {
        DataBatchPostgreSqlEvidenceFixtures.BusinessEnvelope fixture =
                DataBatchPostgreSqlEvidenceFixtures.assessedBusiness(
                        commandId, scenario.sealCommandId(), eventId,
                        scenario.batchId(), traceId, scenario.evaluatedAt(), 1L,
                        scenario.receive().effectiveAt(), expectedSnapshot(scenario));
        return new BusinessEnvelope(fixture.payload(), fixture.digest());
    }

    private static void assertPicBindings(
            JsonNode payload, Scenario scenario, EvaluationCall call) {
        assertEquals("1.0", payload.required("specversion").asText());
        assertEquals(call.businessEventId().toString(), payload.required("id").asText());
        assertEquals(EVENT_TYPE, payload.required("type").asText());
        JsonNode data = payload.required("data");
        assertEquals("PIC-1.0.0", data.required("contractVersion").asText());
        assertEquals(call.businessEventId().toString(), data.required("eventId").asText());
        assertEquals("data-batch", data.required("aggregateType").asText());
        assertEquals(scenario.batchId().toString(), data.required("aggregateId").asText());
        assertEquals(3L, data.required("aggregateVersion").asLong());
        assertEquals(call.traceId(), data.required("traceId").asText());
        assertEquals(EVENT_SCHEMA, data.required("schemaVersion").asText());
        assertEquals(scenario.snapshotId().toString(),
                data.required("qualitySnapshot").required("snapshotId").asText());
        assertEquals("quality-passed",
                data.required("batch").required("status").asText());
    }

    private static void seedAuditEnvelopeEventCollision(
            JdbcTemplate jdbc, Scenario scenario, UUID collidingEventId) {
        UUID auditId = uuid(id(collidingEventId) + 50);
        Timestamp expiresAt = jdbc.queryForObject("""
                select ((?::timestamptz at time zone 'UTC') + interval '3 years')
                         at time zone 'UTC'
                """, Timestamp.class, Timestamp.from(scenario.sealedAt()));
        AuditEnvelope seed = auditEnvelope(
                collidingEventId, auditId, scenario.batchId(), "data-batch.test-collision",
                2, scenario.sealTraceId(), digest("audit-collision-" + collidingEventId),
                scenario.sealedAt());
        jdbc.update("""
                insert into ingestion_quality.iq_local_audit_fact
                  (audit_id, actor_search_token, action, result, batch_id,
                   aggregate_version, trace_id, occurred_at, authorization_context,
                   request_digest, expires_at)
                values (?, ?, 'data-batch.test-collision', 'accepted', ?, 2, ?, ?, ?::jsonb, ?, ?)
                """, auditId, token("ast", WORKER_LOGIN), scenario.batchId(),
                scenario.sealTraceId(), Timestamp.from(scenario.sealedAt()),
                write(seed.record().fact().authorizationContext()),
                digest("audit-collision-" + collidingEventId),
                expiresAt);
        jdbc.update("""
                insert into ingestion_quality.iq_local_audit_outbox
                  (event_id, audit_id, event_type, schema_version, producer,
                   payload, payload_digest, available_at, created_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, collidingEventId, auditId, seed.record().eventType(),
                seed.record().schemaVersion(), seed.record().producer(), seed.payload(),
                seed.digest(), Timestamp.from(scenario.sealedAt()),
                Timestamp.from(scenario.sealedAt()));
    }

    private static EvaluationFootprint footprint(
            JdbcTemplate jdbc, Scenario scenario, EvaluationCall call) {
        return new EvaluationFootprint(
                status(jdbc, scenario.batchId()), jdbc.queryForObject("""
                        select aggregate_version from ingestion_quality.iq_data_batch
                         where batch_id=?
                        """, Long.class, scenario.batchId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_quality_snapshot
                         where batch_id=?
                        """, Integer.class, scenario.batchId()),
                jdbc.queryForObject("""
                        select count(*)
                          from ingestion_quality.iq_quality_snapshot_audit_token_binding binding
                          join ingestion_quality.iq_quality_snapshot snapshot
                            on snapshot.snapshot_id=binding.snapshot_id
                         where snapshot.batch_id=?
                        """, Integer.class, scenario.batchId()),
                jdbc.queryForObject("""
                        select count(*)
                          from ingestion_quality.iq_quality_snapshot_metric metric
                          join ingestion_quality.iq_quality_snapshot snapshot
                            on snapshot.snapshot_id=metric.snapshot_id
                         where snapshot.batch_id=?
                        """, Integer.class, scenario.batchId()),
                jdbc.queryForObject("""
                        select count(*)
                          from ingestion_quality.iq_quality_snapshot_impact_scope impact
                          join ingestion_quality.iq_quality_snapshot snapshot
                            on snapshot.snapshot_id=impact.snapshot_id
                         where snapshot.batch_id=?
                        """, Integer.class, scenario.batchId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_batch_quality_impact_scope
                         where batch_id=? and sealed_at=?
                        """, Integer.class, scenario.batchId(), Timestamp.from(scenario.sealedAt())),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_fact
                         where audit_id=?
                        """, Integer.class, call.commandId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_outbox
                         where audit_id=?
                        """, Integer.class, call.commandId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_batch_quality_outbox
                         where aggregate_id=?
                        """, Integer.class, scenario.batchId()),
                countByScope(jdbc, call.scopeDigest()));
    }

    private static ReceiveFootprint receiveFootprint(JdbcTemplate jdbc, ReceiveSpec spec) {
        return new ReceiveFootprint(
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_data_batch where batch_id=?
                        """, Integer.class, spec.batchId()),
                countByScope(jdbc, spec.scopeDigest()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_fact where audit_id=?
                        """, Integer.class, spec.commandId()),
                jdbc.queryForObject("""
                        select count(*) from ingestion_quality.iq_local_audit_outbox where event_id=?
                        """, Integer.class, spec.commandId()));
    }

    private static int countByScope(JdbcTemplate jdbc, String scope) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_idempotency where scope_digest=?
                """, Integer.class, scope);
    }

    private static String status(JdbcTemplate jdbc, UUID batchId) {
        return jdbc.queryForObject("""
                select status from ingestion_quality.iq_data_batch where batch_id=?
                """, String.class, batchId);
    }

    private static void assertDatabaseFailure(String code, Runnable command) {
        DataAccessException failure = assertThrows(DataAccessException.class, command::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.contains(code), message);
    }

    private static JsonNode mutated(
            JsonNode source, java.util.function.Consumer<ObjectNode> mutation) {
        ObjectNode copy = ((ObjectNode) source).deepCopy();
        mutation.accept(copy);
        return copy;
    }

    private static String mutatedMetrics(
            String source, java.util.function.Consumer<ArrayNode> mutation) {
        ArrayNode copy = ((ArrayNode) readTree(source)).deepCopy();
        mutation.accept(copy);
        return write(copy);
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

    private static void requireDynamicallyAppliedFeatureMigration() throws Exception {
        List<Migration> inventory = new ArrayList<>();
        try (var paths = Files.walk(MIGRATIONS)) {
            paths.filter(path -> path.getFileName().toString().startsWith("V"))
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(path -> inventory.add(migration(path)));
        }
        inventory.sort(Comparator.comparingInt(Migration::version));
        List<Migration> feature = inventory.stream()
                .filter(value -> value.path().getFileName().toString().endsWith(FEATURE_SUFFIX))
                .toList();
        assertEquals(1, feature.size());
        int predecessor = inventory.stream()
                .filter(value -> value.version() < feature.getFirst().version())
                .mapToInt(Migration::version).max().orElseThrow();
        assertEquals(predecessor + 1, feature.getFirst().version());
        assertTrue(Boolean.TRUE.equals(admin().queryForObject("""
                select to_regprocedure(
                  'ingestion_quality.iq_commit_batch_quality_evaluation(uuid,uuid,bigint,uuid,character varying,character varying,character varying,character varying,timestamp with time zone,character,character,character,jsonb,character,character,jsonb,character,uuid,character varying,character varying,bytea,character)')
                       is not null
                """, Boolean.class)), "the runner must apply the dynamically discovered migration");
    }

    private static Migration migration(Path path) {
        Matcher matcher = VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) throw new IllegalStateException("invalid migration " + path);
        return new Migration(Integer.parseInt(matcher.group(1)), path);
    }

    private static void ensureWorkerLogin() {
        admin().execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_batch_atomic_evidence_login') then
                        create role scholarsense_iq_batch_atomic_evidence_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_batch_atomic_evidence_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_batch_owner
                  from scholarsense_iq_batch_atomic_evidence_login;
                grant scholarsense_ingestion_quality_quality_worker
                  to scholarsense_iq_batch_atomic_evidence_login with inherit true, set false;
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
        return sha256(bytes("trace-" + seed)).substring(0, 32);
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

    private static <T> T read(String value, Class<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("invalid JSON fixture", failure);
        }
    }

    private static JsonNode readTree(String value) {
        try {
            return JSON.readTree(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("invalid JSON fixture", failure);
        }
    }

    private static UUID uuid(long value) {
        return UUID.fromString("019fea10-0000-7000-8000-%012x".formatted(value));
    }

    private static long id(UUID value) {
        return value.getLeastSignificantBits() & 0x0000ffffffffffffL;
    }

    private record Migration(int version, Path path) {}

    private record AuditEnvelope(
            String payload, String digest, LocalAuditOutboxRecord record) {}

    private record BusinessEnvelope(byte[] payload, String digest) {
        private BusinessEnvelope {
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private record ReceiveSpec(
            UUID commandId,
            UUID batchId,
            UUID lineageId,
            byte[] businessKeyUtf8,
            Instant receivedAt,
            Instant effectiveAt,
            String manifestDigest,
            String traceId,
            String scopeDigest,
            String requestDigest,
            AuditEnvelope audit) {
        private ReceiveSpec {
            businessKeyUtf8 = businessKeyUtf8.clone();
        }
        @Override public byte[] businessKeyUtf8() { return businessKeyUtf8.clone(); }
        ReceiveSpec withAudit(AuditEnvelope replacement) {
            return new ReceiveSpec(
                    commandId, batchId, lineageId, businessKeyUtf8, receivedAt, effectiveAt,
                    manifestDigest, traceId, scopeDigest, requestDigest, replacement);
        }
    }

    private record Plan(
            List<MetricDefinition> definitions,
            Map<String, MeasuredQualityInputs> inputs,
            QualityAssessment assessment,
            String metricsJson) {}

    private record Scenario(
            ReceiveSpec receive,
            Plan plan,
            UUID sealCommandId,
            String sealScopeDigest,
            String sealRequestDigest,
            AuditEnvelope sealAudit,
            String sealTraceId,
            Instant sealedAt,
            UUID snapshotId,
            UUID businessEventId,
            Instant evaluatedAt,
            String evaluationTraceId) {
        UUID batchId() { return receive.batchId(); }
    }

    private record EvaluationCall(
            UUID commandId,
            UUID batchId,
            UUID snapshotId,
            String overallResult,
            Instant evaluatedAt,
            String traceId,
            String immutableHash,
            String retentionScopeDigest,
            String metrics,
            String scopeDigest,
            String requestDigest,
            AuditEnvelope audit,
            UUID businessEventId,
            BusinessEnvelope business) {
        EvaluationCall withMetrics(String value) {
            return copy(overallResult, traceId, immutableHash, value, businessEventId, business);
        }
        EvaluationCall withOverallResult(String value) {
            return copy(value, traceId, immutableHash, metrics, businessEventId, business);
        }
        EvaluationCall withTraceId(String value) {
            AuditEnvelope changedAudit = auditEnvelope(
                    commandId, commandId, batchId, "data-batch.evaluate", 3,
                    value, requestDigest, evaluatedAt);
            return new EvaluationCall(
                    commandId, batchId, snapshotId, overallResult, evaluatedAt, value,
                    immutableHash, retentionScopeDigest, metrics, scopeDigest, requestDigest,
                    changedAudit, businessEventId, business);
        }
        EvaluationCall withImmutableHash(String value) {
            return copy(overallResult, traceId, value, metrics, businessEventId, business);
        }
        EvaluationCall withRetentionScopeDigest(String value) {
            return new EvaluationCall(
                    commandId, batchId, snapshotId, overallResult, evaluatedAt, traceId,
                    immutableHash, value, metrics, scopeDigest, requestDigest, audit,
                    businessEventId, business);
        }
        EvaluationCall withBusiness(BusinessEnvelope value) {
            return copy(overallResult, traceId, immutableHash, metrics, businessEventId, value);
        }
        EvaluationCall withBusinessEventId(UUID value) {
            return copy(overallResult, traceId, immutableHash, metrics, value, business);
        }
        private EvaluationCall copy(
                String overall,
                String trace,
                String hash,
                String metricJson,
                UUID eventId,
                BusinessEnvelope businessEnvelope) {
            return new EvaluationCall(
                    commandId, batchId, snapshotId, overall, evaluatedAt, trace, hash,
                    retentionScopeDigest, metricJson, scopeDigest, requestDigest, audit,
                    eventId, businessEnvelope);
        }
    }

    private record AuditBinding(
            UUID auditId,
            UUID eventId,
            UUID batchId,
            String action,
            String outcome,
            long aggregateVersion,
            String traceId,
            String requestDigest,
            String eventType,
            String schemaVersion,
            String producer) {}

    private record ReceiveFootprint(int batches, int claims, int auditFacts, int auditOutboxes) {}

    private record ReceiveOutcome(UUID batchId, String disposition) {}

    private record EvaluationFootprint(
            String status,
            long aggregateVersion,
            int snapshots,
            int snapshotAuditTokenBindings,
            int snapshotMetrics,
            int snapshotImpacts,
            int sealedStagedImpacts,
            int auditFacts,
            int auditOutboxes,
            int businessOutboxes,
            int claims) {}
}
