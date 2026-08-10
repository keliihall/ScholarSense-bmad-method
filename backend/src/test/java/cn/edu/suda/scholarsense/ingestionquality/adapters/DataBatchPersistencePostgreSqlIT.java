package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionScopeCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** PostgreSQL 18.4 persistence, fencing, privilege, and retention evidence for Story 2.3. */
class DataBatchPersistencePostgreSqlIT {
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSION = Pattern.compile("^V(\\d{6})__.+\\.sql$");
    private static final String FEATURE_SUFFIX =
            "__ingestion-quality__data_batch_quality_snapshot_v1.sql";

    private static final List<String> TABLES = List.of(
            "iq_data_batch",
            "iq_normalized_fact",
            "iq_batch_quality_measurement",
            "iq_batch_quality_operand",
            "iq_batch_quality_impact_scope",
            "iq_quality_snapshot",
            "iq_quality_snapshot_audit_token_binding",
            "iq_quality_snapshot_metric",
            "iq_quality_snapshot_impact_scope",
            "iq_batch_idempotency",
            "iq_frozen_qmdp_policy",
            "iq_batch_quality_outbox",
            "iq_quality_snapshot_retention_authority_evidence",
            "iq_quality_snapshot_deletion_result");

    private static final List<String> FUNCTIONS = List.of(
            "iq_receive_data_batch",
            "iq_inspect_batch_command_precedence",
            "iq_append_normalized_fact",
            "iq_record_batch_quality_measurement",
            "iq_record_batch_quality_impact_scope",
            "iq_seal_data_batch",
            "iq_validate_batch_audit_payload",
            "iq_qmdp_source",
            "iq_qmdp_ordered_definitions",
            "iq_require_production_watermark",
            "iq_require_production_impact_scope",
            "iq_qmdp_expected_metrics",
            "iq_qshm_expected_hash",
            "iq_validate_batch_business_payload",
            "iq_validate_batch_published_payload",
            "iq_commit_batch_quality_evaluation",
            "iq_publish_data_batch",
            "iq_claim_next_batch_quality_outbox",
            "iq_release_batch_quality_outbox",
            "iq_deliver_batch_quality_outbox",
            "iq_fail_batch_quality_outbox",
            "iq_ingest_quality_snapshot_retention_authority",
            "iq_find_next_due_quality_snapshot_retention",
            "iq_execute_quality_snapshot_retention",
            "iq_find_assessed_quality_snapshot_ids",
            "iq_find_assessed_quality_snapshot",
            "iq_find_assessed_quality_snapshot_metrics",
            "iq_find_assessed_quality_snapshot_impact_scopes",
            "iq_find_assessed_quality_snapshot_page",
            "iq_find_assessed_quality_snapshot_page_metrics",
            "iq_find_assessed_quality_snapshot_page_impact_scopes",
            "iq_resolve_quality_snapshot_source",
            "iq_append_quality_snapshot_read_audit");
    private static final Set<String> WORKER_FUNCTIONS = Set.of(
            "iq_receive_data_batch",
            "iq_inspect_batch_command_precedence",
            "iq_append_normalized_fact",
            "iq_record_batch_quality_measurement",
            "iq_record_batch_quality_impact_scope",
            "iq_seal_data_batch",
            "iq_commit_batch_quality_evaluation",
            "iq_publish_data_batch");

    private static final String BATCH_OWNER =
            "scholarsense_ingestion_quality_batch_owner";
    private static final String QUALITY_WORKER =
            "scholarsense_ingestion_quality_quality_worker";
    private static final String RELAY = "scholarsense_ingestion_quality_relay";
    private static final String RETENTION =
            "scholarsense_ingestion_quality_retention_executor";
    private static final String ONLINE =
            "scholarsense_ingestion_quality_online";
    private static final String AUTHORITY =
            "scholarsense_ingestion_quality_consumer_registry_authority";

    private static final String WORKER_LOGIN =
            "scholarsense_iq_batch_worker_test_login";
    private static final String RELAY_LOGIN =
            "scholarsense_iq_batch_relay_test_login";
    private static final String RETENTION_LOGIN =
            "scholarsense_iq_batch_retention_test_login";
    private static final String ONLINE_LOGIN =
            "scholarsense_iq_batch_online_test_login";

    private static final UUID BATCH_ID = uuid("019fe510-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019fe510-0000-7000-8000-000000000002");
    private static final UUID SNAPSHOT_ID = uuid("019fe510-0000-7000-8000-000000000003");
    private static final String SOURCE_ID = "SRC-P0-CARD-001";
    private static final byte[] BUSINESS_KEY = "batch\0业务".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECORD_ID = "record\0一".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WATERMARK =
            "src-p0-card-001@2026-08-10".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IMPACT_SCOPE =
            "PRIMARY_KEY_COMPLETENESS_BP".getBytes(StandardCharsets.UTF_8);
    private static final int EXPECTED_MEASUREMENT_COUNT = 14;
    private static final String RECEIVE_TRACE = "0123456789abcdef0123456789abcdef";
    private static final String SEAL_TRACE = "11111111111111111111111111111111";
    private static final String EVALUATE_TRACE = "00000000000000001122334455667788";
    private static final String PUBLISH_TRACE = "00000000000000008877665544332211";
    private static final String RETENTION_TRACE = "44444444444444444444444444444444";
    private static final String MANIFEST_DIGEST = digest('a');
    private static final String SCHEMA_VERSION =
            DataBatchPostgreSqlEvidenceFixtures.CARD.schemaBinding().version();
    private static final String SCHEMA_DIGEST =
            DataBatchPostgreSqlEvidenceFixtures.CARD.schemaBinding().canonicalDigest();
    private static final String CATALOG_VERSION = DataBatchPostgreSqlEvidenceFixtures.CONTRACT
            .policy().controlledInputs().dataCatalog().version();
    private static final String CATALOG_DIGEST = DataBatchPostgreSqlEvidenceFixtures.CONTRACT
            .policy().controlledInputs().dataCatalog().canonicalDigest();
    private static final String GATE_VERSION = DataBatchPostgreSqlEvidenceFixtures.CONTRACT
            .policy().controlledInputs().qualityGate().version();
    private static final String GATE_DIGEST = DataBatchPostgreSqlEvidenceFixtures.CONTRACT
            .policy().controlledInputs().qualityGate().canonicalDigest();
    private static final String QMDP_DIGEST = DataBatchPostgreSqlEvidenceFixtures.CONTRACT
            .attestation().qmdpPolicyCanonicalDigest();
    private static final byte[] BUSINESS_PAYLOAD = "{}".getBytes(StandardCharsets.UTF_8);
    private static final String PAYLOAD_DIGEST = sha256(BUSINESS_PAYLOAD);
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-10T01:00:00Z");
    private static final Instant CUTOFF_AT = RECEIVED_AT.minusSeconds(10);
    private static final Instant OBSERVATION_START_AT =
            CUTOFF_AT.minusSeconds(720L * 3_600L);
    private static final Instant SEALED_AT = Instant.parse("2026-08-10T01:01:00Z");
    private static final Instant EVALUATED_AT = Instant.parse("2026-08-10T01:02:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-10T01:03:00Z");
    private static final Instant RETENTION_DUE_AT = Instant.parse("2028-08-10T01:02:00Z");
    private static final cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot
            QUALITY_SNAPSHOT = DataBatchPostgreSqlEvidenceFixtures.qualitySnapshot(
                    BATCH_ID, LINEAGE_ID, SNAPSHOT_ID,
                    OBSERVATION_START_AT, CUTOFF_AT, CUTOFF_AT,
                    WATERMARK, List.of(IMPACT_SCOPE),
                    MANIFEST_DIGEST, EVALUATED_AT, EVALUATE_TRACE);
    private static final String IMMUTABLE_HASH = QUALITY_SNAPSHOT.immutableHash();
    private static final String RETENTION_SCOPE =
            QualitySnapshotRetentionScopeCanonicalizer.digest(
                    new QualitySnapshotRetentionScopeCanonicalizer.Material(
                            "QualitySnapshot", SNAPSHOT_ID, SOURCE_ID, 3L,
                            EVALUATED_AT, RETENTION_DUE_AT, IMMUTABLE_HASH,
                            "QUALITY-SNAPSHOT-RETENTION-1.0.0", "RS-1.0.0"));
    @Test
    void featureMigrationIsTheDynamicallyDiscoveredGlobalSuccessor() throws Exception {
        List<Migration> inventory = new ArrayList<>();
        try (var paths = Files.walk(MIGRATIONS)) {
            paths.filter(path -> path.getFileName().toString().startsWith("V"))
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(path -> inventory.add(migration(path)));
        }
        inventory.sort(Comparator.comparingInt(Migration::version));

        List<Migration> feature = inventory.stream()
                .filter(item -> item.path().getFileName().toString().endsWith(FEATURE_SUFFIX))
                .toList();
        assertEquals(1, feature.size(), "the batch/snapshot forward migration must exist exactly once");
        Migration candidate = feature.getFirst();
        int previousMaximum = inventory.stream()
                .filter(item -> item.version() < candidate.version())
                .mapToInt(Migration::version)
                .max()
                .orElseThrow();
        assertEquals(previousMaximum + 1, candidate.version(),
                "the feature migration must use the successor discovered at creation time");
        for (int index = 0; index < inventory.size(); index++) {
            assertEquals(index + 1, inventory.get(index).version(),
                    "the production inventory must remain one continuous global sequence");
        }
    }

    @Test
    void ownerObjectsAndSecurityDefinerInventoryAreCompleteAndLeastPrivilege() {
        JdbcTemplate jdbc = admin();
        assertEquals("180004", jdbc.queryForObject(
                "select current_setting('server_version_num')", String.class));
        assertEquals(Set.copyOf(TABLES), Set.copyOf(jdbc.queryForList("""
                select table_name
                  from information_schema.tables
                 where table_schema='ingestion_quality'
                   and table_name = any (?::text[])
                """, String.class, (Object) TABLES.toArray(String[]::new))));
        assertEquals("VIEW", jdbc.queryForObject("""
                select table_type
                  from information_schema.tables
                 where table_schema='ingestion_quality'
                   and table_name='iq_published_normalized_fact'
                """, String.class));

        for (String role : List.of(BATCH_OWNER, QUALITY_WORKER, RELAY, RETENTION)) {
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                    select not rolcanlogin and not (rolsuper or rolcreaterole or rolcreatedb
                           or rolreplication or rolbypassrls)
                      from pg_catalog.pg_roles where rolname=?
                    """, Boolean.class, role)), role);
        }
        assertEquals(FUNCTIONS.size(), jdbc.queryForObject("""
                select count(*)
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname = any (?::text[])
                   and procedure.prosecdef
                   and procedure.proconfig @> array['search_path=pg_catalog']
                   and not exists (
                     select 1
                       from pg_catalog.aclexplode(coalesce(
                              procedure.proacl,
                              pg_catalog.acldefault('f', procedure.proowner))) privilege
                      where privilege.grantee=0 and privilege.privilege_type='EXECUTE')
                   and procedure.proowner=(
                     select role_record.oid
                       from pg_catalog.pg_roles role_record
                      where role_record.rolname='scholarsense_ingestion_quality_batch_owner')
                """, Integer.class, (Object) FUNCTIONS.toArray(String[]::new)));

        Map<String, Set<String>> expected = Map.of(
                QUALITY_WORKER, WORKER_FUNCTIONS,
                RELAY, Set.of(
                        "iq_claim_next_batch_quality_outbox",
                        "iq_release_batch_quality_outbox",
                        "iq_deliver_batch_quality_outbox",
                        "iq_fail_batch_quality_outbox"),
                RETENTION, Set.of(
                        "iq_find_next_due_quality_snapshot_retention",
                        "iq_execute_quality_snapshot_retention"),
                ONLINE, Set.of(
                        "iq_find_assessed_quality_snapshot_ids",
                        "iq_find_assessed_quality_snapshot",
                        "iq_find_assessed_quality_snapshot_metrics",
                        "iq_find_assessed_quality_snapshot_impact_scopes",
                        "iq_find_assessed_quality_snapshot_page",
                        "iq_find_assessed_quality_snapshot_page_metrics",
                        "iq_find_assessed_quality_snapshot_page_impact_scopes",
                        "iq_resolve_quality_snapshot_source",
                        "iq_append_quality_snapshot_read_audit"),
                AUTHORITY, Set.of("iq_ingest_quality_snapshot_retention_authority"));
        expected.forEach((role, allowed) -> FUNCTIONS.forEach(function -> assertEquals(
                allowed.contains(function), canExecute(jdbc, role, function),
                role + " -> " + function)));
    }

    @Test
    void databaseStateVisibilityAndAtomicCommitShapeFailClosed() {
        JdbcTemplate jdbc = admin();
        String batchChecks = constraintDefinitions(jdbc, "iq_data_batch");
        for (String status : List.of(
                "receiving", "sealed", "quality-passed", "quality-failed", "published")) {
            assertTrue(batchChecks.contains(status), status);
        }
        assertTrue(batchChecks.contains("9007199254740991"), "versions must be JSON-safe integers");

        String publishedView = jdbc.queryForObject("""
                select pg_catalog.pg_get_viewdef(view_relation.oid, true)
                  from pg_catalog.pg_class view_relation
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=view_relation.relnamespace
                 where namespace.nspname='ingestion_quality'
                   and view_relation.relname='iq_published_normalized_fact'
                """, String.class);
        assertNotNull(publishedView);
        assertTrue(publishedView.contains("iq_normalized_fact"));
        assertTrue(publishedView.contains("iq_data_batch"));
        assertTrue(publishedView.contains("published"));

        for (String role : List.of(QUALITY_WORKER,
                "scholarsense_ingestion_quality_online", RELAY, RETENTION)) {
            for (String table : TABLES) {
                for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                    assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                            "select has_table_privilege(?,?,?)", Boolean.class,
                            role, "ingestion_quality." + table, privilege)),
                            role + " raw " + table + " " + privilege);
                }
            }
        }

        String commit = functionDefinition(jdbc, "iq_commit_batch_quality_evaluation");
        for (String token : List.of(
                "iq_quality_snapshot", "iq_quality_snapshot_audit_token_binding",
                "iq_quality_snapshot_metric", "iq_data_batch",
                "iq_append_batch_audit", "iq_batch_quality_outbox",
                "iq_claim_batch_command", "iq_complete_batch_command")) {
            assertTrue(commit.contains(token), "atomic evaluation boundary missing " + token);
        }
        String appendAudit = functionDefinition(jdbc, "iq_append_batch_audit");
        assertTrue(appendAudit.contains("iq_local_audit_fact"));
        assertTrue(appendAudit.contains("iq_local_audit_outbox"));
        String publish = functionDefinition(jdbc, "iq_publish_data_batch");
        assertTrue(publish.contains("iq_data_batch"));
        int publishPayloadValidation = publish.indexOf(
                "iq_validate_batch_published_payload");
        int publishIdempotencyClaim = publish.indexOf("iq_claim_batch_command");
        assertTrue(publishPayloadValidation >= 0);
        assertTrue(publishPayloadValidation < publishIdempotencyClaim,
                "published PIC validation must precede the first idempotency write");
        assertFalse(publish.contains("iq_normalized_fact"),
                "publish must trust the frozen O(1) accumulator, not rescan facts");
        assertTrue(publish.contains("published"));
    }

    @Test
    void idempotencyPrecedenceInspectionSharesTheOwnerScopeTransactionLock() {
        JdbcTemplate jdbc = admin();
        String claim = functionDefinition(jdbc, "iq_claim_batch_command");
        int claimLock = claim.indexOf("pg_advisory_xact_lock");
        int claimClock = claim.indexOf("iq_processing_at := pg_catalog.clock_timestamp()");
        int claimInsert = claim.indexOf("insert into ingestion_quality.iq_batch_idempotency");
        int claimRowLock = claim.indexOf("for update");
        assertTrue(claimLock >= 0 && claimLock < claimClock
                        && claimClock < claimInsert && claimInsert < claimRowLock,
                "the shared scope transaction lock must precede claim row access");

        String inspect = functionDefinition(jdbc, "iq_inspect_batch_command_precedence");
        int workloadGuard = inspect.indexOf("iq_require_exclusive_workload");
        int inspectLock = inspect.indexOf("pg_advisory_xact_lock");
        int inspectClock = inspect.indexOf(
                "iq_processing_at := pg_catalog.clock_timestamp()");
        int inspectRead = inspect.indexOf("from ingestion_quality.iq_batch_idempotency");
        assertTrue(workloadGuard >= 0 && workloadGuard < inspectLock
                        && inspectLock < inspectClock && inspectClock < inspectRead,
                "inspection must read a post-lock snapshot under the quality-worker guard");
        assertTrue(inspect.contains("'fresh'"));
        assertTrue(inspect.contains("'mismatch'"));
        assertTrue(inspect.contains("'replay'"));
        assertTrue(inspect.contains("authenticated_actor <> session_user::text"));
        assertTrue(inspect.contains("command_type <> requested_command_type"));
        assertTrue(inspect.contains("request_digest <> requested_request_digest"));
        assertTrue(inspect.contains("ingestion_quality_dependency_unavailable"));
        assertFalse(inspect.contains("pg_advisory_lock("),
                "session advisory locks can leak across pooled connections");

        String receive = functionDefinition(jdbc, "iq_receive_data_batch");
        int receiveLock = receive.indexOf("pg_advisory_xact_lock");
        int receiveClock = receive.indexOf("iq_processing_at := pg_catalog.clock_timestamp()");
        int receiveRead = receive.indexOf("from ingestion_quality.iq_batch_idempotency");
        assertTrue(receiveLock >= 0 && receiveLock < receiveClock && receiveClock < receiveRead,
                "receive preflight must evaluate expiry with post-lock database time");
    }

    @Test
    void boundedReceivingMeasurementsMakeTheFinalEvaluationTransactionShort() {
        JdbcTemplate jdbc = admin();
        assertEquals(Set.of("batch_id", "formula_id", "applicable", "sealed_at"),
                Set.copyOf(jdbc.queryForList("""
                        select column_name
                          from information_schema.columns
                         where table_schema='ingestion_quality'
                           and table_name='iq_batch_quality_measurement'
                           and column_name = any (?::text[])
                        """, String.class, (Object) new String[] {
                            "batch_id", "formula_id", "applicable", "sealed_at"})),
                "formula evidence must be bounded and frozen at seal");
        String record = functionDefinition(jdbc, "iq_record_batch_quality_measurement");
        assertTrue(record.contains("receiving"));
        assertTrue(record.contains("iq_batch_quality_operand"));
        String seal = functionDefinition(jdbc, "iq_seal_data_batch");
        assertTrue(seal.contains("iq_batch_quality_measurement"));
        assertTrue(seal.contains("sealed_at"));
        String commit = functionDefinition(jdbc, "iq_commit_batch_quality_evaluation");
        assertTrue(commit.contains("iq_qmdp_expected_metrics"));
        assertFalse(commit.contains("from ingestion_quality.iq_batch_quality_measurement"),
                "evaluation must consume the bounded frozen projection through its owner helper");
        String expectedMetrics = functionDefinition(jdbc, "iq_qmdp_expected_metrics");
        assertTrue(expectedMetrics.contains("iq_batch_quality_measurement"));
        assertTrue(expectedMetrics.contains("iq_batch_quality_operand"));
        assertFalse(commit.contains("lease_until"));
    }

    @Test
    void assessedSnapshotPpProbeUsesBoundedIndexesWithLowFixtureLatency() {
        JdbcTemplate jdbc = admin();
        String list = functionDefinition(jdbc, "iq_find_assessed_quality_snapshot_ids");
        assertTrue(list.contains("limit requested_limit"));
        assertTrue(list.contains("evaluated_at, snapshot.snapshot_id"));
        for (String forbidden : List.of(
                "iq_normalized_fact", "iq_batch_quality_measurement",
                "iq_batch_quality_operand", "iq_published_normalized_fact")) {
            assertFalse(list.contains(forbidden), forbidden);
        }

        List<String> unfiltered = explain(jdbc, """
                select snapshot_id
                  from ingestion_quality.iq_quality_snapshot
                 order by evaluated_at desc, snapshot_id desc
                 limit 21
                """);
        assertTrue(String.join("\n", unfiltered).contains(
                "iq_quality_snapshot_assessed_page_idx"), unfiltered.toString());

        List<String> scoped = explain(jdbc, """
                select snapshot_id
                  from ingestion_quality.iq_quality_snapshot
                 where source_id='SRC-P0-CARD-001'
                   and overall_result='quality-passed'
                 order by evaluated_at desc, snapshot_id desc
                 limit 21
                """);
        assertTrue(String.join("\n", scoped).contains(
                "iq_quality_snapshot_source_result_page_idx"), scoped.toString());

        double executionMilliseconds = unfiltered.stream()
                .filter(line -> line.startsWith("Execution Time:"))
                .map(line -> line.replace("Execution Time:", "")
                        .replace("ms", "").trim())
                .mapToDouble(Double::parseDouble)
                .findFirst().orElseThrow();
        assertTrue(executionMilliseconds < 100.0,
                "PP fixture-only snapshot page probe exceeded 100 ms: "
                        + executionMilliseconds);
    }

    @Test
    void retentionIsExclusiveFailClosedAndDeletesOwnerRowsWithResultAtomically() {
        JdbcTemplate jdbc = admin();
        for (String role : List.of(QUALITY_WORKER,
                "scholarsense_ingestion_quality_online", RELAY, AUTHORITY)) {
            assertFalse(canExecute(jdbc, role, "iq_execute_quality_snapshot_retention"), role);
        }
        assertTrue(canExecute(jdbc, RETENTION, "iq_execute_quality_snapshot_retention"));
        assertFalse(canExecute(
                jdbc, RETENTION, "iq_ingest_quality_snapshot_retention_authority"));
        assertTrue(canExecute(
                jdbc, AUTHORITY, "iq_ingest_quality_snapshot_retention_authority"));

        String retention = functionDefinition(jdbc, "iq_execute_quality_snapshot_retention");
        for (String guard : List.of(
                "blocked", "retention_due_at", "legal_hold", "consumer_registry",
                "watermark")) {
            assertTrue(retention.contains(guard), "retention fail-closed guard missing " + guard);
        }
        for (String atomicTarget : List.of(
                "delete from ingestion_quality.iq_quality_snapshot_metric",
                "delete from ingestion_quality.iq_quality_snapshot",
                "insert into ingestion_quality.iq_quality_snapshot_deletion_result",
                "insert into ingestion_quality.iq_batch_quality_outbox")) {
            assertTrue(retention.contains(atomicTarget),
                    "eligible deletion transaction missing " + atomicTarget);
        }
        assertFalse(retention.contains("deletionreceipt"),
                "audit-operations, not ingestion-quality, owns the final receipt");
    }

    @Test
    void actualLoginsExerciseAtomicLifecycleIdempotencyRollbackAndRetention() {
        ensureWorkloadLogins();
        JdbcTemplate worker = workload(WORKER_LOGIN);
        JdbcTemplate relay = workload(RELAY_LOGIN);
        JdbcTemplate retention = workload(RETENTION_LOGIN);
        JdbcTemplate online = workload(ONLINE_LOGIN);
        JdbcTemplate jdbc = admin();

        assertDatabaseFailure("permission denied", () -> worker.update(
                "insert into ingestion_quality.iq_data_batch default values"));
        assertDatabaseFailure("permission denied", () -> relay.update(
                "delete from ingestion_quality.iq_batch_quality_outbox"));
        assertDatabaseFailure("permission denied", () -> retention.update(
                "delete from ingestion_quality.iq_quality_snapshot"));
        assertDatabaseFailure("permission denied", () -> online.queryForObject(
                "select ingestion_quality.iq_receive_data_batch(" +
                        "null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null)",
                UUID.class));

        String receiveScope = "1".repeat(64);
        String receiveRequest = digest('2');
        assertEquals(BATCH_ID, receive(worker, receiveScope, receiveRequest));
        assertEquals(BATCH_ID, receive(worker, receiveScope, receiveRequest),
                "same-same receive must replay the completed result");
        assertDatabaseFailure("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH",
                () -> receive(worker, receiveScope, digest('3')));
        assertEquals(1, count(jdbc, "iq_data_batch"));
        assertEquals(1, count(jdbc, "iq_batch_idempotency"));
        assertAuditEvidenceTrace(jdbc, "data-batch.receive", RECEIVE_TRACE);

        assertTrue(Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_append_normalized_fact(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class, BATCH_ID, RECORD_ID, SOURCE_ID, BUSINESS_KEY, 1L,
                SCHEMA_VERSION, SCHEMA_DIGEST, LINEAGE_ID, digest('4'),
                Timestamp.from(RECEIVED_AT.plusSeconds(5)))));
        assertTrue(Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_append_normalized_fact(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class, BATCH_ID, RECORD_ID, SOURCE_ID, BUSINESS_KEY, 1L,
                SCHEMA_VERSION, SCHEMA_DIGEST, LINEAGE_ID, digest('4'),
                Timestamp.from(RECEIVED_AT.plusSeconds(5)))));
        assertEquals(1L, jdbc.queryForObject("""
                select normalized_fact_count from ingestion_quality.iq_data_batch
                 where batch_id=?
                """, Long.class, BATCH_ID),
                "same-same fact replay must increment the accumulator only once");
        assertEquals(SCHEMA_VERSION, jdbc.queryForObject("""
                select normalized_fact_schema_version
                  from ingestion_quality.iq_data_batch where batch_id=?
                """, String.class, BATCH_ID));
        assertEquals(SCHEMA_DIGEST, jdbc.queryForObject("""
                select normalized_fact_schema_digest
                  from ingestion_quality.iq_data_batch where batch_id=?
                """, String.class, BATCH_ID).trim());
        recordMeasurements(worker);
        assertTrue(recordImpactScope(worker, RECEIVED_AT.plusSeconds(15)));
        assertTrue(recordImpactScope(worker, RECEIVED_AT.plusSeconds(15)),
                "same raw impact/evidence/time must replay");
        assertDatabaseFailure("INGESTION_QUALITY_IMPACT_SCOPE_CONFLICT",
                () -> recordImpactScope(worker, RECEIVED_AT.plusSeconds(16)));
        assertEquals(0, publishedFactCount(jdbc),
                "receiving facts must not become visible through the published view");

        String failedSealScope = "4".repeat(64);
        assertDatabaseFailure("INGESTION_QUALITY_SEAL_EVIDENCE_INVALID",
                () -> seal(worker, 9L, failedSealScope, digest('6')));
        assertEquals("receiving", status(jdbc));
        assertEquals(0, countByScope(jdbc, failedSealScope),
                "a failed command must roll its idempotency claim back");

        String sealScope = "5".repeat(64);
        String sealRequest = digest('7');
        assertTrue(seal(worker, 1L, sealScope, sealRequest));
        assertFalse(seal(worker, 1L, sealScope, sealRequest),
                "same-same seal must replay without another state change");
        assertEquals("sealed", status(jdbc));
        assertAuditEvidenceTrace(jdbc, "data-batch.seal", SEAL_TRACE);
        assertEquals(SEALED_AT, jdbc.queryForObject("""
                select sealed_at from ingestion_quality.iq_batch_quality_measurement
                 where batch_id=? and formula_id=?
                """, Timestamp.class, BATCH_ID,
                DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD
                        .definitions().getFirst().formulaId()).toInstant());
        assertEquals(SEALED_AT, jdbc.queryForObject("""
                select sealed_at from ingestion_quality.iq_batch_quality_impact_scope
                 where batch_id=? and scope_code_utf8=?
                """, Timestamp.class, BATCH_ID, IMPACT_SCOPE).toInstant());
        assertDatabaseFailure("INGESTION_QUALITY_NORMALIZED_FACT_STATE_INVALID",
                () -> worker.queryForObject("""
                        select ingestion_quality.iq_append_normalized_fact(
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Boolean.class, BATCH_ID,
                        "late\0record".getBytes(StandardCharsets.UTF_8), SOURCE_ID,
                        BUSINESS_KEY, 1L, SCHEMA_VERSION, SCHEMA_DIGEST, LINEAGE_ID,
                        digest('8'), Timestamp.from(SEALED_AT.plusSeconds(1))));

        String malformedMetrics =
                DataBatchPostgreSqlEvidenceFixtures.metricsWithFirstBasisPoints(9_999L);
        String failedEvaluationScope = "6".repeat(64);
        UUID failedSnapshot = uuid("019fe510-0000-7000-8000-000000000010");
        UUID failedEvent = uuid("019fe510-0000-7000-8000-000000000011");
        assertThrows(DataAccessException.class, () -> evaluate(worker, failedSnapshot,
                failedEvent, failedEvaluationScope, digest('9'), malformedMetrics));
        assertEquals(0, count(jdbc, "iq_quality_snapshot"));
        assertEquals(0, countByScope(jdbc, failedEvaluationScope));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, Integer.class, failedEvent));
        assertEquals("sealed", status(jdbc),
                "snapshot, metric, batch, audit, outbox, and idempotency must roll back together");

        String evaluateScope = "7".repeat(64);
        String evaluateRequest = digest('a');
        UUID assessedEvent = uuid("019fe510-0000-7000-8000-000000000012");
        assertTrue(evaluate(worker, SNAPSHOT_ID, assessedEvent, evaluateScope,
                evaluateRequest, DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD.metricsJson()));
        assertFalse(evaluate(worker, SNAPSHOT_ID, assessedEvent, evaluateScope,
                evaluateRequest, DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD.metricsJson()),
                "same-same evaluation must not duplicate snapshot or events");
        assertDatabaseFailure("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH",
                () -> evaluate(worker, SNAPSHOT_ID, assessedEvent, evaluateScope,
                        digest('b'), DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD.metricsJson()));
        assertDatabaseFailure("INGESTION_QUALITY_VERSION_CONFLICT",
                () -> evaluate(worker,
                        uuid("019fe510-0000-7000-8000-000000000013"),
                        uuid("019fe510-0000-7000-8000-000000000014"),
                        "8".repeat(64), digest('c'),
                        DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD.metricsJson()));
        assertEquals("quality-passed", status(jdbc));
        assertEquals(1, count(jdbc, "iq_quality_snapshot"));
        assertEquals(EXPECTED_MEASUREMENT_COUNT,
                count(jdbc, "iq_quality_snapshot_metric"));
        assertEquals(EVALUATE_TRACE, jdbc.queryForObject("""
                select trace_id from ingestion_quality.iq_quality_snapshot
                 where snapshot_id=?
                """, String.class, SNAPSHOT_ID).trim());
        assertCommandEvidenceTrace(jdbc, "data-batch.evaluate", assessedEvent,
                EVALUATE_TRACE);
        assertEquals(HexFormat.of().formatHex(IMPACT_SCOPE), jdbc.queryForObject("""
                select encode(scope_code_utf8, 'hex')
                  from ingestion_quality.iq_quality_snapshot_impact_scope
                 where snapshot_id=?
                """, String.class, SNAPSHOT_ID));
        assertEquals(0, publishedFactCount(jdbc));

        UUID rejectedPublishCommand = uuid("019fe510-0000-7000-8000-000000000034");
        UUID rejectedPublishEvent = uuid("019fe510-0000-7000-8000-000000000017");
        String rejectedPublishScope = "e".repeat(64);
        String rejectedPublishRequest = digest('e');
        DataBatchPostgreSqlEvidenceFixtures.AuditEnvelope rejectedPublishAudit =
                DataBatchPostgreSqlEvidenceFixtures.acceptedAudit(
                        WORKER_LOGIN,
                        rejectedPublishCommand,
                        BATCH_ID,
                        "data-batch.publish",
                        4L,
                        PUBLISH_TRACE,
                        rejectedPublishRequest,
                        PUBLISHED_AT);
        assertDatabaseFailure("INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID", () ->
                worker.queryForObject("""
                        select ingestion_quality.iq_publish_data_batch(
                          ?, ?, 3, ?, ?, ?, ?, ?::jsonb, ?, ?,
                          'scholarsense.ingestion-quality.data-batch.published.v1',
                          'DATA-BATCH-PUBLISHED-1.0.0', ?, ?)
                        """, Boolean.class,
                        rejectedPublishCommand,
                        BATCH_ID,
                        Timestamp.from(PUBLISHED_AT),
                        PUBLISH_TRACE,
                        rejectedPublishScope,
                        rejectedPublishRequest,
                        rejectedPublishAudit.payload(),
                        rejectedPublishAudit.digest(),
                        rejectedPublishEvent,
                        BUSINESS_PAYLOAD,
                        PAYLOAD_DIGEST));
        var afterRejectedPublish = jdbc.queryForMap("""
                select status, aggregate_version, published_at
                  from ingestion_quality.iq_data_batch
                 where batch_id=?
                """, BATCH_ID);
        assertEquals("quality-passed", afterRejectedPublish.get("status"));
        assertEquals(3L, afterRejectedPublish.get("aggregate_version"));
        assertNull(afterRejectedPublish.get("published_at"));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_fact
                 where audit_id=?
                """, Integer.class, rejectedPublishCommand));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_local_audit_outbox
                 where audit_id=?
                """, Integer.class, rejectedPublishCommand));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, Integer.class, rejectedPublishEvent));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_idempotency
                 where scope_digest=?
                """, Integer.class, rejectedPublishScope));
        assertEquals(0, publishedFactCount(jdbc));

        String publishScope = "9".repeat(64);
        String publishRequest = digest('d');
        UUID publishedEvent = uuid("019fe510-0000-7000-8000-000000000015");
        assertTrue(publish(worker, publishedEvent, publishScope, publishRequest));
        assertFalse(publish(worker, publishedEvent, publishScope, publishRequest));
        assertEquals("published", status(jdbc));
        assertEquals(RECEIVE_TRACE, jdbc.queryForObject("""
                select trace_id from ingestion_quality.iq_data_batch where batch_id=?
                """, String.class, BATCH_ID).trim(),
                "the batch row keeps the receive provenance trace");
        assertCommandEvidenceTrace(jdbc, "data-batch.publish", publishedEvent,
                PUBLISH_TRACE);
        assertEquals(1, publishedFactCount(jdbc),
                "one batch-row CAS must expose the whole fact set atomically");
        assertEquals(HexFormat.of().formatHex(RECORD_ID), jdbc.queryForObject("""
                select encode(record_id_utf8, 'hex')
                  from ingestion_quality.iq_published_normalized_fact
                 where batch_id=?
                """, String.class, BATCH_ID), "bytea must preserve U+0000 exactly");

        UUID claimableRelayEvent = uuid("019fe510-0000-7000-8000-000000000016");
        assertEquals(1, jdbc.update("""
                insert into ingestion_quality.iq_batch_quality_outbox
                  (event_id, aggregate_id, aggregate_version, event_type, schema_version,
                   payload_utf8, payload_digest, available_at, created_at)
                values (?, ?, 99,
                        'scholarsense.ingestion-quality.synthetic-relay-probe.v1',
                        'SYNTHETIC-RELAY-PROBE-1.0.0', ?, ?,
                        '1900-01-01T00:00:00Z'::timestamptz,
                        statement_timestamp() - interval '1 second')
                """, claimableRelayEvent, BATCH_ID, BUSINESS_PAYLOAD, PAYLOAD_DIGEST));
        assertEquals(claimableRelayEvent, relay.queryForObject("""
                select event_id
                  from ingestion_quality.iq_claim_next_batch_quality_outbox()
                """, UUID.class));
        assertEquals(1L, jdbc.queryForObject("""
                select attempts
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, Long.class, claimableRelayEvent));
        assertDatabaseFailure("permission denied", () -> relay.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set status='retrying'
                 where event_id=?
                """, claimableRelayEvent));
        assertDatabaseFailure("permission denied", () -> relay.update("""
                update ingestion_quality.iq_batch_quality_outbox
                   set payload_utf8=? where event_id=?
                """, "tampered".getBytes(StandardCharsets.UTF_8), assessedEvent));
        assertDatabaseFailure("permission denied", () -> relay.queryForObject(
                retentionSql(), String.class, retentionArguments(
                        uuid("019fe510-0000-7000-8000-000000000020"),
                        uuid("019fe510-0000-7000-8000-000000000021"),
                        uuid("019fe510-0000-7000-8000-000000000040"))));
        assertDatabaseFailure("permission denied", () -> retention.queryForObject(
                "select ingestion_quality.iq_publish_data_batch(" +
                        "null,null,null,null,null,null,null,null,null,null,null,null,null,null)",
                Boolean.class));

        UUID notDueAuthority = uuid("019fe510-0000-7000-8000-000000000040");
        UUID notDueResult = uuid("019fe510-0000-7000-8000-000000000022");
        assertEquals("blocked", retention.queryForObject(
                retentionSql(), String.class,
                retentionArguments(SNAPSHOT_ID, notDueResult, notDueAuthority)));
        assertEquals(1, count(jdbc, "iq_quality_snapshot"));
        assertEquals(EXPECTED_MEASUREMENT_COUNT, count(jdbc, "iq_quality_snapshot_metric"));
        Map<String, Object> notDue = jdbc.queryForMap("""
                select result, blocker_code, occurred_at,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,scope,evaluatedAt}' as evaluated_at,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,guards,trustedTime,observedAt}' as observed_at,
                       convert_from(payload_utf8, 'UTF8')::jsonb
                         #>> '{data,guards,legalHold,checkedAt}' as legal_checked_at
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id=?
                """, notDueResult);
        assertEquals("blocked", notDue.get("result"));
        assertEquals("CONSUMER_REGISTRY_UNAVAILABLE", notDue.get("blocker_code"));
        assertEquals(List.of(
                "CONSUMER_REGISTRY_UNAVAILABLE", "RETENTION_NOT_DUE"),
                jdbc.queryForList("""
                        select jsonb_array_elements_text(
                                 convert_from(payload_utf8, 'UTF8')::jsonb
                                   #> '{data,blockerCodes}')
                          from ingestion_quality.iq_quality_snapshot_deletion_result
                         where result_event_id=?
                        """, String.class, notDueResult));
        Instant evaluatedAt = Instant.parse((String) notDue.get("evaluated_at"));
        Instant observedAt = Instant.parse((String) notDue.get("observed_at"));
        Instant legalCheckedAt = Instant.parse((String) notDue.get("legal_checked_at"));
        assertFalse(observedAt.isBefore(evaluatedAt));
        assertFalse(legalCheckedAt.isBefore(evaluatedAt));
        assertFalse(((Timestamp) notDue.get("occurred_at")).toInstant()
                .isBefore(observedAt));
        assertEquals(1, jdbc.queryForObject("""
                select count(*)
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, Integer.class, notDueResult));
    }

    private static UUID receive(JdbcTemplate worker, String scope, String requestDigest) {
        UUID commandId = uuid("019fe510-0000-7000-8000-000000000030");
        DataBatchPostgreSqlEvidenceFixtures.AuditEnvelope audit =
                DataBatchPostgreSqlEvidenceFixtures.acceptedAudit(
                        WORKER_LOGIN, commandId, BATCH_ID, "data-batch.receive", 1,
                        RECEIVE_TRACE, requestDigest, RECEIVED_AT);
        return worker.queryForObject("""
                select batch_id from ingestion_quality.iq_receive_data_batch(
                  ?, ?, ?, ?, ?, ?, null, null, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.class,
                commandId, BATCH_ID,
                SOURCE_ID, BUSINESS_KEY, 1L, LINEAGE_ID,
                Timestamp.from(RECEIVED_AT.minusSeconds(60)), MANIFEST_DIGEST,
                Timestamp.from(RECEIVED_AT), RECEIVE_TRACE, scope, requestDigest,
                audit.payload(), audit.digest());
    }

    private static void recordMeasurements(JdbcTemplate worker) {
        for (int ordinal = 0; ordinal < EXPECTED_MEASUREMENT_COUNT; ordinal++) {
            var definition = DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD
                    .definitions().get(ordinal);
            var input = DataBatchPostgreSqlEvidenceFixtures.PASSING_CARD
                    .inputs().get(definition.formulaId());
            String operands = DataBatchPostgreSqlEvidenceFixtures.json(input.operands());
            assertTrue(Boolean.TRUE.equals(worker.queryForObject("""
                    select ingestion_quality.iq_record_batch_quality_measurement(
                      ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """, Boolean.class, BATCH_ID, definition.formulaId(), ordinal,
                    input.applicable(), operands,
                    DataBatchPostgreSqlEvidenceFixtures.evidenceDigest(operands),
                    Timestamp.from(RECEIVED_AT.plusSeconds(10L + ordinal)))));
        }
    }

    private static boolean recordImpactScope(JdbcTemplate worker, Instant recordedAt) {
        return Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_record_batch_quality_impact_scope(?, ?, ?)
                """, Boolean.class, BATCH_ID, IMPACT_SCOPE, Timestamp.from(recordedAt)));
    }

    private static boolean seal(
            JdbcTemplate worker, long expectedVersion, String scope, String requestDigest) {
        UUID commandId = uuid("019fe510-0000-7000-8000-000000000031");
        DataBatchPostgreSqlEvidenceFixtures.AuditEnvelope audit =
                DataBatchPostgreSqlEvidenceFixtures.acceptedAudit(
                        WORKER_LOGIN, commandId, BATCH_ID, "data-batch.seal",
                        expectedVersion + 1, SEAL_TRACE, requestDigest, SEALED_AT);
        return Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_seal_data_batch(
                  ?, ?, ?, 1, 1, 0, ?, ?, ?, 'Asia/Shanghai', ?,
                  'CARD-SLICE-1.0.0', ?, 'DCC-1.1.0', ?, 'QG-1.0.0', ?,
                  'QMDP-1.0.0', ?, ?, ?, ?, 'transaction-record', ?, ?::jsonb,
                  ?, ?, ?, ?, ?::jsonb, ?)
                """, Boolean.class,
                commandId, BATCH_ID,
                expectedVersion, Timestamp.from(OBSERVATION_START_AT),
                Timestamp.from(CUTOFF_AT), Timestamp.from(CUTOFF_AT), WATERMARK,
                SCHEMA_DIGEST, CATALOG_DIGEST, GATE_DIGEST, QMDP_DIGEST,
                Timestamp.from(RECEIVED_AT.minusSeconds(20)),
                Timestamp.from(RECEIVED_AT.plusSeconds(300)), Timestamp.from(RECEIVED_AT),
                MANIFEST_DIGEST, DataBatchPostgreSqlEvidenceFixtures.sealedEvidenceJson(),
                Timestamp.from(SEALED_AT), SEAL_TRACE, scope, requestDigest,
                audit.payload(), audit.digest()));
    }

    private static boolean evaluate(
            JdbcTemplate worker,
            UUID snapshotId,
            UUID businessEventId,
            String scope,
            String requestDigest,
            String metrics) {
        UUID commandId = uuid("019fe510-0000-7000-8000-000000000032");
        UUID causationId = uuid("019fe510-0000-7000-8000-000000000031");
        DataBatchPostgreSqlEvidenceFixtures.AuditEnvelope audit =
                DataBatchPostgreSqlEvidenceFixtures.acceptedAudit(
                        WORKER_LOGIN, commandId, BATCH_ID, "data-batch.evaluate", 3,
                        EVALUATE_TRACE, requestDigest, EVALUATED_AT);
        DataBatchPostgreSqlEvidenceFixtures.BusinessEnvelope business =
                DataBatchPostgreSqlEvidenceFixtures.assessedBusiness(
                        commandId, causationId, businessEventId, BATCH_ID,
                        EVALUATE_TRACE, EVALUATED_AT, 1L,
                        RECEIVED_AT.minusSeconds(60), QUALITY_SNAPSHOT);
        return Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_commit_batch_quality_evaluation(
                  ?, ?, 2, ?, ?, ?, 'quality-passed', ?,
                  ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?,
                  ?, 'scholarsense.ingestion-quality.data-batch.quality-assessed.v1',
                  'DATA-BATCH-QUALITY-ASSESSED-1.0.0', ?, ?)
                """, Boolean.class,
                commandId, BATCH_ID, snapshotId,
                token("ost", snapshotId.toString()), token("agt", snapshotId.toString()),
                DataBatchPostgreSqlEvidenceFixtures.CARD.owner(),
                Timestamp.from(EVALUATED_AT), EVALUATE_TRACE, IMMUTABLE_HASH,
                RETENTION_SCOPE, metrics, scope, requestDigest, audit.payload(),
                audit.digest(), businessEventId, business.payload(), business.digest()));
    }

    private static boolean publish(
            JdbcTemplate worker, UUID businessEventId, String scope, String requestDigest) {
        UUID commandId = uuid("019fe510-0000-7000-8000-000000000033");
        UUID causationId = uuid("019fe510-0000-7000-8000-000000000032");
        DataBatchPostgreSqlEvidenceFixtures.AuditEnvelope audit =
                DataBatchPostgreSqlEvidenceFixtures.acceptedAudit(
                        WORKER_LOGIN, commandId, BATCH_ID, "data-batch.publish", 4,
                        PUBLISH_TRACE, requestDigest, PUBLISHED_AT);
        DataBatchPostgreSqlEvidenceFixtures.BusinessEnvelope business =
                DataBatchPostgreSqlEvidenceFixtures.publishedBusiness(
                        commandId, causationId, businessEventId, BATCH_ID,
                        PUBLISH_TRACE, PUBLISHED_AT, 1L,
                        RECEIVED_AT.minusSeconds(60), QUALITY_SNAPSHOT);
        return Boolean.TRUE.equals(worker.queryForObject("""
                select ingestion_quality.iq_publish_data_batch(
                  ?, ?, 3, ?, ?, ?, ?, ?::jsonb, ?, ?,
                  'scholarsense.ingestion-quality.data-batch.published.v1',
                  'DATA-BATCH-PUBLISHED-1.0.0', ?, ?)
                """, Boolean.class,
                commandId, BATCH_ID,
                Timestamp.from(PUBLISHED_AT), PUBLISH_TRACE, scope, requestDigest,
                audit.payload(), audit.digest(), businessEventId,
                business.payload(), business.digest()));
    }

    private static String metricsJson(long valueBasisPoints) {
        StringBuilder metrics = new StringBuilder("[");
        for (int ordinal = 0; ordinal < EXPECTED_MEASUREMENT_COUNT; ordinal++) {
            if (ordinal > 0) metrics.append(',');
            String metricId = metricId(ordinal);
            if (ordinal == EXPECTED_MEASUREMENT_COUNT - 1) {
                metrics.append("""
                        {
                          "metricId":"%s",
                          "formulaId":"%s",
                          "formulaVersion":"1.0.0",
                          "result":"not-applicable",
                          "applicable":false,
                          "numerator":0,
                          "denominator":0,
                          "valueBasisPoints":null,
                          "unit":"count",
                          "operator":"=",
                          "thresholdNumerator":0,
                          "thresholdDenominator":1,
                          "boundary":"inclusive",
                          "reasonCode":null
                        }
                        """.formatted(metricId, formulaId(ordinal)));
                continue;
            }
            metrics.append("""
                    {
                      "metricId":"%s",
                      "formulaId":"%s",
                      "formulaVersion":"1.0.0",
                      "result":"passed",
                      "applicable":true,
                      "numerator":1,
                      "denominator":1,
                      "valueBasisPoints":%d,
                      "unit":"basis-point",
                      "operator":">=",
                      "thresholdNumerator":1,
                      "thresholdDenominator":1,
                      "boundary":"inclusive",
                      "reasonCode":null
                    }
                    """.formatted(
                    metricId, formulaId(ordinal),
                    ordinal == 0 ? valueBasisPoints : 10_000L));
        }
        return metrics.append(']').toString();
    }

    private static String metricId(int ordinal) {
        return ordinal == 0 ? "freshness" : "bounded-" + ordinal;
    }

    private static String formulaId(int ordinal) {
        return "QMDP-1.0.0/" + metricId(ordinal);
    }

    private static String sealedContract() {
        return """
                {
                  "qshmProfileVersion":"QSHM-1.0.0",
                  "qshmProfileCanonicalDigest":"sha256:%s",
                  "qmdpApprovalRef":"AUTH-2026-08-08-001",
                  "qmdpEffectiveAt":"2023-08-01T00:00:00Z",
                  "qmdpProfileVersion":"QMDP-1.0.0",
                  "qmdpPolicyCanonicalDigest":"%s"
                }
                """.formatted("6".repeat(64), QMDP_DIGEST);
    }

    private static String retentionSql() {
        return """
                select ingestion_quality.iq_execute_quality_snapshot_retention(
                  ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private static Object[] retentionArguments(
            UUID executionId, UUID resultEventId, UUID authorityEvidenceId) {
        return new Object[] {
            executionId, resultEventId, SNAPSHOT_ID, IMMUTABLE_HASH, RETENTION_SCOPE,
            authorityEvidenceId, RETENTION_TRACE
        };
    }

    private static int publishedFactCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_published_normalized_fact
                 where batch_id=?
                """, Integer.class, BATCH_ID);
    }

    private static String status(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select status from ingestion_quality.iq_data_batch where batch_id=?
                """, String.class, BATCH_ID);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject(
                "select count(*) from ingestion_quality." + table, Integer.class);
    }

    private static int countByScope(JdbcTemplate jdbc, String scope) {
        return jdbc.queryForObject("""
                select count(*) from ingestion_quality.iq_batch_idempotency
                 where scope_digest=?
                """, Integer.class, scope);
    }

    private static void assertAuditEvidenceTrace(
            JdbcTemplate jdbc, String action, String expectedTrace) {
        assertEquals(expectedTrace, jdbc.queryForObject("""
                select trace_id
                  from ingestion_quality.iq_local_audit_fact
                 where batch_id=? and action=? and result='accepted'
                """, String.class, BATCH_ID, action).trim());
        assertEquals(expectedTrace, jdbc.queryForObject("""
                select audit_outbox.payload #>> '{fact,traceId}'
                  from ingestion_quality.iq_local_audit_outbox audit_outbox
                  join ingestion_quality.iq_local_audit_fact audit_fact
                    on audit_fact.audit_id=audit_outbox.audit_id
                 where audit_fact.batch_id=? and audit_fact.action=?
                   and audit_fact.result='accepted'
                """, String.class, BATCH_ID, action));
    }

    private static void assertCommandEvidenceTrace(
            JdbcTemplate jdbc, String action, UUID eventId, String expectedTrace) {
        assertAuditEvidenceTrace(jdbc, action, expectedTrace);
        assertEquals(expectedTrace, jdbc.queryForObject("""
                select convert_from(payload_utf8, 'UTF8')::jsonb #>> '{data,traceId}'
                  from ingestion_quality.iq_batch_quality_outbox
                 where event_id=?
                """, String.class, eventId));
        assertEquals(
                "00-" + expectedTrace + "-" + traceparentSpanId(expectedTrace) + "-01",
                jdbc.queryForObject("""
                        select convert_from(payload_utf8, 'UTF8')::jsonb ->> 'traceparent'
                          from ingestion_quality.iq_batch_quality_outbox
                         where event_id=?
                        """, String.class, eventId));
    }

    private static String traceparentSpanId(String traceId) {
        String prefix = traceId.substring(0, 16);
        return "0000000000000000".equals(prefix) ? traceId.substring(16) : prefix;
    }

    private static void assertDatabaseFailure(String messageFragment, Runnable command) {
        DataAccessException failure = assertThrows(DataAccessException.class, command::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.toLowerCase().contains(messageFragment.toLowerCase()), message);
    }

    private static List<String> explain(JdbcTemplate jdbc, String sql) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<List<String>>)
                connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("set enable_seqscan=off");
                        statement.execute("set enable_bitmapscan=off");
                        statement.execute("set enable_sort=off");
                        try {
                            try (var rows = statement.executeQuery(
                                    "explain (analyze, costs off, timing off, summary on) "
                                            + sql)) {
                                List<String> plan = new ArrayList<>();
                                while (rows.next()) plan.add(rows.getString(1));
                                return plan;
                            }
                        } finally {
                            statement.execute("reset enable_seqscan");
                            statement.execute("reset enable_bitmapscan");
                            statement.execute("reset enable_sort");
                        }
                    }
                });
    }

    private static void ensureWorkloadLogins() {
        admin().execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_batch_worker_test_login') then
                        create role scholarsense_iq_batch_worker_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_batch_relay_test_login') then
                        create role scholarsense_iq_batch_relay_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_batch_retention_test_login') then
                        create role scholarsense_iq_batch_retention_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_batch_online_test_login') then
                        create role scholarsense_iq_batch_online_test_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_batch_worker_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_batch_relay_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_batch_retention_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_batch_online_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_batch_owner
                    from scholarsense_iq_batch_worker_test_login,
                         scholarsense_iq_batch_relay_test_login,
                         scholarsense_iq_batch_retention_test_login,
                         scholarsense_iq_batch_online_test_login;
                grant scholarsense_ingestion_quality_quality_worker
                    to scholarsense_iq_batch_worker_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_relay
                    to scholarsense_iq_batch_relay_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_retention_executor
                    to scholarsense_iq_batch_retention_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_online
                    to scholarsense_iq_batch_online_test_login
                    with inherit true, set false;
                """);
    }

    private static Migration migration(Path path) {
        Matcher matcher = VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) throw new IllegalStateException("invalid migration " + path);
        return new Migration(Integer.parseInt(matcher.group(1)), path);
    }

    private static boolean canExecute(JdbcTemplate jdbc, String role, String function) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)=1 and bool_and(has_function_privilege(?, procedure.oid, 'EXECUTE'))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality' and procedure.proname=?
                """, Boolean.class, role, function));
    }

    private static String constraintDefinitions(JdbcTemplate jdbc, String table) {
        return String.join("\n", jdbc.queryForList("""
                select lower(pg_catalog.pg_get_constraintdef(constraint_record.oid, true))
                  from pg_catalog.pg_constraint constraint_record
                  join pg_catalog.pg_class relation
                    on relation.oid=constraint_record.conrelid
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=relation.relnamespace
                 where namespace.nspname='ingestion_quality' and relation.relname=?
                """, String.class, table));
    }

    private static String functionDefinition(JdbcTemplate jdbc, String function) {
        String definition = jdbc.queryForObject("""
                select lower(pg_catalog.pg_get_functiondef(procedure.oid))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality' and procedure.proname=?
                """, String.class, function);
        assertNotNull(definition, function);
        return definition;
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(dataSource(required("scholarsense.audit.pg.user")));
    }

    private static JdbcTemplate workload(String username) {
        return new JdbcTemplate(dataSource(username));
    }

    private static DataSource dataSource(String username) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(username);
        source.setPassword(System.getProperty("scholarsense.audit.pg.password", ""));
        return source;
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String token(String prefix, String value) {
        return prefix + "_v1_k1_" + sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Migration(int version, Path path) {}
}
