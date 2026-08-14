package cn.edu.suda.scholarsense.ingestionquality.adapters;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Opens the configured pool and verifies transport, principal and effective privileges. */
public final class PostgreSqlDataSourceStartupGate {
    static final String REQUIRED_SERVER_VERSION_NUM = "180004";
    private static final String PRINCIPAL_QUERY = """
            select current_user,
                   session_user,
                   current_setting('server_version_num'),
                   login.rolcanlogin
                     and login.rolinherit
                     and not (
                       login.rolsuper
                       or login.rolcreaterole
                       or login.rolcreatedb
                       or login.rolreplication
                       or login.rolbypassrls)
                     and not has_database_privilege(
                       current_user, current_database(), 'CREATE')
                     and not has_schema_privilege(
                       current_user, 'ingestion_quality', 'CREATE')
                     and has_schema_privilege(
                       current_user, 'ingestion_quality', 'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_online',
                     'MEMBER'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_online',
                     'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_online',
                     'SET'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_quality_worker',
                     'MEMBER'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_quality_worker',
                     'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_quality_worker',
                     'SET'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_relay',
                     'MEMBER'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_relay',
                     'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_relay',
                     'SET'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_retention_executor',
                     'MEMBER'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_retention_executor',
                     'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_retention_executor',
                     'SET'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_consumer_registry_authority',
                     'MEMBER'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_consumer_registry_authority',
                     'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_consumer_registry_authority',
                     'SET'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_eligibility_consumer',
                     'MEMBER'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_eligibility_consumer',
                     'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_eligibility_consumer',
                     'SET'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_task_relay',
                     'MEMBER'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_task_relay',
                     'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_task_relay',
                     'SET'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_batch_owner',
                     'MEMBER'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_batch_owner',
                     'USAGE'),
                   pg_has_role(
                     current_user,
                     'scholarsense_ingestion_quality_batch_owner',
                     'SET'),
                   not exists (
                     select 1
                       from pg_catalog.pg_roles controlled
                      where controlled.rolname in (
                              'scholarsense_ingestion_quality_online',
                              'scholarsense_ingestion_quality_quality_worker',
                              'scholarsense_ingestion_quality_relay',
                              'scholarsense_ingestion_quality_retention_executor',
                              'scholarsense_ingestion_quality_consumer_registry_authority',
                              'scholarsense_ingestion_quality_eligibility_consumer',
                              'scholarsense_ingestion_quality_task_relay',
                              'scholarsense_ingestion_quality_batch_owner')
                        and (
                          controlled.rolcanlogin
                          or controlled.rolsuper
                          or controlled.rolcreaterole
                          or controlled.rolcreatedb
                          or controlled.rolreplication
                          or controlled.rolbypassrls)),
                   (
                     select count(*)=1
                            and bool_and(membership.inherit_option)
                            and not bool_or(membership.set_option)
                            and not bool_or(membership.admin_option)
                       from pg_catalog.pg_auth_members membership
                      where membership.member=login.oid),
                   (
                     select count(*)=1
                       from (
                         with recursive reachable(roleid) as (
                           select membership.roleid
                             from pg_catalog.pg_auth_members membership
                            where membership.member=login.oid
                           union
                           select membership.roleid
                             from pg_catalog.pg_auth_members membership
                             join reachable
                               on membership.member=reachable.roleid
                         )
                         select roleid from reachable
                       ) all_memberships)
              from pg_catalog.pg_roles login
             where login.rolname=current_user
            """;
    private static final List<String> COLUMN_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "REFERENCES");
    private static final List<String> TABLE_PRIVILEGES =
            List.of(
                    "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE",
                    "REFERENCES", "TRIGGER", "MAINTAIN");
    private static final String ONLINE_PRIVILEGE_QUERY = privilegeQuery(
            List.of(
                    entry("iq_data_source_catalog", "SELECT"),
                    entry("iq_source_id_reservation", "SELECT"),
                    entry("iq_dependency_id_reservation", "SELECT"),
                    entry("iq_source_contract", "SELECT"),
                    entry("iq_dependency_binding", "SELECT"),
                    entry("iq_catalog_validation_attempt", "SELECT"),
                    entry("iq_catalog_evidence", "SELECT"),
                    entry("iq_catalog_current", "SELECT"),
                    entry("iq_catalog_idempotency", "SELECT"),
                    entry("iq_local_audit_outbox", "SELECT"),
                    entry("iq_local_audit_outbox", "INSERT"),
                    entry("iq_historical_window", "SELECT"),
                    entry("iq_subject_mapping_consumer_cursor", "SELECT"),
                    entry("iq_mapping_recompute_request", "SELECT"),
                    entry("iq_mapping_recompute_job", "SELECT"),
                    entry("iq_mapping_recompute_result", "SELECT")),
            List.of(
                    columnEntry("iq_data_source_catalog", "catalog_id", "INSERT"),
                    columnEntry("iq_data_source_catalog", "catalog_release_id", "INSERT"),
                    columnEntry("iq_data_source_catalog", "contract_version", "INSERT"),
                    columnEntry("iq_data_source_catalog", "status", "INSERT"),
                    columnEntry("iq_data_source_catalog", "aggregate_version", "INSERT"),
                    columnEntry("iq_data_source_catalog", "content_digest", "INSERT"),
                    columnEntry("iq_data_source_catalog", "evidence_set_digest", "INSERT"),
                    columnEntry("iq_data_source_catalog", "validation_errors", "INSERT"),
                    columnEntry("iq_data_source_catalog", "created_at", "INSERT"),
                    columnEntry("iq_data_source_catalog", "updated_at", "INSERT"),
                    columnEntry("iq_data_source_catalog", "published_at", "INSERT"),
                    columnEntry("iq_data_source_catalog", "expires_at", "INSERT"),
                    columnEntry("iq_catalog_idempotency", "idempotency_key_digest", "INSERT"),
                    columnEntry("iq_catalog_idempotency", "request_digest", "INSERT"),
                    columnEntry("iq_catalog_idempotency", "catalog_id", "INSERT"),
                    columnEntry("iq_catalog_idempotency", "response", "INSERT"),
                    columnEntry("iq_catalog_idempotency", "created_at", "INSERT"),
                    columnEntry("iq_catalog_idempotency", "expires_at", "INSERT"),
                    columnEntry("iq_local_audit_fact", "audit_id", "INSERT"),
                    columnEntry("iq_local_audit_fact", "actor_search_token", "INSERT"),
                    columnEntry("iq_local_audit_fact", "action", "INSERT"),
                    columnEntry("iq_local_audit_fact", "result", "INSERT"),
                    columnEntry("iq_local_audit_fact", "catalog_id", "INSERT"),
                    columnEntry("iq_local_audit_fact", "aggregate_version", "INSERT"),
                    columnEntry("iq_local_audit_fact", "trace_id", "INSERT"),
                    columnEntry("iq_local_audit_fact", "occurred_at", "INSERT"),
                    columnEntry("iq_local_audit_fact", "authorization_context", "INSERT"),
                    columnEntry("iq_local_audit_fact", "expires_at", "INSERT"),
                    columnEntry("iq_catalog_idempotency", "request_digest", "UPDATE"),
                    columnEntry("iq_catalog_idempotency", "catalog_id", "UPDATE"),
                    columnEntry("iq_catalog_idempotency", "response", "UPDATE"),
                    columnEntry("iq_catalog_idempotency", "created_at", "UPDATE"),
                    columnEntry("iq_catalog_idempotency", "expires_at", "UPDATE")),
            List.of(
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
                            + "character varying, character, "
                            + "timestamp with time zone, timestamp with time zone, character)",
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
                            + "character varying, timestamp with time zone)"));
    private static final String QUALITY_WORKER_PRIVILEGE_QUERY = privilegeQuery(
            List.of(
                    entry("iq_data_batch", "SELECT"),
                    entry("iq_normalized_fact", "SELECT"),
                    entry("iq_batch_quality_measurement", "SELECT"),
                    entry("iq_batch_quality_operand", "SELECT"),
                    entry("iq_batch_quality_impact_scope", "SELECT"),
                    entry("iq_quality_snapshot", "SELECT"),
                    entry("iq_quality_snapshot_metric", "SELECT"),
                    entry("iq_quality_snapshot_impact_scope", "SELECT"),
                    entry("iq_batch_idempotency", "SELECT")),
            List.of(),
            List.of(
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
    private static final String RELAY_PRIVILEGE_QUERY = privilegeQuery(
            List.of(
                    entry("iq_local_audit_fact", "SELECT"),
                    entry("iq_local_audit_outbox", "SELECT"),
                    entry("iq_mapping_recompute_outbox", "SELECT"),
                    entry("iq_batch_quality_outbox", "SELECT")),
            List.of(
                    columnEntry("iq_local_audit_outbox", "status", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "attempts", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "available_at", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "claimed_until", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "delivered_at", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "last_error_code", "UPDATE"),
                    columnEntry("iq_mapping_recompute_outbox", "status", "UPDATE"),
                    columnEntry("iq_mapping_recompute_outbox", "attempts", "UPDATE"),
                    columnEntry("iq_mapping_recompute_outbox", "available_at", "UPDATE"),
                    columnEntry("iq_mapping_recompute_outbox", "claimed_until", "UPDATE"),
                    columnEntry("iq_mapping_recompute_outbox", "delivered_at", "UPDATE"),
                    columnEntry("iq_mapping_recompute_outbox", "last_error_code", "UPDATE")),
            List.of(
                    "iq_claim_next_batch_quality_outbox()",
                    "iq_release_batch_quality_outbox(uuid, bigint)",
                    "iq_deliver_batch_quality_outbox(uuid, bigint)",
                    "iq_fail_batch_quality_outbox(uuid, bigint)",
                    "iq_claim_next_quality_eligibility_outbox()",
                    "iq_release_quality_eligibility_outbox(uuid, bigint)",
                    "iq_deliver_quality_eligibility_outbox(uuid, bigint)",
                    "iq_fail_quality_eligibility_outbox(uuid, bigint)"));
    private static final String RETENTION_PRIVILEGE_QUERY = privilegeQuery(
            List.of(),
            List.of(),
            List.of(
                    "iq_cleanup_expired(timestamp with time zone)",
                    "iq_find_next_due_quality_snapshot_retention()",
                    "iq_execute_quality_snapshot_retention(uuid, uuid, uuid, character, character, "
                            + "uuid, character)",
                    "iq_cleanup_quality_eligibility_expired(timestamp with time zone)",
                    "iq_cleanup_quality_fuse_expired(timestamp with time zone)",
                    "iq_cleanup_quality_recovery_expired(timestamp with time zone)"));
    private static final String CONSUMER_REGISTRY_AUTHORITY_PRIVILEGE_QUERY = privilegeQuery(
            List.of(),
            List.of(),
            List.of(
                    "iq_ingest_quality_snapshot_retention_authority(uuid, bytea, character)"));
    private static final String ELIGIBILITY_CONSUMER_PRIVILEGE_QUERY = privilegeQuery(
            List.of(),
            List.of(),
            List.of(
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
    private static final String TASK_RELAY_PRIVILEGE_QUERY = privilegeQuery(
            List.of(),
            List.of(),
                List.of(
                    "iq_claim_next_quality_task_outbox()",
                    "iq_authorize_quality_task_send(uuid, bigint, bigint)",
                    "iq_mark_quality_task_delivery_retry(uuid, bigint, character varying)",
                    "iq_complete_quality_task_delivery(uuid, bigint, character varying)",
                    "iq_fail_quality_task_delivery(uuid, bigint, character varying)"));

    private PostgreSqlDataSourceStartupGate() {}

    public static PostgreSqlConnectionProfile verifyOnline(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(dataSource, environment, expectedWorkloadIdentity, Workload.ONLINE);
    }

    public static PostgreSqlConnectionProfile verifyRelay(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(dataSource, environment, expectedWorkloadIdentity, Workload.RELAY);
    }

    public static PostgreSqlConnectionProfile verifyQualityWorker(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(dataSource, environment, expectedWorkloadIdentity, Workload.QUALITY_WORKER);
    }

    public static PostgreSqlConnectionProfile verifyRetentionExecutor(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(
                dataSource, environment, expectedWorkloadIdentity, Workload.RETENTION_EXECUTOR);
    }

    public static PostgreSqlConnectionProfile verifyConsumerRegistryAuthority(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(
                dataSource,
                environment,
                expectedWorkloadIdentity,
                Workload.CONSUMER_REGISTRY_AUTHORITY);
    }

    public static PostgreSqlConnectionProfile verifyEligibilityConsumer(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(
                dataSource, environment, expectedWorkloadIdentity,
                Workload.ELIGIBILITY_CONSUMER);
    }

    public static PostgreSqlConnectionProfile verifyTaskRelay(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(dataSource, environment, expectedWorkloadIdentity, Workload.TASK_RELAY);
    }

    public static PostgreSqlConnectionProfile verifyRecoveryWorker(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        Objects.requireNonNull(dataSource);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            PostgreSqlConnectionProfile profile = PostgreSqlConnectionProfile.validate(
                    environment, connection.getMetaData().getURL(),
                    connection.getMetaData().getUserName(), expectedWorkloadIdentity);
            try (var proof = statement.executeQuery("""
                    select current_user=session_user,
                      current_setting('server_version_num')='180004',
                      login.rolcanlogin and login.rolinherit and not (
                        login.rolsuper or login.rolcreaterole or login.rolcreatedb
                        or login.rolreplication or login.rolbypassrls),
                      pg_has_role(current_user,
                        'scholarsense_ingestion_quality_recovery_worker','MEMBER'),
                      pg_has_role(current_user,
                        'scholarsense_ingestion_quality_recovery_worker','USAGE'),
                      not pg_has_role(current_user,
                        'scholarsense_ingestion_quality_recovery_worker','SET'),
                      (select count(*)=1 and bool_and(m.inherit_option)
                         and not bool_or(m.set_option) and not bool_or(m.admin_option)
                         from pg_catalog.pg_auth_members m where m.member=login.oid)
                    from pg_catalog.pg_roles login where login.rolname=current_user
                    """)) {
                if (!proof.next() || !proof.getBoolean(1) || !proof.getBoolean(2)
                        || !proof.getBoolean(3) || !proof.getBoolean(4)
                        || !proof.getBoolean(5) || !proof.getBoolean(6)
                        || !proof.getBoolean(7) || proof.next()) {
                    throw new IllegalArgumentException(
                            "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH");
                }
            }
            try (var privileges = statement.executeQuery(privilegeQuery(
                    List.of(), List.of(), List.of(
                            "iq_claim_recovery_validation_job(uuid, character, timestamp with time zone, integer)",
                            "iq_checkpoint_recovery_validation_job(uuid, bigint, bigint, jsonb, timestamp with time zone)",
                            "iq_finalize_recovery_validation_job(uuid, bigint, jsonb, timestamp with time zone)",
                            "iq_find_claimable_recovery_validation_jobs(integer, timestamp with time zone)",
                            "iq_is_recovery_validation_lease_current(uuid, bigint, timestamp with time zone)",
                            "iq_build_quality_recovery_readiness_evidence(uuid, timestamp with time zone)",
                            "iq_load_recovery_validation_execution_context(uuid, timestamp with time zone)",
                            "iq_execute_recovery_backfill(uuid, uuid, uuid, character, character, "
                                    + "character, character, character)",
                            "iq_execute_recovery_full_reconciliation(uuid, uuid, uuid, character, "
                                    + "character, character, character, character)",
                            "iq_release_recovery_validation_job(uuid, bigint, character varying, character varying, timestamp with time zone, timestamp with time zone)")))) {
                if (!privileges.next() || !privileges.getBoolean(1) || privileges.next()) {
                    throw new IllegalArgumentException(
                            "INGESTION_QUALITY_DATABASE_PRIVILEGE_MATRIX_MISMATCH");
                }
            }
            return profile;
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_DATABASE_STARTUP_GATE_UNAVAILABLE", unavailable);
        }
    }

    private static PostgreSqlConnectionProfile verify(
            DataSource dataSource,
            String environment,
            String expectedWorkloadIdentity,
            Workload workload) {
        Objects.requireNonNull(dataSource);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            String jdbcUrl = connection.getMetaData().getURL();
            statement.setQueryTimeout(5);
            PostgreSqlConnectionProfile profile;
            try (var proof = statement.executeQuery(PRINCIPAL_QUERY)) {
                if (!proof.next()) throw identityUnavailable();
                String currentUser = proof.getString(1);
                String sessionUser = proof.getString(2);
                String serverVersion = proof.getString(3);
                boolean restrictedLogin = proof.getBoolean(4);
                boolean onlineMember = proof.getBoolean(5);
                boolean onlineUsage = proof.getBoolean(6);
                boolean onlineSet = proof.getBoolean(7);
                boolean qualityMember = proof.getBoolean(8);
                boolean qualityUsage = proof.getBoolean(9);
                boolean qualitySet = proof.getBoolean(10);
                boolean relayMember = proof.getBoolean(11);
                boolean relayUsage = proof.getBoolean(12);
                boolean relaySet = proof.getBoolean(13);
                boolean retentionMember = proof.getBoolean(14);
                boolean retentionUsage = proof.getBoolean(15);
                boolean retentionSet = proof.getBoolean(16);
                boolean authorityMember = proof.getBoolean(17);
                boolean authorityUsage = proof.getBoolean(18);
                boolean authoritySet = proof.getBoolean(19);
                boolean eligibilityMember = proof.getBoolean(20);
                boolean eligibilityUsage = proof.getBoolean(21);
                boolean eligibilitySet = proof.getBoolean(22);
                boolean taskRelayMember = proof.getBoolean(23);
                boolean taskRelayUsage = proof.getBoolean(24);
                boolean taskRelaySet = proof.getBoolean(25);
                boolean ownerMember = proof.getBoolean(26);
                boolean ownerUsage = proof.getBoolean(27);
                boolean ownerSet = proof.getBoolean(28);
                boolean restrictedGroups = proof.getBoolean(29);
                boolean exactDirectMembership = proof.getBoolean(30);
                boolean noIndirectMembership = proof.getBoolean(31);
                if (proof.next()) throw identityUnavailable();
                profile = PostgreSqlConnectionProfile.validate(
                        environment,
                        jdbcUrl,
                        currentUser,
                        expectedWorkloadIdentity);
                if (!Objects.equals(currentUser, sessionUser)) {
                    throw new IllegalArgumentException(
                            "INGESTION_QUALITY_DATABASE_SESSION_IDENTITY_MISMATCH");
                }
                if (!REQUIRED_SERVER_VERSION_NUM.equals(serverVersion)) {
                    throw new IllegalArgumentException(
                            "INGESTION_QUALITY_DATABASE_SERVER_VERSION_MISMATCH");
                }
                if (!restrictedLogin || !restrictedGroups) {
                    throw new IllegalArgumentException(
                            "INGESTION_QUALITY_DATABASE_ROLE_RESTRICTION_MISMATCH");
                }
                boolean membershipValid = switch (workload) {
                    case ONLINE -> onlineMember && onlineUsage && !onlineSet
                            && !qualityMember && !qualityUsage && !qualitySet
                            && !relayMember && !relayUsage && !relaySet
                            && !retentionMember && !retentionUsage && !retentionSet
                            && !authorityMember && !authorityUsage && !authoritySet
                            && !eligibilityMember && !eligibilityUsage && !eligibilitySet
                            && !taskRelayMember && !taskRelayUsage && !taskRelaySet
                            && !ownerMember && !ownerUsage && !ownerSet;
                    case QUALITY_WORKER -> qualityMember && qualityUsage && !qualitySet
                            && !onlineMember && !onlineUsage && !onlineSet
                            && !relayMember && !relayUsage && !relaySet
                            && !retentionMember && !retentionUsage && !retentionSet
                            && !authorityMember && !authorityUsage && !authoritySet
                            && !eligibilityMember && !eligibilityUsage && !eligibilitySet
                            && !taskRelayMember && !taskRelayUsage && !taskRelaySet
                            && !ownerMember && !ownerUsage && !ownerSet;
                    case RELAY -> relayMember && relayUsage && !relaySet
                            && !onlineMember && !onlineUsage && !onlineSet
                            && !qualityMember && !qualityUsage && !qualitySet
                            && !retentionMember && !retentionUsage && !retentionSet
                            && !authorityMember && !authorityUsage && !authoritySet
                            && !eligibilityMember && !eligibilityUsage && !eligibilitySet
                            && !taskRelayMember && !taskRelayUsage && !taskRelaySet
                            && !ownerMember && !ownerUsage && !ownerSet;
                    case RETENTION_EXECUTOR -> retentionMember && retentionUsage && !retentionSet
                            && !onlineMember && !onlineUsage && !onlineSet
                            && !qualityMember && !qualityUsage && !qualitySet
                            && !relayMember && !relayUsage && !relaySet
                            && !authorityMember && !authorityUsage && !authoritySet
                            && !eligibilityMember && !eligibilityUsage && !eligibilitySet
                            && !taskRelayMember && !taskRelayUsage && !taskRelaySet
                            && !ownerMember && !ownerUsage && !ownerSet;
                    case CONSUMER_REGISTRY_AUTHORITY ->
                            authorityMember && authorityUsage && !authoritySet
                            && !onlineMember && !onlineUsage && !onlineSet
                            && !qualityMember && !qualityUsage && !qualitySet
                            && !relayMember && !relayUsage && !relaySet
                            && !retentionMember && !retentionUsage && !retentionSet
                            && !eligibilityMember && !eligibilityUsage && !eligibilitySet
                            && !taskRelayMember && !taskRelayUsage && !taskRelaySet
                            && !ownerMember && !ownerUsage && !ownerSet;
                    case ELIGIBILITY_CONSUMER ->
                            eligibilityMember && eligibilityUsage && !eligibilitySet
                            && !onlineMember && !onlineUsage && !onlineSet
                            && !qualityMember && !qualityUsage && !qualitySet
                            && !relayMember && !relayUsage && !relaySet
                            && !retentionMember && !retentionUsage && !retentionSet
                            && !authorityMember && !authorityUsage && !authoritySet
                            && !taskRelayMember && !taskRelayUsage && !taskRelaySet
                            && !ownerMember && !ownerUsage && !ownerSet;
                    case TASK_RELAY -> taskRelayMember && taskRelayUsage && !taskRelaySet
                            && !onlineMember && !onlineUsage && !onlineSet
                            && !qualityMember && !qualityUsage && !qualitySet
                            && !relayMember && !relayUsage && !relaySet
                            && !retentionMember && !retentionUsage && !retentionSet
                            && !authorityMember && !authorityUsage && !authoritySet
                            && !eligibilityMember && !eligibilityUsage && !eligibilitySet
                            && !ownerMember && !ownerUsage && !ownerSet;
                };
                if (!membershipValid) {
                    throw new IllegalArgumentException(
                            "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH");
                }
                if (!exactDirectMembership || !noIndirectMembership) {
                    throw new IllegalArgumentException(
                            "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH");
                }
            }
            try (var privileges = statement.executeQuery(workload.privilegeQuery)) {
                if (!privileges.next() || !privileges.getBoolean(1) || privileges.next()) {
                    throw new IllegalArgumentException(
                            "INGESTION_QUALITY_DATABASE_PRIVILEGE_MATRIX_MISMATCH");
                }
            }
            return profile;
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_DATABASE_STARTUP_GATE_UNAVAILABLE", unavailable);
        }
    }

    private static String privilegeQuery(
            List<String> tablePrivileges,
            List<String> columnPrivileges,
            List<String> executableFunctions) {
        return """
                with expected_table(table_name, privilege_type) as (
                  %s
                ),
                expected_column_extra(table_name, column_name, privilege_type) as (
                  %s
                ),
                actual_table(table_name, privilege_type) as (
                  select tables.table_name, privileges.privilege_type
                    from information_schema.tables tables
                    cross join (values %s) privileges(privilege_type)
                   where tables.table_schema='ingestion_quality'
                     and tables.table_type in ('BASE TABLE','VIEW')
                     and has_table_privilege(
                       current_user,
                       format('%%I.%%I', tables.table_schema, tables.table_name),
                       privileges.privilege_type)
                ),
                expected_column(table_name, column_name, privilege_type) as (
                  select columns.table_name,
                         columns.column_name,
                         expected_table.privilege_type
                    from information_schema.columns columns
                    join expected_table
                      on expected_table.table_name=columns.table_name
                   where columns.table_schema='ingestion_quality'
                     and expected_table.privilege_type in (
                       'SELECT', 'INSERT', 'UPDATE', 'REFERENCES')
                  union
                  select table_name, column_name, privilege_type
                    from expected_column_extra
                ),
                actual_column(table_name, column_name, privilege_type) as (
                  select columns.table_name,
                         columns.column_name,
                         privileges.privilege_type
                    from information_schema.columns columns
                    cross join (values %s) privileges(privilege_type)
                   where columns.table_schema='ingestion_quality'
                     and has_column_privilege(
                       current_user,
                       format('%%I.%%I', columns.table_schema, columns.table_name),
                       columns.column_name,
                       privileges.privilege_type)
                ),
                expected_function(function_signature) as (
                  %s
                ),
                actual_function(function_signature) as (
                  select procedure.proname || '(' ||
                         pg_catalog.oidvectortypes(procedure.proargtypes) || ')'
                    from pg_catalog.pg_proc procedure
                    join pg_catalog.pg_namespace namespace
                      on namespace.oid=procedure.pronamespace
                   where namespace.nspname='ingestion_quality'
                     and has_function_privilege(current_user, procedure.oid, 'EXECUTE')
                )
                select not exists (
                         (select * from actual_table except select * from expected_table)
                         union all
                         (select * from expected_table except select * from actual_table))
                       and not exists (
                         (select * from actual_column except select * from expected_column)
                          union all
                         (select * from expected_column except select * from actual_column))
                       and not exists (
                         (select * from actual_function except select * from expected_function)
                          union all
                         (select * from expected_function except select * from actual_function))
                """.formatted(
                expectedRelation(tablePrivileges, 2),
                expectedRelation(columnPrivileges, 3),
                privilegeValues(TABLE_PRIVILEGES),
                privilegeValues(COLUMN_PRIVILEGES),
                expectedRelation(
                        executableFunctions.stream()
                                .map(function -> "('%s')".formatted(function))
                                .toList(),
                        1));
    }

    private static String expectedRelation(List<String> rows, int columnCount) {
        if (!rows.isEmpty()) return "values " + String.join(",\n", rows);
        return "select " + java.util.stream.IntStream.range(0, columnCount)
                .mapToObj(ignored -> "null::text")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow() + " where false";
    }

    private static String entry(String table, String privilege) {
        return "('%s','%s')".formatted(table, privilege);
    }

    private static String columnEntry(String table, String column, String privilege) {
        return "('%s','%s','%s')".formatted(table, column, privilege);
    }

    private static String privilegeValues(List<String> privileges) {
        return privileges.stream()
                .map(privilege -> "('%s')".formatted(privilege))
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static IllegalStateException identityUnavailable() {
        return new IllegalStateException("INGESTION_QUALITY_DATABASE_IDENTITY_UNAVAILABLE");
    }

    private enum Workload {
        ONLINE(ONLINE_PRIVILEGE_QUERY),
        QUALITY_WORKER(QUALITY_WORKER_PRIVILEGE_QUERY),
        RELAY(RELAY_PRIVILEGE_QUERY),
        RETENTION_EXECUTOR(RETENTION_PRIVILEGE_QUERY),
        CONSUMER_REGISTRY_AUTHORITY(CONSUMER_REGISTRY_AUTHORITY_PRIVILEGE_QUERY),
        ELIGIBILITY_CONSUMER(ELIGIBILITY_CONSUMER_PRIVILEGE_QUERY),
        TASK_RELAY(TASK_RELAY_PRIVILEGE_QUERY);

        private final String privilegeQuery;

        Workload(String privilegeQuery) {
            this.privilegeQuery = privilegeQuery;
        }
    }
}
