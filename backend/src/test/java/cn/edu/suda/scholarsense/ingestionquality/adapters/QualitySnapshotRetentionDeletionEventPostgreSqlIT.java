package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PostgreSQL 18.4 contract for the production owner-local retention-result handoff.
 *
 * <p>The retention workload supplies only bound identifiers and a trace. PostgreSQL owns the
 * complete production 1.1 event, derives it from the locked database outcome, and stores one
 * canonical byte representation in both the result and outbox. Authority rows in this fixture are
 * synthetic administrator setup for the independently tested production authority boundary.
 */
@TestMethodOrder(OrderAnnotation.class)
class QualitySnapshotRetentionDeletionEventPostgreSqlIT {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path EVENT_ROOT =
            REPOSITORY.resolve("contracts/events/ingestion-quality");
    private static final Path EVENT_SCHEMA =
            EVENT_ROOT.resolve("quality-snapshot-deletion-result-1.1.0.schema.json");
    private static final Path COMPLETED_FIXTURE = EVENT_ROOT.resolve(
            "fixtures/valid/quality-snapshot-deletion-production-completed-v1.json");
    private static final Path BLOCKED_FIXTURE = EVENT_ROOT.resolve(
            "fixtures/valid/quality-snapshot-deletion-production-blocked-v1.json");
    private static final Path RETENTION_POLICY = REPOSITORY.resolve(
            "contracts/ingestion-quality/batch-quality/"
                    + "quality-snapshot-retention-1.0.0.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String RETENTION_LOGIN =
            "scholarsense_iq_ret_event_executor_test_login";
    private static final String EVENT_TYPE =
            "scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1";
    private static final String EVENT_SCHEMA_VERSION =
            "QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0";
    private static final String SOURCE_ID = "SRC-P0-CARD-001";
    private static final String TRACE_ID = "123456789abcdef0123456789abcdef0";
    private static final String RETENTION_TRACE_ID = "abcdef0123456789abcdef0123456789";
    private static final int METRIC_COUNT = 14;
    private static final Instant RECEIVED_AT = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant SEALED_AT = Instant.parse("2024-01-01T01:00:00Z");
    private static final Instant EVALUATED_AT = Instant.parse("2024-01-02T00:00:00Z");
    private static final Instant RETENTION_DUE_AT = Instant.parse("2026-01-02T00:00:00Z");

    private static final Set<String> ROOT_FIELDS = Set.of(
            "specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "traceparent", "data");
    private static final Set<String> DATA_FIELDS = Set.of(
            "resultContractVersion", "contractVersion", "correlationId", "causationId",
            "eventId", "aggregateType", "aggregateId", "aggregateVersion", "executionId",
            "supersedesResultId", "producer", "occurredAt", "traceId", "result",
            "retentionPolicyVersion", "retentionScheduleVersion", "scope", "guards",
            "ownerLocalResults", "deletionCommittedAt", "blockerCodes", "failureCodes",
            "backup", "auditHandoff", "canonicalizationProfile", "runtimeEvidenceClaim");
    private static final Set<String> SCOPE_FIELDS = Set.of(
            "objectType", "snapshotId", "sourceId", "snapshotAggregateVersion",
            "evaluatedAt", "retentionDueAt", "snapshotImmutableHash", "scopeDigest");
    private static final Set<String> GUARD_FIELDS = Set.of(
            "trustedTime", "legalHold", "consumerRegistry", "consumerWatermarksCheckedAt",
            "consumerWatermarks");
    private static final Set<String> OWNER_RESULT_FIELDS = Set.of(
            "onlineSnapshot", "onlineMetrics", "readModels", "indexes", "caches", "objects");
    private static final Set<String> TARGET_FIELDS = Set.of(
            "status", "selectedCount", "deletedCount", "remainingCount", "evidenceDigest",
            "errorCode", "transactionId", "transactionEvidenceDigest");
    private static final Set<String> AUDIT_HANDOFF_FIELDS = Set.of(
            "targetOwner", "inputKind", "finalReceiptOwner", "ownerResultIsFinalReceipt",
            "conformanceReceiptSatisfiesProduction");

    @Test
    @Order(1)
    void controlledSchemaAndDatabaseSurfaceFreezeOwnerResultWithoutDeletionReceipt()
            throws Exception {
        JsonNode schema = read(EVENT_SCHEMA);
        JsonNode completed = read(COMPLETED_FIXTURE);
        JsonNode blocked = read(BLOCKED_FIXTURE);
        JsonNode policy = read(RETENTION_POLICY);

        assertAll(
                () -> assertEquals(
                        "quality-snapshot-deletion-result-1.1.0.schema.json",
                        schema.required("$id").asText()),
                () -> assertFalse(schema.required("additionalProperties").asBoolean()),
                () -> assertEquals(ROOT_FIELDS, textSet(schema.required("required"))),
                () -> assertEquals(4, schema.required("properties")
                        .required("data").required("oneOf").size()),
                () -> assertExactEnvelope(completed, null, 2L, "completed", null),
                () -> assertExactEnvelope(blocked, null, 1L, "blocked", "LEGAL_HOLD_MATCHED"),
                () -> assertTrue(policy.at("/ownerResult/wirePrettyJsonAllowed").asBoolean()),
                () -> assertFalse(policy.at("/ownerResult/wireCanonicalByteEqualityRequired")
                        .asBoolean()),
                () -> assertEquals("audit-operations",
                        policy.at("/auditHandoff/targetOwner").asText()),
                () -> assertFalse(policy.at(
                        "/auditHandoff/conformanceReceiptSatisfiesProduction").asBoolean()));

        JdbcTemplate jdbc = admin();
        Set<String> resultColumns = Set.copyOf(jdbc.queryForList("""
                select column_name
                  from information_schema.columns
                 where table_schema='ingestion_quality'
                   and table_name='iq_quality_snapshot_deletion_result'
                """, String.class));
        assertTrue(resultColumns.containsAll(Set.of(
                        "result_event_id", "execution_id", "snapshot_id",
                        "snapshot_immutable_hash", "scope_digest", "result", "blocker_code",
                        "transaction_id", "transaction_evidence_digest", "aggregate_version",
                        "supersedes_result_event_id", "occurred_at", "payload_utf8",
                        "payload_digest")),
                "the durable owner result must retain every replay and handoff binding");

        assertEquals(0, jdbc.queryForObject("""
                select count(*)
                  from information_schema.tables
                 where table_schema='ingestion_quality'
                   and lower(table_name) like '%deletion%receipt%'
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*)
                  from pg_catalog.pg_type type_record
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=type_record.typnamespace
                 where namespace.nspname='ingestion_quality'
                   and lower(type_record.typname) like '%deletion%receipt%'
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*)
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and lower(procedure.proname) like '%deletion%receipt%'
                """, Integer.class));
        try (var productionFiles = Files.walk(REPOSITORY.resolve(
                "backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality"))) {
            assertTrue(productionFiles.noneMatch(path ->
                            path.getFileName().toString().contains("DeletionReceipt")),
                    "audit-operations, not ingestion-quality, owns DeletionReceipt");
        }
    }

    @Test
    @Order(2)
    void ownerMaterializesCanonicalCompletedResultAndReplaysItsPublicIdentity()
            throws Exception {
        ensureRetentionLogin();
        JdbcTemplate jdbc = admin();
        Scenario scenario = scenario(0x100);
        ObjectNode authorityEvent = event(scenario, "completed", databaseNow(jdbc));
        seedAssessedSnapshot(jdbc, scenario);
        seedAuthority(jdbc, scenario, authorityEvent, true);

        assertEquals("completed", executeRetention(scenario));
        assertSnapshotCounts(jdbc, scenario, 0, 0, 0);
        assertAuthorityConsumed(jdbc, scenario);

        Map<String, Object> result = result(jdbc, scenario.eventId());
        Map<String, Object> outbox = outbox(jdbc, scenario.eventId());
        assertEquals(scenario.executionId(), result.get("execution_id"));
        assertEquals(scenario.snapshotId(), result.get("snapshot_id"));
        assertEquals(snapshotHash(scenario), result.get("snapshot_immutable_hash"));
        assertEquals(scopeDigest(authorityEvent), result.get("scope_digest"));
        assertEquals("completed", result.get("result"));
        assertNull(result.get("blocker_code"));
        assertEquals(1L, result.get("aggregate_version"));
        assertNull(result.get("supersedes_result_event_id"));
        UUID transactionId = (UUID) result.get("transaction_id");
        assertNotNull(transactionId);
        assertNotEquals(scenario.executionId(), result.get("transaction_id"));
        assertUuidV7(transactionId);

        assertEquals(scenario.executionId(), outbox.get("aggregate_id"));
        assertEquals(1L, outbox.get("aggregate_version"));
        assertEquals(EVENT_TYPE, outbox.get("event_type"));
        assertEquals(EVENT_SCHEMA_VERSION, outbox.get("schema_version"));
        byte[] storedBytes = (byte[]) result.get("payload_utf8");
        assertArrayEquals(storedBytes, (byte[]) outbox.get("payload_utf8"));
        assertEquals(sha256(storedBytes), trim(result.get("payload_digest")));
        assertEquals(sha256(storedBytes), trim(outbox.get("payload_digest")));

        JsonNode stored = read(storedBytes);
        assertArrayEquals(canonicalBytes(stored), storedBytes,
                "the owner must persist exactly one canonical representation");
        assertExactEnvelope(stored, scenario, 1L, "completed", null);
        assertEquals(RETENTION_TRACE_ID, stored.required("data").required("traceId").asText());
        Instant committedAt = ((Timestamp) result.get("occurred_at")).toInstant();
        assertCompletedDatabaseOutcome(stored, transactionId, committedAt);
        assertEquals(transactionEvidenceDigest(stored),
                result.get("transaction_evidence_digest"));

        int resultCount = resultCount(jdbc, scenario.executionId());
        int outboxCount = outboxCount(jdbc, scenario.executionId());
        assertEquals("completed", executeRetention(scenario));
        assertEquals(resultCount, resultCount(jdbc, scenario.executionId()));
        assertEquals(outboxCount, outboxCount(jdbc, scenario.executionId()));
    }

    @Test
    @Order(3)
    void blockedResultBindsExactLegalHoldEvidenceLineageAndOwnerAuditHandoff()
            throws Exception {
        ensureRetentionLogin();
        JdbcTemplate jdbc = admin();
        Scenario scenario = scenario(0x200);
        Instant observedAt = databaseNow(jdbc);
        ObjectNode event = event(scenario, "blocked", observedAt);
        seedAssessedSnapshot(jdbc, scenario);
        seedAuthority(jdbc, scenario, event, false);

        assertEquals("blocked", executeRetention(scenario));
        assertSnapshotCounts(jdbc, scenario, 1, METRIC_COUNT, 2);
        assertAuthorityConsumed(jdbc, scenario);

        Map<String, Object> result = result(jdbc, scenario.eventId());
        Map<String, Object> outbox = outbox(jdbc, scenario.eventId());
        assertEquals(scenario.executionId(), result.get("execution_id"));
        assertEquals("blocked", result.get("result"));
        assertEquals("LEGAL_HOLD_MATCHED", result.get("blocker_code"));
        assertNull(result.get("transaction_id"));
        assertNull(result.get("transaction_evidence_digest"));
        assertEquals(1L, result.get("aggregate_version"));
        assertNull(result.get("supersedes_result_event_id"));
        assertEquals(scenario.executionId(), outbox.get("aggregate_id"));
        byte[] storedBytes = (byte[]) result.get("payload_utf8");
        assertArrayEquals(storedBytes, (byte[]) outbox.get("payload_utf8"));
        assertArrayEquals(canonicalBytes(read(storedBytes)), storedBytes);
        assertEquals(sha256(storedBytes), trim(result.get("payload_digest")));
        assertEquals(sha256(storedBytes), trim(outbox.get("payload_digest")));

        JsonNode stored = read(storedBytes);
        assertExactEnvelope(stored, scenario, 1L, "blocked", "LEGAL_HOLD_MATCHED");
        JsonNode data = stored.required("data");
        assertEquals(List.of("LEGAL_HOLD_MATCHED"), strings(data.required("blockerCodes")));
        assertEquals(List.of(), strings(data.required("failureCodes")));
        assertTrue(data.required("deletionCommittedAt").isNull());
        for (String targetName : OWNER_RESULT_FIELDS) {
            JsonNode target = data.required("ownerLocalResults").required(targetName);
            assertEquals("not-attempted", target.required("status").asText());
            assertEquals(0L, target.required("selectedCount").asLong());
            assertEquals(0L, target.required("deletedCount").asLong());
            assertEquals(0L, target.required("remainingCount").asLong());
            assertTrue(target.required("transactionId").isNull());
            assertTrue(target.required("transactionEvidenceDigest").isNull());
        }
        assertOwnerAuditHandoff(data.required("auditHandoff"));
    }

    @Test
    @Order(4)
    void executorSignatureHasNoCallerControlledResultPayload() {
        JdbcTemplate jdbc = admin();
        Map<String, Object> function = jdbc.queryForMap("""
                select pg_catalog.oidvectortypes(procedure.proargtypes) as argument_types,
                       pg_catalog.array_to_string(procedure.proargnames, ',') as argument_names,
                       lower(pg_catalog.pg_get_functiondef(procedure.oid)) as definition
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname='iq_execute_quality_snapshot_retention'
                """);
        assertEquals("uuid, uuid, uuid, character, character, uuid, character",
                function.get("argument_types"));
        assertEquals("requested_execution_id,requested_result_event_id,requested_snapshot_id,"
                        + "requested_snapshot_immutable_hash,requested_scope_digest,"
                        + "requested_authority_evidence_id,requested_trace_id",
                function.get("argument_names"));
        String definition = String.valueOf(function.get("definition"));
        assertFalse(definition.contains("requested_payload"));
        assertFalse(definition.contains("requested_transaction"));
    }

    private static void assertCompletedDatabaseOutcome(
            JsonNode event, UUID transactionId, Instant committedAt) {
        JsonNode data = event.required("data");
        JsonNode snapshot = data.required("ownerLocalResults").required("onlineSnapshot");
        JsonNode metrics = data.required("ownerLocalResults").required("onlineMetrics");
        JsonNode readModels = data.required("ownerLocalResults").required("readModels");
        assertEquals("deleted", snapshot.required("status").asText());
        assertEquals(1L, snapshot.required("selectedCount").asLong());
        assertEquals(1L, snapshot.required("deletedCount").asLong());
        assertEquals(0L, snapshot.required("remainingCount").asLong());
        assertEquals("deleted", metrics.required("status").asText());
        assertEquals(METRIC_COUNT, metrics.required("selectedCount").asInt());
        assertEquals(METRIC_COUNT, metrics.required("deletedCount").asInt());
        assertEquals(0L, metrics.required("remainingCount").asLong());
        assertEquals("deleted", readModels.required("status").asText());
        assertEquals(2, readModels.required("selectedCount").asInt());
        assertEquals(2, readModels.required("deletedCount").asInt());
        assertEquals(0L, readModels.required("remainingCount").asLong());
        assertEquals(transactionId.toString(), snapshot.required("transactionId").asText());
        assertEquals(snapshot.required("transactionId"), metrics.required("transactionId"));
        assertEquals(snapshot.required("transactionId"), readModels.required("transactionId"));
        assertEquals(transactionEvidenceDigest(event),
                snapshot.required("transactionEvidenceDigest").asText());
        assertEquals(snapshot.required("transactionEvidenceDigest"),
                metrics.required("transactionEvidenceDigest"));
        assertEquals(snapshot.required("transactionEvidenceDigest"),
                readModels.required("transactionEvidenceDigest"));
        assertEquals(instant(committedAt), data.required("deletionCommittedAt").asText());
        assertOwnerAuditHandoff(data.required("auditHandoff"));
    }

    private static void assertExactEnvelope(
            JsonNode event,
            Scenario scenario,
            long expectedVersion,
            String expectedResult,
            String expectedBlocker) {
        assertEquals(ROOT_FIELDS, Set.copyOf(event.propertyNames()));
        JsonNode data = event.required("data");
        assertEquals(DATA_FIELDS, Set.copyOf(data.propertyNames()));
        assertEquals(SCOPE_FIELDS, Set.copyOf(data.required("scope").propertyNames()));
        assertEquals(GUARD_FIELDS, Set.copyOf(data.required("guards").propertyNames()));
        assertEquals(OWNER_RESULT_FIELDS,
                Set.copyOf(data.required("ownerLocalResults").propertyNames()));
        for (String targetName : OWNER_RESULT_FIELDS) {
            assertEquals(TARGET_FIELDS, Set.copyOf(data.required("ownerLocalResults")
                    .required(targetName).propertyNames()));
        }
        assertEquals(AUDIT_HANDOFF_FIELDS,
                Set.copyOf(data.required("auditHandoff").propertyNames()));

        assertEquals("1.0", event.required("specversion").asText());
        assertEquals("urn:scholarsense:ingestion-quality", event.required("source").asText());
        assertEquals(EVENT_TYPE, event.required("type").asText());
        assertEquals("application/json", event.required("datacontenttype").asText());
        assertEquals(EVENT_SCHEMA_VERSION, data.required("resultContractVersion").asText());
        assertEquals("PIC-1.0.0", data.required("contractVersion").asText());
        assertEquals("quality-snapshot-deletion-execution",
                data.required("aggregateType").asText());
        assertEquals("ingestion-quality-retention-executor", data.required("producer").asText());
        assertEquals("QUALITY-SNAPSHOT-RETENTION-1.0.0",
                data.required("retentionPolicyVersion").asText());
        assertEquals("RS-1.0.0", data.required("retentionScheduleVersion").asText());
        assertEquals("SCHOLARSENSE-CANONICAL-JSON-1.0.0",
                data.required("canonicalizationProfile").asText());
        assertEquals("none", data.required("runtimeEvidenceClaim").asText());
        assertEquals(event.required("id"), data.required("eventId"));
        assertEquals(event.required("time"), data.required("occurredAt"));
        assertEquals(data.required("executionId"), data.required("aggregateId"));
        assertEquals(expectedVersion, data.required("aggregateVersion").asLong());
        assertEquals(expectedResult, data.required("result").asText());
        assertOwnerAuditHandoff(data.required("auditHandoff"));

        if (scenario != null) {
            assertEquals(scenario.eventId().toString(), event.required("id").asText());
            assertEquals(scenario.executionId().toString(), data.required("executionId").asText());
            assertEquals(scenario.snapshotId().toString(),
                    data.required("scope").required("snapshotId").asText());
            assertEquals(snapshotHash(scenario),
                    data.required("scope").required("snapshotImmutableHash").asText());
            assertEquals(scopeDigest(event),
                    data.required("scope").required("scopeDigest").asText());
            assertEquals("quality-snapshot/" + scenario.snapshotId(),
                    event.required("subject").asText());
        }
        if (expectedBlocker == null) {
            assertEquals(List.of(), strings(data.required("blockerCodes")));
        } else {
            assertEquals(List.of(expectedBlocker), strings(data.required("blockerCodes")));
        }
    }

    private static void assertOwnerAuditHandoff(JsonNode handoff) {
        assertEquals("audit-operations", handoff.required("targetOwner").asText());
        assertEquals("owner-local-deletion-result", handoff.required("inputKind").asText());
        assertEquals("audit-operations", handoff.required("finalReceiptOwner").asText());
        assertFalse(handoff.required("ownerResultIsFinalReceipt").asBoolean());
        assertFalse(handoff.required("conformanceReceiptSatisfiesProduction").asBoolean());
    }

    private static ObjectNode event(Scenario scenario, String result, Instant occurredAt)
            throws Exception {
        boolean completed = result.equals("completed");
        ObjectNode event = ((ObjectNode) read(completed ? COMPLETED_FIXTURE : BLOCKED_FIXTURE))
                .deepCopy();
        ObjectNode data = data(event);
        ObjectNode scope = scope(event);
        ObjectNode guards = (ObjectNode) data.required("guards");
        ObjectNode legalHold = (ObjectNode) guards.required("legalHold");
        ObjectNode registry = (ObjectNode) guards.required("consumerRegistry");
        ObjectNode registryAuthority = (ObjectNode) registry.required("authorityEvidence");
        ObjectNode consumer = (ObjectNode) guards.required("consumerWatermarks").required(0);
        ObjectNode attestation = (ObjectNode) consumer.required("attestation");

        String eventId = scenario.eventId().toString();
        String executionId = scenario.executionId().toString();
        String snapshotId = scenario.snapshotId().toString();
        String timestamp = instant(occurredAt);
        String checkedAt = instant(occurredAt.minusSeconds(2));
        String attestedAt = instant(occurredAt.minusSeconds(3));
        event.put("id", eventId);
        event.put("subject", "quality-snapshot/" + snapshotId);
        event.put("time", timestamp);
        event.put("traceparent", "00-" + TRACE_ID + "-bbbbbbbbbbbbbbbb-01");
        data.put("correlationId", executionId);
        data.put("causationId", scenario.causationId().toString());
        data.put("eventId", eventId);
        data.put("aggregateId", executionId);
        data.put("aggregateVersion", 1);
        data.put("executionId", executionId);
        data.putNull("supersedesResultId");
        data.put("occurredAt", timestamp);
        data.put("traceId", TRACE_ID);
        data.put("result", result);

        scope.put("snapshotId", snapshotId);
        scope.put("sourceId", SOURCE_ID);
        scope.put("snapshotAggregateVersion", 3);
        scope.put("evaluatedAt", instant(EVALUATED_AT));
        scope.put("retentionDueAt", instant(RETENTION_DUE_AT));
        scope.put("snapshotImmutableHash", snapshotHash(scenario));
        scope.put("scopeDigest", scopeDigest(event));
        String scopeDigest = scope.required("scopeDigest").asText();

        ((ObjectNode) guards.required("trustedTime"))
                .put("status", "available")
                .put("observedAt", timestamp);
        legalHold.put("status", completed ? "clear" : "matched");
        legalHold.put("checkedScopeDigest", scopeDigest);
        legalHold.put("matchedCount", completed ? 0 : 1);
        ArrayNode matchedScopes = (ArrayNode) legalHold.required("matchedScopeDigests");
        matchedScopes.removeAll();
        if (!completed) matchedScopes.add(scopeDigest);
        legalHold.put("checkedAt", checkedAt);
        registry.put("checkedAt", checkedAt);
        registryAuthority.put("scopeDigest", scopeDigest);
        registryAuthority.put("checkedAt", checkedAt);
        guards.put("consumerWatermarksCheckedAt", checkedAt);
        consumer.put("requiredAggregateVersion", 3);
        consumer.put("confirmedAggregateVersion", 3);
        attestation.put("snapshotId", snapshotId);
        attestation.put("snapshotImmutableHash", snapshotHash(scenario));
        attestation.put("requiredAggregateVersion", 3);
        attestation.put("scopeDigest", scopeDigest);
        attestation.put("attestedAt", attestedAt);

        ArrayNode blockers = (ArrayNode) data.required("blockerCodes");
        blockers.removeAll();
        if (!completed) blockers.add("LEGAL_HOLD_MATCHED");
        ((ArrayNode) data.required("failureCodes")).removeAll();
        if (completed) {
            data.put("deletionCommittedAt", timestamp);
            ((ObjectNode) data.required("backup"))
                    .put("backupExpiryDueAt", instant(occurredAt.plus(11, ChronoUnit.DAYS)));
            configureCompletedOwnerResults(data, scenario);
            refreshTransactionEvidenceDigest(event);
        } else {
            data.putNull("deletionCommittedAt");
            ((ObjectNode) data.required("backup")).putNull("backupExpiryDueAt");
            configureBlockedOwnerResults(data);
        }
        return event;
    }

    private static void configureCompletedOwnerResults(ObjectNode data, Scenario scenario) {
        ObjectNode snapshot = targetData(data, "onlineSnapshot");
        completedTarget(snapshot, 1, scenario, digest("snapshot-delete:" + scenario.snapshotId()));
        ObjectNode metrics = targetData(data, "onlineMetrics");
        completedTarget(metrics, METRIC_COUNT, scenario,
                digest("metric-delete:" + scenario.snapshotId() + ":" + METRIC_COUNT));
        for (String name : List.of("readModels", "indexes", "caches", "objects")) {
            ObjectNode target = targetData(data, name);
            target.put("status", "not-applicable");
            target.put("selectedCount", 0);
            target.put("deletedCount", 0);
            target.put("remainingCount", 0);
            target.putNull("evidenceDigest");
            target.putNull("errorCode");
            target.putNull("transactionId");
            target.putNull("transactionEvidenceDigest");
        }
    }

    private static void completedTarget(
            ObjectNode target, int count, Scenario scenario, String evidenceDigest) {
        target.put("status", "deleted");
        target.put("selectedCount", count);
        target.put("deletedCount", count);
        target.put("remainingCount", 0);
        target.put("evidenceDigest", evidenceDigest);
        target.putNull("errorCode");
        target.put("transactionId", scenario.transactionId().toString());
        target.putNull("transactionEvidenceDigest");
    }

    private static void configureBlockedOwnerResults(ObjectNode data) {
        for (String name : OWNER_RESULT_FIELDS) {
            ObjectNode target = targetData(data, name);
            target.put("status", "not-attempted");
            target.put("selectedCount", 0);
            target.put("deletedCount", 0);
            target.put("remainingCount", 0);
            target.putNull("evidenceDigest");
            target.putNull("errorCode");
            target.putNull("transactionId");
            target.putNull("transactionEvidenceDigest");
        }
    }

    private static void refreshTransactionEvidenceDigest(ObjectNode event) {
        String digest = transactionEvidenceDigest(event);
        target(event, "onlineSnapshot").put("transactionEvidenceDigest", digest);
        target(event, "onlineMetrics").put("transactionEvidenceDigest", digest);
        target(event, "readModels").put("transactionEvidenceDigest", digest);
    }

    private static String transactionEvidenceDigest(JsonNode event) {
        JsonNode data = event.required("data");
        ObjectNode material = JSON.createObjectNode();
        material.set("executionId", data.required("executionId").deepCopy());
        material.set("scopeDigest", data.required("scope").required("scopeDigest").deepCopy());
        material.set("result", data.required("result").deepCopy());
        material.set("deletionCommittedAt", data.required("deletionCommittedAt").deepCopy());
        material.set("transactionId", data.required("ownerLocalResults")
                .required("onlineSnapshot").required("transactionId").deepCopy());
        material.set("onlineSnapshot", transactionTargetMaterial(
                data.required("ownerLocalResults").required("onlineSnapshot")));
        material.set("onlineMetrics", transactionTargetMaterial(
                data.required("ownerLocalResults").required("onlineMetrics")));
        material.set("readModels", transactionTargetMaterial(
                data.required("ownerLocalResults").required("readModels")));
        return "sha256:" + sha256(canonicalBytes(material));
    }

    private static ObjectNode transactionTargetMaterial(JsonNode target) {
        ObjectNode material = JSON.createObjectNode();
        for (String field : List.of(
                "status", "selectedCount", "deletedCount", "remainingCount",
                "evidenceDigest", "errorCode")) {
            material.set(field, target.required(field).deepCopy());
        }
        return material;
    }

    private static String scopeDigest(JsonNode event) {
        JsonNode data = event.required("data");
        JsonNode scope = data.required("scope");
        ObjectNode material = JSON.createObjectNode();
        for (String field : List.of(
                "objectType", "snapshotId", "sourceId", "snapshotAggregateVersion",
                "evaluatedAt", "retentionDueAt", "snapshotImmutableHash")) {
            material.set(field, scope.required(field).deepCopy());
        }
        material.set("retentionPolicyVersion", data.required("retentionPolicyVersion").deepCopy());
        material.set("retentionScheduleVersion",
                data.required("retentionScheduleVersion").deepCopy());
        return "sha256:" + sha256(canonicalBytes(material));
    }

    private static void seedAssessedSnapshot(JdbcTemplate jdbc, Scenario scenario) {
        byte[] businessKey = ("retention-event\0" + scenario.batchId())
                .getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                insert into ingestion_quality.iq_data_batch
                  (batch_id, source_id, business_key_utf8, business_key_digest,
                   source_version, lineage_id, effective_at, declared_manifest_digest,
                   status, aggregate_version, received_at, trace_id)
                values (?, ?, ?, encode(sha256(?), 'hex'), 1, ?, ?, ?,
                        'receiving', 1, ?, ?)
                """,
                scenario.batchId(), SOURCE_ID, businessKey, businessKey, scenario.lineageId(),
                Timestamp.from(RECEIVED_AT.minusSeconds(60)), digest("manifest"),
                Timestamp.from(RECEIVED_AT), TRACE_ID);
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_batch
                   set status='sealed', aggregate_version=2,
                       record_count=0, valid_record_count=0, rejected_record_count=0,
                       observation_start_at=?, observation_end_at=?, cutoff_at=?,
                       business_timezone='Asia/Shanghai', watermark_utf8=?,
                       source_schema_version='CARD-1.0.0', source_schema_digest=?,
                       data_catalog_version='CATALOG-1.0.0', data_catalog_digest=?,
                       quality_gate_version='QG-1.0.0', quality_gate_digest=?,
                       qmdp_version='QMDP-1.0.0', qmdp_digest=?,
                       source_occurred_at=?, scheduled_due_at=?,
                       lane_id='retention-event-fixture',
                       sealed_contract_evidence='{"fixture":"retention-event"}'::jsonb,
                       sealed_at=?
                 where batch_id=?
                """,
                Timestamp.from(RECEIVED_AT.minusSeconds(30)),
                Timestamp.from(RECEIVED_AT.minusSeconds(10)),
                Timestamp.from(RECEIVED_AT.minusSeconds(10)),
                ("wm\0" + scenario.batchId()).getBytes(StandardCharsets.UTF_8),
                digest("source-schema"), digest("catalog"), digest("gate"), digest("qmdp"),
                Timestamp.from(RECEIVED_AT.minusSeconds(20)),
                Timestamp.from(RECEIVED_AT.plusSeconds(300)),
                Timestamp.from(SEALED_AT), scenario.batchId()));
        jdbc.update("""
                insert into ingestion_quality.iq_quality_snapshot
                  (snapshot_id, batch_id, domain_tag, hash_profile_version,
                   hash_profile_digest, source_id, assessed_batch_status, overall_result,
                   observation_start_at, observation_end_at, cutoff_at, watermark_utf8,
                   source_owner_ref, approval_ref, effective_at,
                   retention_schedule_version, qmdp_version, qmdp_digest,
                   quality_gate_version, quality_gate_digest, canonicalization_profile,
                   manifest_digest, source_schema_version, source_schema_digest,
                   lineage_id, evaluated_at, trace_id, aggregate_version,
                   immutable_hash, retention_due_at, legal_hold, retention_scope_digest)
                select ?, batch_id,
                       'scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1',
                       'QSHM-1.0.0', ?, source_id, 'quality-passed', 'quality-passed',
                       observation_start_at, observation_end_at, cutoff_at, watermark_utf8,
                       'urn:scholarsense:owner:registrar', 'AUTH-2026-08-08-001', ?,
                       'RS-1.0.0', qmdp_version, qmdp_digest,
                       quality_gate_version, quality_gate_digest,
                       'SCHOLARSENSE-CANONICAL-JSON-1.0.0', declared_manifest_digest,
                       source_schema_version, source_schema_digest, lineage_id, ?, trace_id, 3,
                       ?, ?, false, ?
                  from ingestion_quality.iq_data_batch
                 where batch_id=?
                """,
                scenario.snapshotId(), digest("hash-profile"),
                Timestamp.from(RECEIVED_AT.minusSeconds(60)), Timestamp.from(EVALUATED_AT),
                snapshotHash(scenario), Timestamp.from(RETENTION_DUE_AT),
                scopeDigestUnchecked(scenario), scenario.batchId());
        for (int ordinal = 0; ordinal < METRIC_COUNT; ordinal++) {
            jdbc.update("""
                    insert into ingestion_quality.iq_quality_snapshot_metric
                      (snapshot_id, metric_ordinal, metric_id, formula_id, formula_version,
                       result, applicable, numerator, denominator, value_basis_points,
                       unit, operator, threshold_numerator, threshold_denominator,
                       boundary, reason_code)
                    values (?, ?, ?, ?, '1.0.0', 'passed', true, 1, 1, 10000,
                            'basis-point', '>=', 1, 1, 'inclusive', null)
                    """, scenario.snapshotId(), ordinal, "RETENTION-METRIC-" + ordinal,
                    "QMDP-1.0.0/RETENTION-EVENT/" + ordinal);
        }
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            jdbc.update("""
                    insert into ingestion_quality.iq_quality_snapshot_impact_scope
                      (snapshot_id, scope_ordinal, scope_code_utf8)
                    values (?, ?, ?)
                    """, scenario.snapshotId(), ordinal,
                    ("RETENTION_SCOPE_" + ordinal).getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(1, jdbc.update("""
                update ingestion_quality.iq_data_batch
                   set status='quality-passed', aggregate_version=3, evaluated_at=?
                 where batch_id=?
                """, Timestamp.from(EVALUATED_AT), scenario.batchId()));
    }

    private static void seedAuthority(
            JdbcTemplate jdbc, Scenario scenario, JsonNode event, boolean legalHoldClear) {
        JsonNode data = event.required("data");
        JsonNode guards = data.required("guards");
        JsonNode registry = guards.required("consumerRegistry");
        JsonNode authority = registry.required("authorityEvidence");
        ObjectNode attestations = JSON.createObjectNode();
        attestations.set("consumers", guards.required("consumerWatermarks").deepCopy());
        byte[] attestationBytes = canonicalBytes(attestations);
        byte[] memberBytes = canonicalBytes(guards.required("consumerWatermarks"));
        Instant observedAt = Instant.parse(
                guards.required("trustedTime").required("observedAt").asText());
        Instant checkedAt = Instant.parse(
                guards.required("legalHold").required("checkedAt").asText());
        jdbc.update("""
                insert into ingestion_quality
                  .iq_quality_snapshot_retention_authority_evidence
                  (authority_evidence_id, authority_ref, snapshot_id,
                   snapshot_immutable_hash, scope_digest, registry_version,
                   registry_digest, members_digest, legal_hold_clear,
                   legal_hold_checked_scope_digest, legal_hold_checked_at,
                   consumer_attestations_payload_utf8, consumer_attestations_digest,
                   watermarks_checked_at, evidence_copy_status, evidence_copy_digest,
                   trusted_observed_at, issued_at, expires_at, consumed_at, execution_id,
                   runtime_evidence_claim, verification_status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'copied', ?, ?, ?, ?,
                        null, null,
                        'production-verified', 'verified')
                """,
                scenario.authorityEvidenceId(),
                "consumer-registry-authority://production/quality-snapshot/"
                        + scenario.authorityEvidenceId(),
                scenario.snapshotId(), snapshotHash(scenario),
                scopeDigest(event), registry.required("registryVersion").asText(),
                registry.required("registryDigest").asText(),
                authority.required("membersDigest").asText(), legalHoldClear,
                scopeDigest(event), Timestamp.from(checkedAt), attestationBytes,
                sha256(attestationBytes), Timestamp.from(Instant.parse(
                        guards.required("consumerWatermarksCheckedAt").asText())),
                "sha256:" + sha256(memberBytes), Timestamp.from(observedAt),
                Timestamp.from(observedAt.minusSeconds(60)),
                Timestamp.from(observedAt.plusSeconds(3600)));
    }

    private static String executeRetention(Scenario scenario) {
        return retention().queryForObject("""
                select ingestion_quality.iq_execute_quality_snapshot_retention(
                  ?::uuid, ?::uuid, ?::uuid, ?::char(71), ?::char(71), ?::uuid,
                  ?::char(32))
                """, String.class,
                scenario.executionId(), scenario.eventId(), scenario.snapshotId(),
                snapshotHash(scenario), scopeDigestUnchecked(scenario),
                scenario.authorityEvidenceId(), RETENTION_TRACE_ID);
    }

    private static Map<String, Object> result(JdbcTemplate jdbc, UUID eventId) {
        return jdbc.queryForMap("""
                select result_event_id, execution_id, snapshot_id, snapshot_immutable_hash,
                       scope_digest, result, blocker_code, transaction_id,
                       transaction_evidence_digest, aggregate_version,
                       supersedes_result_event_id, occurred_at, payload_utf8, payload_digest
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, eventId);
    }

    private static Map<String, Object> outbox(JdbcTemplate jdbc, UUID eventId) {
        return jdbc.queryForMap("""
                select event_id, aggregate_id, aggregate_version, event_type, schema_version,
                       payload_utf8, payload_digest
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, eventId);
    }

    private static int resultCount(JdbcTemplate jdbc, UUID executionId) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where execution_id=?
                """, Integer.class, executionId);
    }

    private static int outboxCount(JdbcTemplate jdbc, UUID executionId) {
        return jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_batch_quality_outbox
                 where aggregate_id=? and event_type=?
                """, Integer.class, executionId, EVENT_TYPE);
    }

    private static void assertSnapshotCounts(
            JdbcTemplate jdbc,
            Scenario scenario,
            int expectedSnapshots,
            int expectedMetrics,
            int expectedImpacts) {
        assertEquals(expectedSnapshots, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_quality_snapshot
                 where snapshot_id=?
                """, Integer.class, scenario.snapshotId()));
        assertEquals(expectedMetrics, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_quality_snapshot_metric
                 where snapshot_id=?
                """, Integer.class, scenario.snapshotId()));
        assertEquals(expectedImpacts, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_quality_snapshot_impact_scope
                 where snapshot_id=?
                """, Integer.class, scenario.snapshotId()));
    }

    private static void assertAuthorityConsumed(JdbcTemplate jdbc, Scenario scenario) {
        Map<String, Object> authority = jdbc.queryForMap("""
                select consumed_at, execution_id
                  from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
                 where authority_evidence_id=?
                """, scenario.authorityEvidenceId());
        assertNotNull(authority.get("consumed_at"));
        assertEquals(scenario.executionId(), authority.get("execution_id"));
    }

    private static void ensureRetentionLogin() {
        admin().execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname=
                                      'scholarsense_iq_ret_event_executor_test_login') then
                        create role scholarsense_iq_ret_event_executor_test_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_ret_event_executor_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_batch_owner
                    from scholarsense_iq_ret_event_executor_test_login;
                grant scholarsense_ingestion_quality_retention_executor
                    to scholarsense_iq_ret_event_executor_test_login
                    with inherit true, set false;
                """);
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(dataSource(required("scholarsense.audit.pg.user")));
    }

    private static JdbcTemplate retention() {
        return new JdbcTemplate(dataSource(RETENTION_LOGIN));
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
        return jdbc.queryForObject(
                "select date_trunc('milliseconds', statement_timestamp())",
                Timestamp.class).toInstant();
    }

    private static ObjectNode data(ObjectNode event) {
        return (ObjectNode) event.required("data");
    }

    private static ObjectNode scope(ObjectNode event) {
        return (ObjectNode) data(event).required("scope");
    }

    private static ObjectNode target(ObjectNode event, String name) {
        return targetData(data(event), name);
    }

    private static ObjectNode targetData(ObjectNode data, String name) {
        return (ObjectNode) data.required("ownerLocalResults").required(name);
    }

    private static Scenario scenario(int base) {
        UUID snapshotId = uuid(base + 3);
        return new Scenario(
                uuid(base + 1), uuid(base + 2), snapshotId, snapshotId,
                uuid(base + 0x11), uuid(base + 0x12), uuid(base + 0x13),
                uuid(base + 0x14));
    }

    private static UUID uuid(int value) {
        return UUID.fromString("019fe570-0000-7000-8000-" + "%012x".formatted(value));
    }

    private static void assertUuidV7(UUID value) {
        assertEquals(7, value.version());
        assertEquals(2, value.variant());
    }

    private static String snapshotHash(Scenario scenario) {
        return digest("snapshot:" + scenario.snapshotId());
    }

    /** The same exact material used by {@link #event}; useful before that event is built. */
    private static String scopeDigestUnchecked(Scenario scenario) {
        ObjectNode event = JSON.createObjectNode();
        ObjectNode data = event.putObject("data");
        data.put("retentionPolicyVersion", "QUALITY-SNAPSHOT-RETENTION-1.0.0");
        data.put("retentionScheduleVersion", "RS-1.0.0");
        ObjectNode scope = data.putObject("scope");
        scope.put("objectType", "QualitySnapshot");
        scope.put("snapshotId", scenario.snapshotId().toString());
        scope.put("sourceId", SOURCE_ID);
        scope.put("snapshotAggregateVersion", 3);
        scope.put("evaluatedAt", instant(EVALUATED_AT));
        scope.put("retentionDueAt", instant(RETENTION_DUE_AT));
        scope.put("snapshotImmutableHash", snapshotHash(scenario));
        return scopeDigest(event);
    }

    private static String digest(String material) {
        return "sha256:" + sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] canonicalBytes(JsonNode value) {
        try {
            return JSON.writeValueAsBytes(canonicalValue(value));
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("invalid canonical event fixture", failure);
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
        throw new IllegalArgumentException("canonical event accepts integer JSON only");
    }

    private static JsonNode read(Path path) throws Exception {
        return JSON.readTree(Files.readAllBytes(path));
    }

    private static JsonNode read(byte[] value) throws Exception {
        return JSON.readTree(value);
    }

    private static Set<String> textSet(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false)
                .map(JsonNode::asText)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<String> strings(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private static String trim(Object value) {
        assertNotNull(value);
        return value.toString().trim();
    }

    private static String instant(Instant value) {
        return value.toString();
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " required");
        }
        return value;
    }

    private record Scenario(
            UUID batchId,
            UUID lineageId,
            UUID snapshotId,
            UUID executionId,
            UUID eventId,
            UUID authorityEvidenceId,
            UUID transactionId,
            UUID causationId) {}

}
