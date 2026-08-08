package cn.edu.suda.scholarsense.subjectregistry.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.JdbcSubjectRegistryStore;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.JdbcSubjectMappingEventRelayStore;
import cn.edu.suda.scholarsense.subjectregistry.application.IngestCommit;
import cn.edu.suda.scholarsense.subjectregistry.application.ProtectedIdentifierMaterial;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryAuditEvent;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryIdPort;
import cn.edu.suda.scholarsense.subjectregistry.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierKey;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import cn.edu.suda.scholarsense.subjectregistry.domain.ProtectedIdentifierToken;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping;
import java.time.Instant;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** PostgreSQL 18.4 actual-login evidence, launched by scripts/run_audit_postgresql_tests.sh. */
class SubjectRegistryPostgreSqlIT {
    private static final String ONLINE_LOGIN = "scholarsense_sr_online_test_login";
    private static final String RELAY_LOGIN = "scholarsense_sr_relay_test_login";
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");
    private static final UUID EXCEPTION_ID = uuid("019fcfea-6000-7000-8000-000000000001");
    private static final UUID IDENTIFIER_ID = uuid("019fcfea-6000-7000-8000-000000000002");
    private static final UUID STUDENT_A = uuid("019fcfea-6000-7000-8000-000000000003");
    private static final UUID STUDENT_B = uuid("019fcfea-6000-7000-8000-000000000004");
    private JdbcTemplate admin;

    @BeforeEach
    void setUp() {
        admin = new JdbcTemplate(adminDataSource());
        admin.execute("""
                truncate table
                  subject_registry.sr_local_audit_outbox,
                  subject_registry.sr_local_audit_fact,
                  subject_registry.sr_mapping_recompute_outbox,
                  subject_registry.sr_correction_target,
                  subject_registry.sr_correction_event,
                  subject_registry.sr_repair_idempotency,
                  subject_registry.sr_mapping_exception,
                  subject_registry.sr_subject_mapping,
                  subject_registry.sr_identifier_secret,
                  subject_registry.sr_student_ref_reservation cascade
                """);
        ensureOnlineLogin();
        ensureRelayLogin();
    }

    @Test
    void exactServerSchemaAndFunctionOnlyPrivilegesArePresent() {
        assertEquals(ONLINE_LOGIN,
                SubjectRegistryPostgreSqlDataSourceStartupGate.verifyOnline(
                        onlineDataSource(), "test", ONLINE_LOGIN)
                        .expectedWorkloadIdentity());
        assertEquals(RELAY_LOGIN,
                SubjectRegistryPostgreSqlDataSourceStartupGate.verifyRelay(
                        relayDataSource(), "test", RELAY_LOGIN)
                        .expectedWorkloadIdentity());
        assertEquals("180004", admin.queryForObject(
                "select current_setting('server_version_num')", String.class));
        assertEquals(10, admin.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema='subject_registry' and table_type='BASE TABLE'
                """, Integer.class));
        assertTrue(Boolean.TRUE.equals(admin.queryForObject(
                "select has_schema_privilege('scholarsense_subject_registry_online',"
                        + "'subject_registry','USAGE')", Boolean.class)));
        for (String table : List.of(
                "sr_student_ref_reservation", "sr_identifier_secret", "sr_subject_mapping",
                "sr_mapping_exception", "sr_correction_event", "sr_correction_target",
                "sr_mapping_recompute_outbox", "sr_repair_idempotency",
                "sr_local_audit_fact", "sr_local_audit_outbox")) {
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertFalse(Boolean.TRUE.equals(admin.queryForObject(
                        "select has_table_privilege('scholarsense_subject_registry_online',?,?)",
                        Boolean.class, "subject_registry." + table, privilege)),
                        table + " " + privilege);
            }
        }
        for (String function : List.of(
                "sr_issue_student_ref", "sr_record_subject_mapping",
                "sr_record_mapping_exception",
                "sr_repair_mapping_exception",
                "sr_find_pending_recompute_request")) {
            assertTrue(functionPrivilege(function), function);
        }
        assertFalse(Boolean.TRUE.equals(admin.queryForObject(
                "select has_table_privilege('scholarsense_subject_registry_online',"
                        + "'subject_registry.sr_mapping_recompute_outbox','SELECT')",
                Boolean.class)));
        assertTrue(Boolean.TRUE.equals(admin.queryForObject(
                "select has_table_privilege('scholarsense_subject_registry_relay',"
                        + "'subject_registry.sr_mapping_recompute_outbox','SELECT')",
                Boolean.class)));
        assertTrue(Boolean.TRUE.equals(admin.queryForObject("""
                select has_column_privilege(
                  'scholarsense_subject_registry_relay',
                  'subject_registry.sr_mapping_recompute_outbox','status','UPDATE')
                """, Boolean.class)));
        assertFalse(Boolean.TRUE.equals(admin.queryForObject("""
                select has_column_privilege(
                  'scholarsense_subject_registry_relay',
                  'subject_registry.sr_mapping_recompute_outbox','payload','UPDATE')
                """, Boolean.class)));
        assertFalse(Boolean.TRUE.equals(admin.queryForObject(
                "select has_table_privilege('scholarsense_subject_registry_relay',"
                        + "'subject_registry.sr_mapping_recompute_outbox','INSERT')",
                Boolean.class)));
        assertEquals(5, admin.queryForObject("""
                select count(*)
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='subject_registry'
                   and procedure.proname in (
                     'sr_issue_student_ref','sr_record_subject_mapping',
                     'sr_record_mapping_exception',
                     'sr_repair_mapping_exception',
                     'sr_find_pending_recompute_request')
                   and procedure.prosecdef
                   and procedure.proconfig @> array['search_path=pg_catalog']
                """, Integer.class));
    }

    @Test
    void subjectMappingFunctionReusesTheProtectedKeyAndDatabaseRejectsOverlap() {
        UUID student = uuid("019fcfea-6000-7000-8000-000000000081");
        JdbcTemplate online = new JdbcTemplate(onlineDataSource());
        recordMapping(online, student,
                uuid("019fcfea-6000-7000-8000-000000000082"),
                uuid("019fcfea-6000-7000-8000-000000000083"),
                uuid("019fcfea-6000-7000-8000-000000000084"),
                NOW.minusSeconds(60), NOW, 1);
        recordMapping(online, student,
                uuid("019fcfea-6000-7000-8000-000000000085"),
                uuid("019fcfea-6000-7000-8000-000000000086"),
                uuid("019fcfea-6000-7000-8000-000000000087"),
                NOW, NOW.plusSeconds(60), 2);
        assertThrows(DataAccessException.class, () -> recordMapping(
                online, student,
                uuid("019fcfea-6000-7000-8000-000000000088"),
                uuid("019fcfea-6000-7000-8000-000000000089"),
                uuid("019fcfea-6000-7000-8000-000000000090"),
                NOW.minusNanos(1), NOW.plusSeconds(1), 3));
        assertEquals(2, count("sr_subject_mapping"));
        assertEquals(1, count("sr_identifier_secret"));
    }

    @Test
    void productionJdbcStoreUsesOnlyTheControlledMappingFunctionAndRestoresTimeline() {
        JdbcTemplate online = new JdbcTemplate(onlineDataSource());
        SubjectRegistryIdPort idPort = new SubjectRegistryIdPort() {
            @Override public UUID nextUuid() { return nextEvidenceUuid(); }
            @Override public StudentRef nextStudentRef() {
                return StudentRef.of(nextEvidenceUuid());
            }
        };
        JdbcSubjectRegistryStore store = new JdbcSubjectRegistryStore(
                online, new tools.jackson.databind.ObjectMapper(), idPort);
        StudentRef student = StudentRef.of(
                uuid("019fcfea-6000-7000-8000-000000000091"));
        ProtectedIdentifierToken token = ProtectedIdentifierToken.of(
                "prod", "kms://subject-registry/identifier-bundle", "v1",
                "hmac-sha256:" + "e".repeat(64));
        IdentifierKey key = new IdentifierKey(
                "SRC-P0-STUDENT-001", IdentifierType.STUDENT_NUMBER, token);
        SubjectMapping mapping = SubjectMapping.active(
                uuid("019fcfea-6000-7000-8000-000000000092"),
                uuid("019fcfea-6000-7000-8000-000000000093"), key, student,
                EffectiveInterval.of(NOW.minusSeconds(10), null), 1);
        TimeSourceProfile profile = new TimeSourceProfile(
                "campus-ntp-subject", "AUDIT-CLOCK-BINDING-1.0.0", 3,
                NOW.minusSeconds(10), NOW.plusSeconds(60),
                "evidence://signed/clock/subject-registry.json");
        SubjectRegistryAuditEvent audit = new SubjectRegistryAuditEvent(
                "subject-mapping.ingest", "mapped", mapping.mappingId(), 1,
                "ast_v1_k1_" + "a".repeat(64), "192.0.2.10", TRACE, NOW, profile, null);
        store.saveIngest(new IngestCommit(
                Optional.of(mapping), Optional.empty(),
                new ProtectedIdentifierMaterial(
                        token, "aesgcm-v1:AAAAAAAAAAAAAAAA:BBBBBBBBBBBBBBBBBBBBBBBB",
                        "SOURCE_NATIVE_IDENTIFIER"),
                "wm-store-001", audit));

        assertEquals(List.of(student), store.findAuthorityCandidates(token, NOW));
        assertTrue(store.identifierPreviouslyIssued(key));
        assertEquals(student, store.timeline(key).resolve(key, NOW).studentRef().orElseThrow());
    }

    @Test
    void actualOnlineLoginCannotBypassFunctionsWithRawDml() {
        JdbcTemplate online = new JdbcTemplate(onlineDataSource());
        assertTrue(Boolean.TRUE.equals(online.queryForObject(
                "select session_user=current_user", Boolean.class)));
        assertThrows(DataAccessException.class, () -> online.update("""
                insert into subject_registry.sr_student_ref_reservation
                  (student_ref, authority_source_id, issued_at, status)
                values (?, 'SRC-P0-STUDENT-001', ?, 'active')
                """, STUDENT_A, Timestamp.from(NOW)));

        issue(online, STUDENT_A, uuid("019fcfea-6000-7000-8000-000000000011"),
                uuid("019fcfea-6000-7000-8000-000000000012"));
        recordException(
                online, EXCEPTION_ID, IDENTIFIER_ID,
                uuid("019fcfea-6000-7000-8000-000000000013"),
                uuid("019fcfea-6000-7000-8000-000000000014"));

        assertThrows(DataAccessException.class, () -> online.update("""
                update subject_registry.sr_mapping_exception
                   set status='resolved', aggregate_version=99
                 where exception_id=?
                """, EXCEPTION_ID));
        assertThrows(DataAccessException.class, () -> online.update(
                "delete from subject_registry.sr_student_ref_reservation where student_ref=?",
                STUDENT_A));
        assertEquals("open", admin.queryForObject("""
                select status from subject_registry.sr_mapping_exception
                 where exception_id=?
                """, String.class, EXCEPTION_ID));
    }

    @Test
    void repairIsAtomicCasAndServerScopedIdempotent() {
        JdbcTemplate online = new JdbcTemplate(onlineDataSource());
        seedRepair(online);
        UUID requestId = uuid("019fcfea-6000-7000-8000-000000000023");

        JsonNode first = repair(
                online, EXCEPTION_ID, 1, "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                uuid("019fcfea-6000-7000-8000-000000000021"),
                uuid("019fcfea-6000-7000-8000-000000000022"),
                requestId,
                uuid("019fcfea-6000-7000-8000-000000000024"),
                uuid("019fcfea-6000-7000-8000-000000000025"));
        JsonNode replay = repair(
                online, EXCEPTION_ID, 1, "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                uuid("019fcfea-6000-7000-8000-000000000021"),
                uuid("019fcfea-6000-7000-8000-000000000022"),
                requestId,
                uuid("019fcfea-6000-7000-8000-000000000024"),
                uuid("019fcfea-6000-7000-8000-000000000025"));

        assertEquals(first, replay);
        assertEquals("resolved", first.get("status").asText());
        assertEquals(3, first.get("aggregateVersion").asLong());
        assertEquals(1, count("sr_correction_event"));
        assertEquals(1, count("sr_mapping_recompute_outbox"));
        assertEquals(1, count("sr_repair_idempotency"));
        assertEquals(4, count("sr_local_audit_fact"), "two issuance + exception + repair uses four facts");
        JsonNode event = json(admin.queryForObject("""
                select payload::text
                  from subject_registry.sr_mapping_recompute_outbox
                 where request_id=?
                """, String.class, requestId));
        assertEquals("1.0", event.get("specversion").asText());
        assertEquals("cn.edu.suda.scholarsense.subject-mapping.changed.v1",
                event.get("type").asText());
        assertEquals(requestId.toString(), event.get("id").asText());
        assertEquals("SRC-P0-CARD-001", event.get("data").get("sourceId").asText());
        assertEquals("wm-repair-001", event.get("data").get("sourceWatermark").asText());
        assertEquals(1, event.get("data").get("aggregateVersion").asLong());
        assertEquals("019fcfea-6000-7000-8000-000000000022",
                event.get("data").get("aggregateId").asText());
        assertEquals(2, event.get("data").get("affectedStudentRefs").size());
        assertEquals(2, admin.queryForObject("""
                select affected_student_ref_count
                  from subject_registry.sr_mapping_recompute_outbox
                 where request_id=?
                """, Integer.class, requestId));
        var pending = store(online).findPendingRequest(requestId).orElseThrow();
        assertEquals(requestId, pending.requestId());
        assertEquals("SRC-P0-CARD-001", pending.ownerSourceId());
        assertEquals(NOW, pending.queuedAt());
        assertEquals(TRACE, pending.traceId());
        DataSource relayDataSource = relayDataSource();
        var relay = new JdbcSubjectMappingEventRelayStore(
                new JdbcTemplate(relayDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(relayDataSource)),
                new tools.jackson.databind.ObjectMapper());
        var claims = relay.claimDue(100, NOW, Duration.ofSeconds(60));
        assertEquals(1, claims.size());
        assertEquals(requestId, claims.getFirst().event().eventId());
        assertEquals("wm-repair-001", claims.getFirst().event().sourceWatermark());
        assertTrue(relay.confirm(requestId, claims.getFirst().attempts(), NOW.plusSeconds(1)));
        assertEquals("delivered", admin.queryForObject("""
                select status from subject_registry.sr_mapping_recompute_outbox
                 where request_id=?
                """, String.class, requestId));

        assertThrows(DataAccessException.class, () -> repair(
                online, EXCEPTION_ID, 1, "sha256:" + "1".repeat(64),
                "sha256:" + "9".repeat(64),
                uuid("019fcfea-6000-7000-8000-000000000021"),
                uuid("019fcfea-6000-7000-8000-000000000022"),
                uuid("019fcfea-6000-7000-8000-000000000023"),
                uuid("019fcfea-6000-7000-8000-000000000024"),
                uuid("019fcfea-6000-7000-8000-000000000025")));
    }

    @Test
    void relayQuarantinesACommittedEventWhoseSelfContainedPayloadLosesIntegrity() {
        JdbcTemplate online = new JdbcTemplate(onlineDataSource());
        seedRepair(online);
        UUID requestId = uuid("019fcfea-6000-7000-8000-000000000026");
        repair(
                online, EXCEPTION_ID, 1, "sha256:" + "5".repeat(64),
                "sha256:" + "6".repeat(64),
                uuid("019fcfea-6000-7000-8000-000000000027"),
                uuid("019fcfea-6000-7000-8000-000000000028"), requestId,
                uuid("019fcfea-6000-7000-8000-000000000029"),
                uuid("019fcfea-6000-7000-8000-000000000030"));
        admin.update("""
                update subject_registry.sr_mapping_recompute_outbox
                   set payload='{}'::jsonb where request_id=?
                """, requestId);

        DataSource relayDataSource = relayDataSource();
        var relay = new JdbcSubjectMappingEventRelayStore(
                new JdbcTemplate(relayDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(relayDataSource)),
                new tools.jackson.databind.ObjectMapper());
        assertTrue(relay.claimDue(100, NOW, Duration.ofSeconds(60)).isEmpty());
        assertEquals("failed", admin.queryForObject("""
                select status from subject_registry.sr_mapping_recompute_outbox
                 where request_id=?
                """, String.class, requestId));
        assertEquals("SUBJECT_MAPPING_EVENT_INTEGRITY_INVALID", admin.queryForObject("""
                select last_error_code from subject_registry.sr_mapping_recompute_outbox
                 where request_id=?
                """, String.class, requestId));
        assertEquals(1L, admin.queryForObject("""
                select attempts from subject_registry.sr_mapping_recompute_outbox
                 where request_id=?
                """, Long.class, requestId));
    }

    @Test
    void auditFailureRollsBackExceptionCorrectionOutboxAndIdempotency() {
        JdbcTemplate online = new JdbcTemplate(onlineDataSource());
        UUID exceptionId = uuid("019fcfea-6000-7000-8000-000000000031");
        UUID identifierId = uuid("019fcfea-6000-7000-8000-000000000032");
        seedRepair(online, exceptionId, identifierId);
        UUID duplicateAuditId = uuid("019fcfea-6000-7000-8000-000000000033");
        admin.update("""
                insert into subject_registry.sr_local_audit_fact
                  (audit_id, actor_search_token, action, result_code, object_id,
                   aggregate_version, trace_id, occurred_at, authorization_context,
                   expires_at)
values (?, ?, 'seed.audit', 'accepted', ?, 1, ?, ?, '{}'::jsonb,
        cast(? as timestamptz) + interval '3 years')
                """, duplicateAuditId, "ast_v1_k1_" + "a".repeat(64), exceptionId,
                TRACE, Timestamp.from(NOW), Timestamp.from(NOW));
        int correctionBefore = count("sr_correction_event");

        assertThrows(DataAccessException.class, () -> repair(
                online, exceptionId, 1,
                "sha256:" + "3".repeat(64), "sha256:" + "4".repeat(64),
                uuid("019fcfea-6000-7000-8000-000000000034"),
                uuid("019fcfea-6000-7000-8000-000000000035"),
                uuid("019fcfea-6000-7000-8000-000000000036"),
                duplicateAuditId,
                uuid("019fcfea-6000-7000-8000-000000000037")));

        assertEquals("open", admin.queryForObject(
                "select status from subject_registry.sr_mapping_exception where exception_id=?",
                String.class, exceptionId));
        assertEquals(correctionBefore, count("sr_correction_event"));
        assertEquals(0, admin.queryForObject("""
                select count(*) from subject_registry.sr_repair_idempotency
                 where idempotency_scope_digest=?
                """, Integer.class, "sha256:" + "3".repeat(64)));
    }

    private void seedRepair(JdbcTemplate online) {
        seedRepair(online, EXCEPTION_ID, IDENTIFIER_ID);
    }

    private void seedRepair(JdbcTemplate online, UUID exceptionId, UUID identifierId) {
        issue(online, STUDENT_A,
                nextEvidenceUuid(), nextEvidenceUuid());
        issue(online, STUDENT_B,
                nextEvidenceUuid(), nextEvidenceUuid());
        recordException(online, exceptionId, identifierId,
                nextEvidenceUuid(), nextEvidenceUuid());
    }

    private void issue(JdbcTemplate online, UUID studentRef, UUID auditId, UUID outboxId) {
        online.queryForObject("""
                select subject_registry.sr_issue_student_ref(
                  ?, 'SRC-P0-STUDENT-001', ?, ?, ?, ?, ?)
                """, Boolean.class, studentRef, Timestamp.from(NOW), auditId, outboxId,
                "ast_v1_k1_" + "a".repeat(64), TRACE);
    }

    private void recordException(
            JdbcTemplate online, UUID exceptionId, UUID identifierId,
            UUID auditId, UUID outboxId) {
        online.queryForObject("""
                select subject_registry.sr_record_mapping_exception(
                  ?, ?, 'SRC-P0-CARD-001', 'card-number',
                  'prod', 'kms://subject-registry/identifier-bundle', 'v1',
                  ?, 'aesgcm-v1:AAAAAAAAAAAAAAAA:BBBBBBBBBBBBBBBBBBBBBBBB',
                  'SOURCE_NATIVE_IDENTIFIER', ?, 'prod',
                  'kms://subject-registry/identifier-bundle', 'v1', ?,
                  'aesgcm-v1:CCCCCCCCCCCCCCCC:DDDDDDDDDDDDDDDDDDDDDDDD',
                  'ambiguous', '一卡通数据 owner',
                  ?, ?, ?, ?, ?)
                """, Boolean.class, identifierId, exceptionId,
                "hmac-sha256:" + "b".repeat(64), nextEvidenceUuid(),
                "hmac-sha256:" + "c".repeat(64), Timestamp.from(NOW), auditId, outboxId,
                "ast_v1_k1_" + "a".repeat(64), TRACE);
    }

    private void recordMapping(
            JdbcTemplate online, UUID studentRef, UUID identifierId,
            UUID mappingId, UUID aggregateId, Instant from, Instant to, long version) {
        online.queryForObject("""
                select subject_registry.sr_record_subject_mapping(
                  ?, ?, ?, ?, 'SRC-P0-STUDENT-001', 'student-number',
                  'prod', 'kms://subject-registry/identifier-bundle', 'v1', ?,
                  'aesgcm-v1:AAAAAAAAAAAAAAAA:BBBBBBBBBBBBBBBBBBBBBBBB',
                  'SOURCE_NATIVE_IDENTIFIER', ?, ?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class, identifierId, mappingId, aggregateId, studentRef,
                "hmac-sha256:" + "d".repeat(64), Timestamp.from(from), Timestamp.from(to),
                version, Timestamp.from(NOW), nextEvidenceUuid(), nextEvidenceUuid(),
                "ast_v1_k1_" + "a".repeat(64), TRACE);
    }

    private JsonNode repair(
            JdbcTemplate online, UUID exceptionId, long expectedVersion,
            String scopeDigest, String requestDigest,
            UUID eventId, UUID lineageId, UUID requestId, UUID auditId, UUID auditOutboxId) {
        String json = online.queryForObject("""
                select subject_registry.sr_repair_mapping_exception(
                  ?, ?, ?, ?, ?, ?, 'correct', 'alias', ?, array[?]::uuid[],
                  'AUTHORITY_CORRECTION', ?, ?, 'wm-repair-001', ?, ?, ?, ?
                )::text
                """, String.class, exceptionId, expectedVersion, scopeDigest, requestDigest,
                eventId, lineageId, STUDENT_A, STUDENT_B, Timestamp.from(NOW), requestId,
                auditId, auditOutboxId, "ast_v1_k1_" + "a".repeat(64), TRACE);
        try {
            return new tools.jackson.databind.ObjectMapper().readTree(json);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private boolean functionPrivilege(String function) {
        return Boolean.TRUE.equals(admin.queryForObject("""
                select count(*)=1 and bool_and(has_function_privilege(
                         'scholarsense_subject_registry_online', procedure.oid, 'EXECUTE'))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='subject_registry' and procedure.proname=?
                """, Boolean.class, function));
    }

    private int count(String table) {
        return admin.queryForObject(
                "select count(*) from subject_registry." + table, Integer.class);
    }

    private JsonNode json(String value) {
        try {
            return new tools.jackson.databind.ObjectMapper().readTree(value);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private JdbcSubjectRegistryStore store(JdbcTemplate online) {
        return new JdbcSubjectRegistryStore(
                online, new tools.jackson.databind.ObjectMapper(), new SubjectRegistryIdPort() {
                    @Override public UUID nextUuid() { return nextEvidenceUuid(); }
                    @Override public StudentRef nextStudentRef() {
                        return StudentRef.of(nextEvidenceUuid());
                    }
                });
    }

    private void ensureOnlineLogin() {
        admin.execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_sr_online_test_login') then
                        create role scholarsense_sr_online_test_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_sr_online_test_login
                    login inherit nosuperuser nocreatedb nocreaterole
                    noreplication nobypassrls;
                revoke scholarsense_subject_registry_online
                    from scholarsense_sr_online_test_login;
                grant scholarsense_subject_registry_online
                    to scholarsense_sr_online_test_login
                    with inherit true, set false;
                """);
    }

    private void ensureRelayLogin() {
        admin.execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_sr_relay_test_login') then
                        create role scholarsense_sr_relay_test_login login inherit;
                    end if;
                end
                $role_test$;
                alter role scholarsense_sr_relay_test_login
                    login inherit nosuperuser nocreatedb nocreaterole
                    noreplication nobypassrls;
                revoke scholarsense_subject_registry_relay
                    from scholarsense_sr_relay_test_login;
                grant scholarsense_subject_registry_relay
                    to scholarsense_sr_relay_test_login
                    with inherit true, set false;
                """);
    }

    private DataSource adminDataSource() {
        return dataSource(required("scholarsense.audit.pg.user"));
    }

    private DataSource onlineDataSource() {
        return dataSource(ONLINE_LOGIN);
    }

    private DataSource relayDataSource() {
        return dataSource(RELAY_LOGIN);
    }

    private DataSource dataSource(String username) {
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

    private static long evidenceSequence = 500;

    private static synchronized UUID nextEvidenceUuid() {
        return UUID.fromString(String.format(
                "019fcfea-6000-7000-8000-%012d", evidenceSequence++));
    }
}
