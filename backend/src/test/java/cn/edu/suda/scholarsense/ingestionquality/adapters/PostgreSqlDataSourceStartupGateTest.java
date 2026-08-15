package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgreSqlDataSourceStartupGateTest {
    private static final String PRODUCTION_URL =
            "jdbc:postgresql://db.example.invalid/scholarsense"
                    + "?sslmode=verify-full&channelBinding=require";
    private static final Pattern SQL_TUPLE = Pattern.compile(
            "\\('([^']*)'(?:,'([^']*)')?(?:,'([^']*)')?\\)");

    private static final Matrix ONLINE_MATRIX = new Matrix(
            set(
                    "iq_data_source_catalog|SELECT",
                    "iq_source_id_reservation|SELECT",
                    "iq_dependency_id_reservation|SELECT",
                    "iq_source_contract|SELECT",
                    "iq_dependency_binding|SELECT",
                    "iq_catalog_validation_attempt|SELECT",
                    "iq_catalog_evidence|SELECT",
                    "iq_catalog_current|SELECT",
                    "iq_catalog_idempotency|SELECT",
                    "iq_local_audit_outbox|SELECT",
                    "iq_local_audit_outbox|INSERT",
                    "iq_historical_window|SELECT",
                    "iq_subject_mapping_consumer_cursor|SELECT",
                    "iq_mapping_recompute_request|SELECT",
                    "iq_mapping_recompute_job|SELECT",
                    "iq_mapping_recompute_result|SELECT"),
            set(
                    "iq_data_source_catalog|catalog_id|INSERT",
                    "iq_data_source_catalog|catalog_release_id|INSERT",
                    "iq_data_source_catalog|contract_version|INSERT",
                    "iq_data_source_catalog|status|INSERT",
                    "iq_data_source_catalog|aggregate_version|INSERT",
                    "iq_data_source_catalog|content_digest|INSERT",
                    "iq_data_source_catalog|evidence_set_digest|INSERT",
                    "iq_data_source_catalog|validation_errors|INSERT",
                    "iq_data_source_catalog|created_at|INSERT",
                    "iq_data_source_catalog|updated_at|INSERT",
                    "iq_data_source_catalog|published_at|INSERT",
                    "iq_data_source_catalog|expires_at|INSERT",
                    "iq_catalog_idempotency|idempotency_key_digest|INSERT",
                    "iq_catalog_idempotency|request_digest|INSERT",
                    "iq_catalog_idempotency|catalog_id|INSERT",
                    "iq_catalog_idempotency|response|INSERT",
                    "iq_catalog_idempotency|created_at|INSERT",
                    "iq_catalog_idempotency|expires_at|INSERT",
                    "iq_local_audit_fact|audit_id|INSERT",
                    "iq_local_audit_fact|actor_search_token|INSERT",
                    "iq_local_audit_fact|action|INSERT",
                    "iq_local_audit_fact|result|INSERT",
                    "iq_local_audit_fact|catalog_id|INSERT",
                    "iq_local_audit_fact|aggregate_version|INSERT",
                    "iq_local_audit_fact|trace_id|INSERT",
                    "iq_local_audit_fact|occurred_at|INSERT",
                    "iq_local_audit_fact|authorization_context|INSERT",
                    "iq_local_audit_fact|expires_at|INSERT",
                    "iq_catalog_idempotency|request_digest|UPDATE",
                    "iq_catalog_idempotency|catalog_id|UPDATE",
                    "iq_catalog_idempotency|response|UPDATE",
                    "iq_catalog_idempotency|created_at|UPDATE",
                    "iq_catalog_idempotency|expires_at|UPDATE"),
            set(
                    "iq_add_catalog_source(uuid, character varying, character varying, "
                            + "character varying, character varying, character varying, "
                            + "character varying, jsonb)",
                    "iq_add_catalog_dependency(uuid, character varying, character varying, "
                            + "character varying, character varying)",
                    "iq_record_catalog_validation(uuid, bigint, character varying, bigint, "
                            + "jsonb, timestamp with time zone, uuid, character varying)",
                    "iq_publish_catalog(uuid, bigint, bigint, uuid, character varying, "
                            + "timestamp with time zone, bigint, jsonb)",
                    "iq_record_historical_window(character varying, uuid, "
                            + "timestamp with time zone, timestamp with time zone, "
                            + "character varying, jsonb, jsonb, bigint, jsonb, "
                            + "character varying, character varying, character varying, "
                            + "uuid, character, timestamp with time zone, timestamp with time zone)",
                    "iq_accept_subject_mapping_event(character varying, character varying, "
                            + "uuid, uuid, bigint, timestamp with time zone, uuid, "
                            + "character varying, uuid[], character varying, character varying, "
                            + "boolean, timestamp with time zone)",
                    "iq_reconcile_subject_mapping_consumer(character varying, uuid, bigint, "
                            + "timestamp with time zone)",
                    "iq_enqueue_mapping_recompute(uuid, uuid, character varying, uuid, "
                            + "character varying, character varying, character varying, "
                            + "character varying, character, timestamp with time zone, "
                            + "timestamp with time zone, character)",
                    "iq_enqueue_mapping_recompute_v2(uuid, uuid, character varying, uuid, "
                            + "character varying, character varying, character varying, "
                            + "character varying, character, timestamp with time zone, "
                            + "timestamp with time zone, character, character varying)",
                    "iq_record_mapping_recompute_plan(uuid, uuid, character varying, integer, "
                            + "integer, timestamp with time zone, character)",
                    "iq_claim_mapping_recompute_job(uuid, character varying, "
                            + "timestamp with time zone, timestamp with time zone)",
                    "iq_checkpoint_mapping_recompute_job(uuid, bigint, bigint, "
                            + "timestamp with time zone)",
                    "iq_complete_mapping_recompute_job(uuid, bigint, timestamp with time zone, uuid)",
                    "iq_fail_mapping_recompute_job(uuid, bigint, timestamp with time zone, "
                            + "character varying)",
                    "iq_requeue_mapping_recompute_job(uuid, bigint, timestamp with time zone)",
                    "iq_cancel_mapping_recompute_job(uuid, bigint, timestamp with time zone)",
                    "iq_find_assessed_quality_snapshot_ids(character varying, character varying, "
                            + "timestamp with time zone, timestamp with time zone, "
                            + "character varying, character varying, timestamp with time zone, "
                            + "uuid, integer)",
                    "iq_find_assessed_quality_snapshot(uuid)",
                    "iq_find_assessed_quality_snapshot_metrics(uuid)",
                    "iq_find_assessed_quality_snapshot_impact_scopes(uuid)",
                    "iq_find_assessed_quality_snapshot_page(uuid[])",
                    "iq_find_assessed_quality_snapshot_page_metrics(uuid[])",
                    "iq_find_assessed_quality_snapshot_page_impact_scopes(uuid[])",
                    "iq_resolve_quality_snapshot_source(character)",
                    "iq_append_quality_snapshot_read_audit(uuid, uuid, uuid, character varying, "
                            + "character varying, character varying, character varying, "
                            + "character varying, bigint, character, timestamp with time zone, "
                            + "jsonb, character)",
                    "iq_find_quality_eligibility_ids(character varying, character varying, "
                            + "timestamp with time zone, uuid, integer)",
                    "iq_find_quality_eligibility_page(uuid[])",
                    "iq_find_quality_eligibility_page_members(uuid[])",
                    "iq_resolve_quality_eligibility_source(uuid)",
                    "iq_append_quality_eligibility_read_audit(uuid, uuid, bigint, "
                            + "character varying, character varying, character varying, "
                            + "character varying, character varying, character, "
                            + "timestamp with time zone, character)",
                    "iq_find_quality_recovery_task_ids(character varying, character varying, "
                            + "timestamp with time zone, uuid, integer)",
                    "iq_find_quality_recovery_task_page(uuid[])",
                    "iq_find_quality_recovery_task_ids_v2(character varying, character varying, "
                            + "timestamp with time zone, uuid, integer)",
                    "iq_find_quality_recovery_task_page_v2(uuid[])",
                    "iq_find_quality_recovery_task_page_rules(uuid[])",
                    "iq_append_quality_recovery_task_read_audit(uuid, uuid, bigint, "
                            + "character varying, character varying, character varying, "
                            + "character, timestamp with time zone, character)",
                    "iq_submit_quality_recovery_request(character, jsonb)",
                    "iq_submit_quality_recovery_with_validation(character, jsonb, character, uuid)",
                    "iq_submit_recovery_validation_job(character, jsonb)",
                    "iq_resolve_quality_recovery_task_owner(character)",
                    "iq_load_quality_recovery_command_context(uuid)",
                    "iq_find_quality_recovery_request(uuid)",
                    "iq_find_quality_recovery_idempotency(character, character, uuid)",
                    "iq_store_quality_recovery_evidence(jsonb, jsonb, timestamp with time zone)",
                    "iq_bind_quality_recovery_approval(character, character, jsonb, "
                            + "timestamp with time zone)",
                    "iq_build_quality_recovery_readiness_evidence(uuid, timestamp with time zone)",
                    "iq_execute_quality_recovery(character, jsonb)",
                    "iq_claim_quality_recovery_confirmations(integer, timestamp with time zone)",
                    "iq_mark_quality_recovery_confirmation_delivered(uuid, character, "
                            + "timestamp with time zone)",
                    "iq_release_quality_recovery_confirmation(uuid, character, "
                            + "character varying, timestamp with time zone)",
                    "iq_start_recovery_observation(jsonb)",
                    "iq_load_recovery_observation_view(uuid)",
                    "iq_load_quality_finalization_context(uuid)",
                    "iq_bind_quality_finalization_approval(jsonb)",
                    "iq_find_quality_finalization_replay(character, character, uuid, text)",
                    "iq_execute_quality_finalization(character, jsonb)"));

    private static final Matrix QUALITY_WORKER_MATRIX = new Matrix(
            set(
                    "iq_data_batch|SELECT",
                    "iq_normalized_fact|SELECT",
                    "iq_batch_quality_measurement|SELECT",
                    "iq_batch_quality_operand|SELECT",
                    "iq_batch_quality_impact_scope|SELECT",
                    "iq_quality_snapshot|SELECT",
                    "iq_quality_snapshot_metric|SELECT",
                    "iq_quality_snapshot_impact_scope|SELECT",
                    "iq_batch_idempotency|SELECT"),
            Set.of(),
            set(
                    "iq_receive_data_batch(uuid, uuid, character varying, bytea, bigint, uuid, uuid, "
                            + "character varying, timestamp with time zone, character, "
                            + "timestamp with time zone, character, character, character, jsonb, "
                            + "character)",
                    "iq_inspect_batch_command_precedence(character, character varying, character)",
                    "iq_append_normalized_fact(uuid, bytea, character varying, bytea, bigint, "
                            + "character varying, character, uuid, character, "
                            + "timestamp with time zone)",
                    "iq_record_batch_quality_measurement(uuid, character varying, integer, boolean, "
                            + "jsonb, character, timestamp with time zone)",
                    "iq_record_batch_quality_impact_scope(uuid, bytea, timestamp with time zone)",
                    "iq_seal_data_batch(uuid, uuid, bigint, bigint, bigint, bigint, "
                            + "timestamp with time zone, timestamp with time zone, "
                            + "timestamp with time zone, character varying, bytea, "
                            + "character varying, character, character varying, character, "
                            + "character varying, character, character varying, character, "
                            + "timestamp with time zone, timestamp with time zone, "
                            + "timestamp with time zone, character varying, character, jsonb, "
                            + "timestamp with time zone, character, character, character, jsonb, "
                            + "character)",
                    "iq_commit_batch_quality_evaluation(uuid, uuid, bigint, uuid, character varying, "
                            + "character varying, character varying, character varying, "
                            + "timestamp with time zone, character, character, "
                            + "character, jsonb, character, character, jsonb, character, uuid, "
                            + "character varying, character varying, bytea, character)",
                    "iq_publish_data_batch(uuid, uuid, bigint, timestamp with time zone, character, "
                            + "character, character, jsonb, character, uuid, character varying, "
                            + "character varying, bytea, character)"));

    private static final Matrix RELAY_MATRIX = new Matrix(
            set(
                    "iq_local_audit_fact|SELECT",
                    "iq_local_audit_outbox|SELECT",
                    "iq_mapping_recompute_outbox|SELECT",
                    "iq_batch_quality_outbox|SELECT"),
            set(
                    "iq_local_audit_outbox|status|UPDATE",
                    "iq_local_audit_outbox|attempts|UPDATE",
                    "iq_local_audit_outbox|available_at|UPDATE",
                    "iq_local_audit_outbox|claimed_until|UPDATE",
                    "iq_local_audit_outbox|delivered_at|UPDATE",
                    "iq_local_audit_outbox|last_error_code|UPDATE",
                    "iq_mapping_recompute_outbox|status|UPDATE",
                    "iq_mapping_recompute_outbox|attempts|UPDATE",
                    "iq_mapping_recompute_outbox|available_at|UPDATE",
                    "iq_mapping_recompute_outbox|claimed_until|UPDATE",
                    "iq_mapping_recompute_outbox|delivered_at|UPDATE",
                    "iq_mapping_recompute_outbox|last_error_code|UPDATE"),
            set(
                    "iq_claim_next_batch_quality_outbox()",
                    "iq_release_batch_quality_outbox(uuid, bigint)",
                    "iq_deliver_batch_quality_outbox(uuid, bigint)",
                    "iq_fail_batch_quality_outbox(uuid, bigint)",
                    "iq_claim_next_quality_eligibility_outbox()",
                    "iq_release_quality_eligibility_outbox(uuid, bigint)",
                    "iq_deliver_quality_eligibility_outbox(uuid, bigint)",
                    "iq_fail_quality_eligibility_outbox(uuid, bigint)"));

    private static final Matrix RETENTION_MATRIX = new Matrix(
            Set.of(),
            Set.of(),
            set(
                    "iq_cleanup_expired(timestamp with time zone)",
                    "iq_find_next_due_quality_snapshot_retention()",
                    "iq_execute_quality_snapshot_retention(uuid, uuid, uuid, character, character, "
                            + "uuid, character)",
                    "iq_cleanup_quality_eligibility_expired(timestamp with time zone)",
                    "iq_cleanup_quality_fuse_expired(timestamp with time zone)",
                    "iq_cleanup_quality_recovery_expired(timestamp with time zone)",
                    "iq_cleanup_quality_finalization_expired(timestamp with time zone)"));

    private static final Matrix CONSUMER_REGISTRY_AUTHORITY_MATRIX = new Matrix(
            Set.of(),
            Set.of(),
            set("iq_ingest_quality_snapshot_retention_authority(uuid, bytea, character)"));

    private static final Matrix ELIGIBILITY_CONSUMER_MATRIX = new Matrix(
            Set.of(),
            Set.of(),
            set(
                    "iq_find_quality_snapshot_evidence(uuid, uuid, character)",
                    "iq_find_quality_snapshot_evidence_v2(uuid, uuid, character)",
                    "iq_load_quality_eligibility_processing_state(uuid, character varying)",
                    "iq_load_quality_eligibility_processing_state_v2(uuid, character varying)",
                    "iq_accept_quality_eligibility_event(uuid, character varying, bigint, "
                            + "character, jsonb)",
                    "iq_accept_quality_eligibility_event_v2(uuid, character varying, bigint, "
                            + "character, jsonb)",
                    "iq_append_quality_fuse_rejection_audit(uuid, bigint, character varying, "
                            + "character)"));

    private static final Matrix TASK_RELAY_MATRIX = new Matrix(
            Set.of(),
            Set.of(),
                set(
                    "iq_claim_next_quality_task_outbox()",
                    "iq_authorize_quality_task_send(uuid, bigint, bigint)",
                    "iq_mark_quality_task_delivery_retry(uuid, bigint, character varying)",
                    "iq_complete_quality_task_delivery(uuid, bigint, character varying)",
                    "iq_fail_quality_task_delivery(uuid, bigint, character varying)"));

    @Test
    void sevenEntrypointsVerifyExactPrincipalAndPrivilegeContracts() throws Exception {
        for (Workload workload : Workload.values()) {
            String identity = workload.identity();
            Fixture fixture = fixture(Proof.exclusive(identity, workload));

            PostgreSqlConnectionProfile profile = verify(workload, fixture, identity);

            assertEquals(PRODUCTION_URL, profile.jdbcUrl(), workload.name());
            assertEquals(identity, profile.expectedWorkloadIdentity(), workload.name());
            String principal = fixture.principalQuery();
            assertTrue(principal.contains("current_setting('server_version_num')"));
            assertTrue(principal.contains("current_user"));
            assertTrue(principal.contains("session_user"));
            assertTrue(principal.contains("controlled.rolcanlogin"));
            assertTrue(principal.contains("'MEMBER'"));
            assertTrue(principal.contains("'USAGE'"));
            assertTrue(principal.contains("'SET'"));
            assertTrue(principal.contains("membership.admin_option"));
            assertTrue(principal.contains("with recursive reachable(roleid)"));
            assertFalse(principal.toLowerCase().contains("set role"));
            for (Workload controlled : Workload.values()) {
                assertTrue(principal.contains(controlled.groupRole()), controlled.name());
            }
            assertTrue(principal.contains(
                    "scholarsense_ingestion_quality_batch_owner"), workload.name());
            assertEquals(workload.matrix(), Matrix.parse(fixture.privilegeQuery()), workload.name());
            assertTrue(fixture.privilegeQuery()
                    .contains("tables.table_type in ('BASE TABLE','VIEW')"),
                    "unexpected view grants must participate in the exact relation matrix");
        }
    }

    @Test
    void productionStartupFailsWithoutChannelBindingOrForAConnectionIdentityMismatch()
            throws Exception {
        Fixture missingBinding = fixture(
                Proof.exclusive(Workload.ONLINE.identity(), Workload.ONLINE),
                "jdbc:postgresql://db.example.invalid/scholarsense?sslmode=verify-full");
        IllegalArgumentException channelBinding = assertThrows(
                IllegalArgumentException.class,
                () -> verify(Workload.ONLINE, missingBinding, Workload.ONLINE.identity()));
        assertEquals(
                "INGESTION_QUALITY_DATABASE_CHANNEL_BINDING_REQUIRED",
                channelBinding.getMessage());

        Fixture wrongIdentity = fixture(Proof.exclusive("unexpected_login", Workload.ONLINE));
        IllegalArgumentException identity = assertThrows(
                IllegalArgumentException.class,
                () -> verify(Workload.ONLINE, wrongIdentity, Workload.ONLINE.identity()));
        assertEquals("INGESTION_QUALITY_DATABASE_IDENTITY_MISMATCH", identity.getMessage());
    }

    @Test
    void everyEntrypointRejectsSetRoleSessionAndAnythingOtherThanPostgreSql180004()
            throws Exception {
        for (Workload workload : Workload.values()) {
            Proof valid = Proof.exclusive(workload.identity(), workload);
            Fixture changedRole = fixture(valid.withSessionUser("session_owner"));
            IllegalArgumentException session = assertThrows(
                    IllegalArgumentException.class,
                    () -> verify(workload, changedRole, workload.identity()));
            assertEquals(
                    "INGESTION_QUALITY_DATABASE_SESSION_IDENTITY_MISMATCH",
                    session.getMessage(),
                    workload.name());

            Fixture wrongVersion = fixture(valid.withVersion("180003"));
            IllegalArgumentException version = assertThrows(
                    IllegalArgumentException.class,
                    () -> verify(workload, wrongVersion, workload.identity()));
            assertEquals(
                    "INGESTION_QUALITY_DATABASE_SERVER_VERSION_MISMATCH",
                    version.getMessage(),
                    workload.name());
        }
    }

    @Test
    void everyEntrypointRejectsLoginElevationSetCapabilityAndCrossMembership()
            throws Exception {
        for (Workload workload : Workload.values()) {
            Proof valid = Proof.exclusive(workload.identity(), workload);
            Fixture elevated = fixture(valid.withRestrictions(false));
            IllegalArgumentException restriction = assertThrows(
                    IllegalArgumentException.class,
                    () -> verify(workload, elevated, workload.identity()));
            assertEquals(
                    "INGESTION_QUALITY_DATABASE_ROLE_RESTRICTION_MISMATCH",
                    restriction.getMessage(),
                    workload.name());

            Fixture setCapable = fixture(valid.withMembership(workload, true, true, true));
            IllegalArgumentException set = assertThrows(
                    IllegalArgumentException.class,
                    () -> verify(workload, setCapable, workload.identity()));
            assertEquals(
                    "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH",
                    set.getMessage(),
                    workload.name());

            Workload other = Workload.values()[(workload.ordinal() + 1) % Workload.values().length];
            Fixture crossMember = fixture(valid.withMembership(other, true, true, false));
            IllegalArgumentException cross = assertThrows(
                    IllegalArgumentException.class,
                    () -> verify(workload, crossMember, workload.identity()));
            assertEquals(
                    "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH",
                    cross.getMessage(),
                    workload.name());

            for (Proof ownerAccess : List.of(
                    valid.withOwnerMembership(true, true, false),
                    valid.withOwnerMembership(false, true, false),
                    valid.withOwnerMembership(false, false, true))) {
                Fixture ownerCapable = fixture(ownerAccess);
                IllegalArgumentException owner = assertThrows(
                        IllegalArgumentException.class,
                        () -> verify(workload, ownerCapable, workload.identity()));
                assertEquals(
                        "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH",
                        owner.getMessage(),
                        workload.name());
            }

            Fixture hiddenSetRole = fixture(valid.withMembershipGraph(false));
            IllegalArgumentException hidden = assertThrows(
                    IllegalArgumentException.class,
                    () -> verify(workload, hiddenSetRole, workload.identity()));
            assertEquals(
                    "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH",
                    hidden.getMessage(),
                    workload.name());
        }
    }

    @Test
    void everyEntrypointFailsClosedForAnyUnexpectedEffectivePrivilege() throws Exception {
        for (Workload workload : Workload.values()) {
            Proof drifted = Proof.exclusive(workload.identity(), workload).withMatrix(false);
            Fixture fixture = fixture(drifted);

            IllegalArgumentException privileges = assertThrows(
                    IllegalArgumentException.class,
                    () -> verify(workload, fixture, workload.identity()));

            assertEquals(
                    "INGESTION_QUALITY_DATABASE_PRIVILEGE_MATRIX_MISMATCH",
                    privileges.getMessage(),
                    workload.name());
        }
    }

    @Test
    void cleanupAuthorityMovesFromRelayToTheExclusiveRetentionExecutor() {
        assertFalse(RELAY_MATRIX.functions().contains(
                "iq_cleanup_expired(timestamp with time zone)"));
        assertFalse(RELAY_MATRIX.functions().stream()
                .anyMatch(function -> function.startsWith(
                        "iq_execute_quality_snapshot_retention(")));
        assertEquals(
                set(
                        "iq_cleanup_expired(timestamp with time zone)",
                        "iq_find_next_due_quality_snapshot_retention()",
                        "iq_execute_quality_snapshot_retention(uuid, uuid, uuid, character, "
                                + "character, uuid, character)",
                        "iq_cleanup_quality_eligibility_expired(timestamp with time zone)",
                        "iq_cleanup_quality_fuse_expired(timestamp with time zone)",
                        "iq_cleanup_quality_recovery_expired(timestamp with time zone)",
                        "iq_cleanup_quality_finalization_expired(timestamp with time zone)"),
                RETENTION_MATRIX.functions());
        assertEquals(
                set("iq_ingest_quality_snapshot_retention_authority(uuid, bytea, character)"),
                CONSUMER_REGISTRY_AUTHORITY_MATRIX.functions());
        assertFalse(RETENTION_MATRIX.functions().stream()
                .anyMatch(function -> function.startsWith(
                        "iq_ingest_quality_snapshot_retention_authority(")));
        assertFalse(CONSUMER_REGISTRY_AUTHORITY_MATRIX.functions().stream()
                .anyMatch(function -> function.startsWith(
                        "iq_execute_quality_snapshot_retention(")
                        || function.startsWith("iq_cleanup_expired(")
                        || function.startsWith(
                                "iq_find_next_due_quality_snapshot_retention(")));
    }

    @Test
    void batchQualityRelayUsesOnlyAttemptFencedTransitionFunctions() {
        assertTrue(RELAY_MATRIX.tablePrivileges().contains("iq_batch_quality_outbox|SELECT"));
        assertFalse(RELAY_MATRIX.columnPrivileges().stream()
                .anyMatch(privilege -> privilege.startsWith("iq_batch_quality_outbox|")));
        assertTrue(RELAY_MATRIX.functions().containsAll(set(
                "iq_claim_next_batch_quality_outbox()",
                "iq_release_batch_quality_outbox(uuid, bigint)",
                "iq_deliver_batch_quality_outbox(uuid, bigint)",
                "iq_fail_batch_quality_outbox(uuid, bigint)")));
    }

    private static PostgreSqlConnectionProfile verify(
            Workload workload, Fixture fixture, String identity) throws Exception {
        Method method = PostgreSqlDataSourceStartupGate.class.getMethod(
                workload.verifier(), DataSource.class, String.class, String.class);
        try {
            return (PostgreSqlConnectionProfile) method.invoke(
                    null, fixture.dataSource(), "prod", identity);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    private static Fixture fixture(Proof proof) throws Exception {
        return fixture(proof, PRODUCTION_URL);
    }

    private static Fixture fixture(Proof proof, String url) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet principal = mock(ResultSet.class);
        ResultSet matrix = mock(ResultSet.class);
        List<String> queries = new ArrayList<>();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(url);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            queries.add(sql);
            return sql.contains("expected_table") ? matrix : principal;
        });
        when(principal.next()).thenReturn(true, false);
        when(principal.getString(1)).thenReturn(proof.currentUser());
        when(principal.getString(2)).thenReturn(proof.sessionUser());
        when(principal.getString(3)).thenReturn(proof.serverVersion());
        when(principal.getBoolean(4)).thenReturn(proof.restrictedLogin());
        when(principal.getBoolean(5)).thenReturn(proof.onlineMember());
        when(principal.getBoolean(6)).thenReturn(proof.onlineUsage());
        when(principal.getBoolean(7)).thenReturn(proof.onlineSet());
        when(principal.getBoolean(8)).thenReturn(proof.qualityMember());
        when(principal.getBoolean(9)).thenReturn(proof.qualityUsage());
        when(principal.getBoolean(10)).thenReturn(proof.qualitySet());
        when(principal.getBoolean(11)).thenReturn(proof.relayMember());
        when(principal.getBoolean(12)).thenReturn(proof.relayUsage());
        when(principal.getBoolean(13)).thenReturn(proof.relaySet());
        when(principal.getBoolean(14)).thenReturn(proof.retentionMember());
        when(principal.getBoolean(15)).thenReturn(proof.retentionUsage());
        when(principal.getBoolean(16)).thenReturn(proof.retentionSet());
        when(principal.getBoolean(17)).thenReturn(proof.authorityMember());
        when(principal.getBoolean(18)).thenReturn(proof.authorityUsage());
        when(principal.getBoolean(19)).thenReturn(proof.authoritySet());
        when(principal.getBoolean(20)).thenReturn(proof.eligibilityMember());
        when(principal.getBoolean(21)).thenReturn(proof.eligibilityUsage());
        when(principal.getBoolean(22)).thenReturn(proof.eligibilitySet());
        when(principal.getBoolean(23)).thenReturn(proof.taskRelayMember());
        when(principal.getBoolean(24)).thenReturn(proof.taskRelayUsage());
        when(principal.getBoolean(25)).thenReturn(proof.taskRelaySet());
        when(principal.getBoolean(26)).thenReturn(proof.ownerMember());
        when(principal.getBoolean(27)).thenReturn(proof.ownerUsage());
        when(principal.getBoolean(28)).thenReturn(proof.ownerSet());
        when(principal.getBoolean(29)).thenReturn(proof.restrictedGroups());
        when(principal.getBoolean(30)).thenReturn(proof.membershipGraphExact());
        when(principal.getBoolean(31)).thenReturn(proof.membershipGraphExact());
        when(matrix.next()).thenReturn(true, false);
        when(matrix.getBoolean(1)).thenReturn(proof.matrixValid());
        return new Fixture(dataSource, queries);
    }

    private static Set<String> set(String... values) {
        return Set.of(values);
    }

    private record Fixture(DataSource dataSource, List<String> queries) {
        private String principalQuery() {
            return queries.stream()
                    .filter(query -> !query.contains("expected_table"))
                    .findFirst()
                    .orElseThrow();
        }

        private String privilegeQuery() {
            return queries.stream()
                    .filter(query -> query.contains("expected_table"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private record Matrix(
            Set<String> tablePrivileges,
            Set<String> columnPrivileges,
            Set<String> functions) {
        private static Matrix parse(String sql) {
            return new Matrix(
                    tuples(section(
                            sql,
                            "expected_table(table_name, privilege_type) as (",
                            "expected_column_extra")),
                    tuples(section(
                            sql,
                            "expected_column_extra(table_name, column_name, privilege_type) as (",
                            "actual_table")),
                    tuples(section(
                            sql,
                            "expected_function(function_signature) as (",
                            "actual_function")));
        }

        private static String section(String sql, String start, String end) {
            int startIndex = sql.indexOf(start);
            int endIndex = sql.indexOf(end, startIndex + start.length());
            if (startIndex < 0 || endIndex < 0) {
                throw new AssertionError("startup gate privilege query shape drifted");
            }
            return sql.substring(startIndex + start.length(), endIndex);
        }

        private static Set<String> tuples(String section) {
            Matcher matcher = SQL_TUPLE.matcher(section);
            Set<String> values = new LinkedHashSet<>();
            while (matcher.find()) {
                StringBuilder value = new StringBuilder(matcher.group(1));
                if (matcher.group(2) != null) value.append('|').append(matcher.group(2));
                if (matcher.group(3) != null) value.append('|').append(matcher.group(3));
                values.add(value.toString());
            }
            return Set.copyOf(values);
        }
    }

    private enum Workload {
        ONLINE(
                "verifyOnline",
                "scholarsense_ingestion_quality_online",
                "scholarsense_web_prod",
                ONLINE_MATRIX),
        QUALITY_WORKER(
                "verifyQualityWorker",
                "scholarsense_ingestion_quality_quality_worker",
                "scholarsense_quality_worker_prod",
                QUALITY_WORKER_MATRIX),
        RELAY(
                "verifyRelay",
                "scholarsense_ingestion_quality_relay",
                "scholarsense_relay_prod",
                RELAY_MATRIX),
        RETENTION_EXECUTOR(
                "verifyRetentionExecutor",
                "scholarsense_ingestion_quality_retention_executor",
                "scholarsense_retention_prod",
                RETENTION_MATRIX),
        CONSUMER_REGISTRY_AUTHORITY(
                "verifyConsumerRegistryAuthority",
                "scholarsense_ingestion_quality_consumer_registry_authority",
                "scholarsense_consumer_registry_authority_prod",
                CONSUMER_REGISTRY_AUTHORITY_MATRIX),
        ELIGIBILITY_CONSUMER(
                "verifyEligibilityConsumer",
                "scholarsense_ingestion_quality_eligibility_consumer",
                "scholarsense_eligibility_consumer_prod",
                ELIGIBILITY_CONSUMER_MATRIX),
        TASK_RELAY(
                "verifyTaskRelay",
                "scholarsense_ingestion_quality_task_relay",
                "scholarsense_task_relay_prod",
                TASK_RELAY_MATRIX);

        private final String verifier;
        private final String groupRole;
        private final String identity;
        private final Matrix matrix;

        Workload(String verifier, String groupRole, String identity, Matrix matrix) {
            this.verifier = verifier;
            this.groupRole = groupRole;
            this.identity = identity;
            this.matrix = matrix;
        }

        private String verifier() {
            return verifier;
        }

        private String groupRole() {
            return groupRole;
        }

        private String identity() {
            return identity;
        }

        private Matrix matrix() {
            return matrix;
        }
    }

    private record Proof(
            String currentUser,
            String sessionUser,
            String serverVersion,
            boolean restrictedLogin,
            boolean onlineMember,
            boolean onlineUsage,
            boolean onlineSet,
            boolean qualityMember,
            boolean qualityUsage,
            boolean qualitySet,
            boolean relayMember,
            boolean relayUsage,
            boolean relaySet,
            boolean retentionMember,
            boolean retentionUsage,
            boolean retentionSet,
            boolean authorityMember,
            boolean authorityUsage,
            boolean authoritySet,
            boolean eligibilityMember,
            boolean eligibilityUsage,
            boolean eligibilitySet,
            boolean taskRelayMember,
            boolean taskRelayUsage,
            boolean taskRelaySet,
            boolean ownerMember,
            boolean ownerUsage,
            boolean ownerSet,
            boolean restrictedGroups,
            boolean membershipGraphExact,
            boolean matrixValid) {
        private static Proof exclusive(String identity, Workload workload) {
            Proof empty = new Proof(
                    identity, identity, "180004", true,
                    false, false, false,
                    false, false, false,
                    false, false, false,
                    false, false, false,
                    false, false, false,
                    false, false, false,
                    false, false, false,
                    false, false, false,
                    true, true, true);
            return empty.withMembership(workload, true, true, false);
        }

        private Proof withSessionUser(String value) {
            return new Proof(
                    currentUser, value, serverVersion, restrictedLogin,
                    onlineMember, onlineUsage, onlineSet,
                    qualityMember, qualityUsage, qualitySet,
                    relayMember, relayUsage, relaySet,
                    retentionMember, retentionUsage, retentionSet,
                    authorityMember, authorityUsage, authoritySet,
                    eligibilityMember, eligibilityUsage, eligibilitySet,
                    taskRelayMember, taskRelayUsage, taskRelaySet,
                    ownerMember, ownerUsage, ownerSet,
                    restrictedGroups, membershipGraphExact, matrixValid);
        }

        private Proof withVersion(String value) {
            return new Proof(
                    currentUser, sessionUser, value, restrictedLogin,
                    onlineMember, onlineUsage, onlineSet,
                    qualityMember, qualityUsage, qualitySet,
                    relayMember, relayUsage, relaySet,
                    retentionMember, retentionUsage, retentionSet,
                    authorityMember, authorityUsage, authoritySet,
                    eligibilityMember, eligibilityUsage, eligibilitySet,
                    taskRelayMember, taskRelayUsage, taskRelaySet,
                    ownerMember, ownerUsage, ownerSet,
                    restrictedGroups, membershipGraphExact, matrixValid);
        }

        private Proof withRestrictions(boolean value) {
            return new Proof(
                    currentUser, sessionUser, serverVersion, value,
                    onlineMember, onlineUsage, onlineSet,
                    qualityMember, qualityUsage, qualitySet,
                    relayMember, relayUsage, relaySet,
                    retentionMember, retentionUsage, retentionSet,
                    authorityMember, authorityUsage, authoritySet,
                    eligibilityMember, eligibilityUsage, eligibilitySet,
                    taskRelayMember, taskRelayUsage, taskRelaySet,
                    ownerMember, ownerUsage, ownerSet,
                    value, membershipGraphExact, matrixValid);
        }

        private Proof withMatrix(boolean value) {
            return new Proof(
                    currentUser, sessionUser, serverVersion, restrictedLogin,
                    onlineMember, onlineUsage, onlineSet,
                    qualityMember, qualityUsage, qualitySet,
                    relayMember, relayUsage, relaySet,
                    retentionMember, retentionUsage, retentionSet,
                    authorityMember, authorityUsage, authoritySet,
                    eligibilityMember, eligibilityUsage, eligibilitySet,
                    taskRelayMember, taskRelayUsage, taskRelaySet,
                    ownerMember, ownerUsage, ownerSet,
                    restrictedGroups, membershipGraphExact, value);
        }

        private Proof withMembershipGraph(boolean value) {
            return new Proof(
                    currentUser, sessionUser, serverVersion, restrictedLogin,
                    onlineMember, onlineUsage, onlineSet,
                    qualityMember, qualityUsage, qualitySet,
                    relayMember, relayUsage, relaySet,
                    retentionMember, retentionUsage, retentionSet,
                    authorityMember, authorityUsage, authoritySet,
                    eligibilityMember, eligibilityUsage, eligibilitySet,
                    taskRelayMember, taskRelayUsage, taskRelaySet,
                    ownerMember, ownerUsage, ownerSet,
                    restrictedGroups, value, matrixValid);
        }

        private Proof withOwnerMembership(
                boolean member, boolean usage, boolean setCapability) {
            return new Proof(
                    currentUser, sessionUser, serverVersion, restrictedLogin,
                    onlineMember, onlineUsage, onlineSet,
                    qualityMember, qualityUsage, qualitySet,
                    relayMember, relayUsage, relaySet,
                    retentionMember, retentionUsage, retentionSet,
                    authorityMember, authorityUsage, authoritySet,
                    eligibilityMember, eligibilityUsage, eligibilitySet,
                    taskRelayMember, taskRelayUsage, taskRelaySet,
                    member, usage, setCapability,
                    restrictedGroups, membershipGraphExact, matrixValid);
        }

        private Proof withMembership(
                Workload workload, boolean member, boolean usage, boolean setCapability) {
            return switch (workload) {
                case ONLINE -> new Proof(
                        currentUser, sessionUser, serverVersion, restrictedLogin,
                        member, usage, setCapability,
                        qualityMember, qualityUsage, qualitySet,
                        relayMember, relayUsage, relaySet,
                        retentionMember, retentionUsage, retentionSet,
                        authorityMember, authorityUsage, authoritySet,
                        eligibilityMember, eligibilityUsage, eligibilitySet,
                        taskRelayMember, taskRelayUsage, taskRelaySet,
                        ownerMember, ownerUsage, ownerSet,
                        restrictedGroups, membershipGraphExact, matrixValid);
                case QUALITY_WORKER -> new Proof(
                        currentUser, sessionUser, serverVersion, restrictedLogin,
                        onlineMember, onlineUsage, onlineSet,
                        member, usage, setCapability,
                        relayMember, relayUsage, relaySet,
                        retentionMember, retentionUsage, retentionSet,
                        authorityMember, authorityUsage, authoritySet,
                        eligibilityMember, eligibilityUsage, eligibilitySet,
                        taskRelayMember, taskRelayUsage, taskRelaySet,
                        ownerMember, ownerUsage, ownerSet,
                        restrictedGroups, membershipGraphExact, matrixValid);
                case RELAY -> new Proof(
                        currentUser, sessionUser, serverVersion, restrictedLogin,
                        onlineMember, onlineUsage, onlineSet,
                        qualityMember, qualityUsage, qualitySet,
                        member, usage, setCapability,
                        retentionMember, retentionUsage, retentionSet,
                        authorityMember, authorityUsage, authoritySet,
                        eligibilityMember, eligibilityUsage, eligibilitySet,
                        taskRelayMember, taskRelayUsage, taskRelaySet,
                        ownerMember, ownerUsage, ownerSet,
                        restrictedGroups, membershipGraphExact, matrixValid);
                case RETENTION_EXECUTOR -> new Proof(
                        currentUser, sessionUser, serverVersion, restrictedLogin,
                        onlineMember, onlineUsage, onlineSet,
                        qualityMember, qualityUsage, qualitySet,
                        relayMember, relayUsage, relaySet,
                        member, usage, setCapability,
                        authorityMember, authorityUsage, authoritySet,
                        eligibilityMember, eligibilityUsage, eligibilitySet,
                        taskRelayMember, taskRelayUsage, taskRelaySet,
                        ownerMember, ownerUsage, ownerSet,
                        restrictedGroups, membershipGraphExact, matrixValid);
                case CONSUMER_REGISTRY_AUTHORITY -> new Proof(
                        currentUser, sessionUser, serverVersion, restrictedLogin,
                        onlineMember, onlineUsage, onlineSet,
                        qualityMember, qualityUsage, qualitySet,
                        relayMember, relayUsage, relaySet,
                        retentionMember, retentionUsage, retentionSet,
                        member, usage, setCapability,
                        eligibilityMember, eligibilityUsage, eligibilitySet,
                        taskRelayMember, taskRelayUsage, taskRelaySet,
                        ownerMember, ownerUsage, ownerSet,
                        restrictedGroups, membershipGraphExact, matrixValid);
                case ELIGIBILITY_CONSUMER -> new Proof(
                        currentUser, sessionUser, serverVersion, restrictedLogin,
                        onlineMember, onlineUsage, onlineSet,
                        qualityMember, qualityUsage, qualitySet,
                        relayMember, relayUsage, relaySet,
                        retentionMember, retentionUsage, retentionSet,
                        authorityMember, authorityUsage, authoritySet,
                        member, usage, setCapability,
                        taskRelayMember, taskRelayUsage, taskRelaySet,
                        ownerMember, ownerUsage, ownerSet,
                        restrictedGroups, membershipGraphExact, matrixValid);
                case TASK_RELAY -> new Proof(
                        currentUser, sessionUser, serverVersion, restrictedLogin,
                        onlineMember, onlineUsage, onlineSet,
                        qualityMember, qualityUsage, qualitySet,
                        relayMember, relayUsage, relaySet,
                        retentionMember, retentionUsage, retentionSet,
                        authorityMember, authorityUsage, authoritySet,
                        eligibilityMember, eligibilityUsage, eligibilitySet,
                        member, usage, setCapability,
                        ownerMember, ownerUsage, ownerSet,
                        restrictedGroups, membershipGraphExact, matrixValid);
            };
        }
    }
}
