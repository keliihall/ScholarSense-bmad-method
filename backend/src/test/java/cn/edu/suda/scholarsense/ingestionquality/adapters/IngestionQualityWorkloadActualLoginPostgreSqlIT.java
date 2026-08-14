package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Clean and upgrade PostgreSQL 18.4 evidence for all eight physical workload logins. */
class IngestionQualityWorkloadActualLoginPostgreSqlIT {
    private static final List<String> DATABASE_URL_PROPERTIES = List.of(
            "scholarsense.audit.pg.url", "scholarsense.audit.pg.upgrade-url");
    private static final String BATCH_OWNER =
            "scholarsense_ingestion_quality_batch_owner";
    private static final List<Workload> WORKLOADS = List.of(
            new Workload(
                    Kind.ONLINE,
                    "scholarsense_ingestion_quality_online",
                    "scholarsense_iq_actual_online_test_login",
                    Set.of(
                            "iq_add_catalog_source",
                            "iq_add_catalog_dependency",
                            "iq_record_catalog_validation",
                            "iq_publish_catalog",
                            "iq_record_historical_window",
                            "iq_accept_subject_mapping_event",
                            "iq_reconcile_subject_mapping_consumer",
                            "iq_enqueue_mapping_recompute",
                            "iq_record_mapping_recompute_plan",
                            "iq_claim_mapping_recompute_job",
                            "iq_checkpoint_mapping_recompute_job",
                            "iq_complete_mapping_recompute_job",
                            "iq_fail_mapping_recompute_job",
                            "iq_requeue_mapping_recompute_job",
                            "iq_cancel_mapping_recompute_job",
                            "iq_find_assessed_quality_snapshot_ids",
                            "iq_find_assessed_quality_snapshot",
                            "iq_find_assessed_quality_snapshot_metrics",
                            "iq_find_assessed_quality_snapshot_impact_scopes",
                            "iq_find_assessed_quality_snapshot_page",
                            "iq_find_assessed_quality_snapshot_page_metrics",
                            "iq_find_assessed_quality_snapshot_page_impact_scopes",
                            "iq_resolve_quality_snapshot_source",
                            "iq_append_quality_snapshot_read_audit",
                            "iq_find_quality_eligibility_ids",
                            "iq_find_quality_eligibility_page",
                            "iq_find_quality_eligibility_page_members",
                            "iq_resolve_quality_eligibility_source",
                            "iq_append_quality_eligibility_read_audit",
                            "iq_find_quality_recovery_task_ids",
                            "iq_find_quality_recovery_task_page",
                            "iq_find_quality_recovery_task_page_rules",
                            "iq_append_quality_recovery_task_read_audit",
                            "iq_submit_quality_recovery_request",
                            "iq_submit_quality_recovery_with_validation",
                            "iq_submit_recovery_validation_job",
                            "iq_resolve_quality_recovery_task_owner",
                            "iq_load_quality_recovery_command_context",
                            "iq_find_quality_recovery_request",
                            "iq_find_quality_recovery_idempotency",
                            "iq_store_quality_recovery_evidence",
                            "iq_bind_quality_recovery_approval",
                            "iq_build_quality_recovery_readiness_evidence",
                            "iq_execute_quality_recovery",
                            "iq_claim_quality_recovery_confirmations",
                            "iq_mark_quality_recovery_confirmation_delivered",
                            "iq_release_quality_recovery_confirmation")),
            new Workload(
                    Kind.QUALITY_WORKER,
                    "scholarsense_ingestion_quality_quality_worker",
                    "scholarsense_iq_actual_quality_worker_test_login",
                    Set.of(
                            "iq_receive_data_batch",
                            "iq_inspect_batch_command_precedence",
                            "iq_append_normalized_fact",
                            "iq_record_batch_quality_measurement",
                            "iq_record_batch_quality_impact_scope",
                            "iq_seal_data_batch",
                            "iq_commit_batch_quality_evaluation",
                            "iq_publish_data_batch")),
            new Workload(
                    Kind.RELAY,
                    "scholarsense_ingestion_quality_relay",
                    "scholarsense_iq_actual_relay_test_login",
                    Set.of(
                            "iq_claim_next_batch_quality_outbox",
                            "iq_release_batch_quality_outbox",
                            "iq_deliver_batch_quality_outbox",
                            "iq_fail_batch_quality_outbox",
                            "iq_claim_next_quality_eligibility_outbox",
                            "iq_release_quality_eligibility_outbox",
                            "iq_deliver_quality_eligibility_outbox",
                            "iq_fail_quality_eligibility_outbox")),
            new Workload(
                    Kind.RETENTION_EXECUTOR,
                    "scholarsense_ingestion_quality_retention_executor",
                    "scholarsense_iq_actual_retention_test_login",
                    Set.of(
                            "iq_cleanup_expired",
                            "iq_find_next_due_quality_snapshot_retention",
                            "iq_execute_quality_snapshot_retention",
                            "iq_cleanup_quality_eligibility_expired",
                            "iq_cleanup_quality_fuse_expired",
                            "iq_cleanup_quality_recovery_expired")),
            new Workload(
                    Kind.CONSUMER_REGISTRY_AUTHORITY,
                    "scholarsense_ingestion_quality_consumer_registry_authority",
                    "scholarsense_iq_actual_authority_test_login",
                    Set.of("iq_ingest_quality_snapshot_retention_authority")),
            new Workload(
                    Kind.ELIGIBILITY_CONSUMER,
                    "scholarsense_ingestion_quality_eligibility_consumer",
                    "scholarsense_iq_actual_eligibility_consumer_test_login",
                    Set.of(
                            "iq_find_quality_snapshot_evidence",
                            "iq_find_quality_snapshot_evidence_v2",
                            "iq_load_quality_eligibility_processing_state",
                            "iq_load_quality_eligibility_processing_state_v2",
                            "iq_accept_quality_eligibility_event",
                            "iq_accept_quality_eligibility_event_v2",
                            "iq_append_quality_fuse_rejection_audit")),
            new Workload(
                    Kind.TASK_RELAY,
                    "scholarsense_ingestion_quality_task_relay",
                    "scholarsense_iq_actual_task_relay_test_login",
                    Set.of(
                            "iq_claim_next_quality_task_outbox",
                            "iq_authorize_quality_task_send",
                            "iq_mark_quality_task_delivery_retry",
                            "iq_complete_quality_task_delivery",
                            "iq_fail_quality_task_delivery")),
            new Workload(
                    Kind.RECOVERY_WORKER,
                    "scholarsense_ingestion_quality_recovery_worker",
                    "scholarsense_iq_actual_recovery_worker_test_login",
                    Set.of(
                            "iq_claim_recovery_validation_job",
                            "iq_checkpoint_recovery_validation_job",
                            "iq_finalize_recovery_validation_job",
                            "iq_find_claimable_recovery_validation_jobs",
                            "iq_is_recovery_validation_lease_current",
                            "iq_build_quality_recovery_readiness_evidence",
                            "iq_load_recovery_validation_execution_context",
                            "iq_execute_recovery_backfill",
                            "iq_execute_recovery_full_reconciliation",
                            "iq_release_recovery_validation_job")));

    @Test
    void cleanAndUpgradeExerciseEightExclusiveActualLoginsAndSecurityDefinerBoundaries() {
        String adminUser = required("scholarsense.audit.pg.user");
        for (String urlProperty : DATABASE_URL_PROPERTIES) {
            String url = required(urlProperty);
            JdbcTemplate admin = jdbc(url, adminUser);
            ensureWorkloadLogins(admin);
            assertBatchOwnerIsRestricted(admin, urlProperty);

            for (Workload workload : WORKLOADS) {
                DataSource source = dataSource(url, workload.login());
                PostgreSqlConnectionProfile profile =
                        verify(workload.kind(), source, workload.login());
                assertEquals(url, profile.jdbcUrl(), urlProperty + " " + workload.kind());
                assertEquals(
                        workload.login(),
                        profile.expectedWorkloadIdentity(),
                        urlProperty + " " + workload.kind());
                assertActualLoginIsRestrictedAndExclusive(
                        jdbc(url, workload.login()), workload, urlProperty);
                assertExecutableRoutinesAreClosedSecurityDefiners(
                        admin, workload, urlProperty);
            }

            assertAuthorityAndRetentionBoundariesExecuteOnlyForTheirOwnLogin(url);
            assertHiddenSetRoleEscapeIsRejected(admin, url, urlProperty);
        }
    }

    private static PostgreSqlConnectionProfile verify(
            Kind kind, DataSource dataSource, String expectedIdentity) {
        return switch (kind) {
            case ONLINE -> PostgreSqlDataSourceStartupGate.verifyOnline(
                    dataSource, "test", expectedIdentity);
            case QUALITY_WORKER -> PostgreSqlDataSourceStartupGate.verifyQualityWorker(
                    dataSource, "test", expectedIdentity);
            case RELAY -> PostgreSqlDataSourceStartupGate.verifyRelay(
                    dataSource, "test", expectedIdentity);
            case RETENTION_EXECUTOR -> PostgreSqlDataSourceStartupGate.verifyRetentionExecutor(
                    dataSource, "test", expectedIdentity);
            case CONSUMER_REGISTRY_AUTHORITY ->
                    PostgreSqlDataSourceStartupGate.verifyConsumerRegistryAuthority(
                            dataSource, "test", expectedIdentity);
            case ELIGIBILITY_CONSUMER ->
                    PostgreSqlDataSourceStartupGate.verifyEligibilityConsumer(
                            dataSource, "test", expectedIdentity);
            case TASK_RELAY -> PostgreSqlDataSourceStartupGate.verifyTaskRelay(
                    dataSource, "test", expectedIdentity);
            case RECOVERY_WORKER -> PostgreSqlDataSourceStartupGate.verifyRecoveryWorker(
                    dataSource, "test", expectedIdentity);
        };
    }

    private static void assertActualLoginIsRestrictedAndExclusive(
            JdbcTemplate workloadJdbc, Workload workload, String urlProperty) {
        assertTrue(Boolean.TRUE.equals(workloadJdbc.queryForObject("""
                select session_user=current_user
                       and current_user=?
                       and login.rolcanlogin
                       and login.rolinherit
                       and not (login.rolsuper or login.rolcreaterole
                                or login.rolcreatedb or login.rolreplication
                                or login.rolbypassrls)
                  from pg_catalog.pg_roles login
                 where login.rolname=current_user
                """, Boolean.class, workload.login())), urlProperty + " " + workload.kind());

        Set<String> memberships = Set.copyOf(workloadJdbc.queryForList("""
                select granted_role.rolname
                  from pg_catalog.pg_auth_members membership
                  join pg_catalog.pg_roles granted_role
                    on granted_role.oid=membership.roleid
                  join pg_catalog.pg_roles member_role
                    on member_role.oid=membership.member
                 where member_role.rolname=current_user
                """, String.class));
        assertEquals(Set.of(workload.groupRole()), memberships,
                urlProperty + " " + workload.kind());
        assertTrue(Boolean.TRUE.equals(workloadJdbc.queryForObject("""
                select count(*)=1
                       and bool_and(membership.inherit_option)
                       and not bool_or(membership.set_option)
                       and not bool_or(membership.admin_option)
                  from pg_catalog.pg_auth_members membership
                  join pg_catalog.pg_roles member_role
                    on member_role.oid=membership.member
                 where member_role.rolname=current_user
                """, Boolean.class)),
                urlProperty + " " + workload.kind());
        Set<String> reachableMemberships = Set.copyOf(workloadJdbc.queryForList("""
                with recursive reachable(roleid) as (
                  select membership.roleid
                    from pg_catalog.pg_auth_members membership
                    join pg_catalog.pg_roles member_role
                      on member_role.oid=membership.member
                   where member_role.rolname=current_user
                  union
                  select membership.roleid
                    from pg_catalog.pg_auth_members membership
                    join reachable
                      on membership.member=reachable.roleid
                )
                select granted_role.rolname
                  from reachable
                  join pg_catalog.pg_roles granted_role
                    on granted_role.oid=reachable.roleid
                """, String.class));
        assertEquals(Set.of(workload.groupRole()), reachableMemberships,
                urlProperty + " reachable " + workload.kind());
    }

    private static void assertHiddenSetRoleEscapeIsRejected(
            JdbcTemplate admin, String url, String urlProperty) {
        String escapeRole = "scholarsense_iq_hidden_set_escape_test";
        String qualityLogin = login(Kind.QUALITY_WORKER);
        admin.execute("""
                do $escape_role$
                begin
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_hidden_set_escape_test') then
                        create role scholarsense_iq_hidden_set_escape_test nologin noinherit
                            nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                    end if;
                end
                $escape_role$;
                revoke scholarsense_iq_hidden_set_escape_test
                    from scholarsense_iq_actual_quality_worker_test_login;
                revoke all on ingestion_quality.iq_normalized_fact
                    from scholarsense_iq_hidden_set_escape_test;
                revoke all on schema ingestion_quality
                    from scholarsense_iq_hidden_set_escape_test;
                grant usage on schema ingestion_quality
                    to scholarsense_iq_hidden_set_escape_test;
                grant truncate on ingestion_quality.iq_normalized_fact
                    to scholarsense_iq_hidden_set_escape_test;
                grant scholarsense_iq_hidden_set_escape_test
                    to scholarsense_iq_actual_quality_worker_test_login
                    with inherit false, set true, admin false;
                """);
        try {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> PostgreSqlDataSourceStartupGate.verifyQualityWorker(
                            dataSource(url, qualityLogin), "test", qualityLogin));
            assertEquals(
                    "INGESTION_QUALITY_DATABASE_ROLE_MEMBERSHIP_MISMATCH",
                    failure.getMessage(),
                    urlProperty);

            JdbcTemplate escaped = jdbc(url, qualityLogin);
            assertTrue(Boolean.TRUE.equals(escaped.queryForObject(
                    "select pg_has_role(current_user, ?, 'SET')",
                    Boolean.class,
                    escapeRole)), urlProperty);
            assertTrue(Boolean.TRUE.equals(admin.queryForObject("""
                    select has_schema_privilege(?, 'ingestion_quality', 'USAGE')
                           and has_table_privilege(
                             ?,
                             'ingestion_quality.iq_normalized_fact',
                             'TRUNCATE')
                    """, Boolean.class, escapeRole, escapeRole)), urlProperty);
            assertDomainRejection(
                    "INGESTION_QUALITY_WORKLOAD_ROLE_MISMATCH",
                    () -> escaped.queryForList("""
                            select *
                              from ingestion_quality.iq_inspect_batch_command_precedence(
                                ?, 'receive', ?)
                            """, "0".repeat(64), "sha256:" + "0".repeat(64)));
        } finally {
            admin.execute("""
                    revoke scholarsense_iq_hidden_set_escape_test
                        from scholarsense_iq_actual_quality_worker_test_login;
                    revoke all on ingestion_quality.iq_normalized_fact
                        from scholarsense_iq_hidden_set_escape_test;
                    revoke all on schema ingestion_quality
                        from scholarsense_iq_hidden_set_escape_test;
                    """);
        }
    }

    private static void assertExecutableRoutinesAreClosedSecurityDefiners(
            JdbcTemplate admin, Workload workload, String urlProperty) {
        Set<String> executableRoutines = Set.copyOf(admin.queryForList("""
                select procedure.proname
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and has_function_privilege(?, procedure.oid, 'EXECUTE')
                """, String.class, workload.login()));
        assertEquals(workload.executableRoutines(), executableRoutines,
                urlProperty + " " + workload.kind());

        assertEquals(0, admin.queryForObject("""
                select count(*)
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                  join pg_catalog.pg_roles owner_role
                    on owner_role.oid=procedure.proowner
                 where namespace.nspname='ingestion_quality'
                   and has_function_privilege(?, procedure.oid, 'EXECUTE')
                   and (
                     not procedure.prosecdef
                     or exists (
                       select 1
                         from pg_catalog.aclexplode(coalesce(
                                procedure.proacl,
                                pg_catalog.acldefault('f', procedure.proowner))) privilege
                        where privilege.grantee=0
                          and privilege.privilege_type='EXECUTE')
                     or owner_role.rolcanlogin
                     or owner_role.rolsuper
                     or owner_role.rolcreaterole
                     or owner_role.rolcreatedb
                     or owner_role.rolreplication
                     or owner_role.rolbypassrls
                     or owner_role.rolname <> ?)
                """, Integer.class, workload.login(), BATCH_OWNER),
                urlProperty + " " + workload.kind());
    }

    private static void assertBatchOwnerIsRestricted(
            JdbcTemplate admin, String urlProperty) {
        assertTrue(Boolean.TRUE.equals(admin.queryForObject("""
                select not owner_role.rolcanlogin
                       and not (owner_role.rolsuper
                                or owner_role.rolcreaterole
                                or owner_role.rolcreatedb
                                or owner_role.rolreplication
                                or owner_role.rolbypassrls)
                       and not has_database_privilege(
                           owner_role.rolname, current_database(), 'CREATE')
                       and not has_schema_privilege(
                           owner_role.rolname, 'ingestion_quality', 'CREATE')
                       and not exists (
                         select 1
                           from pg_catalog.pg_auth_members membership
                          where membership.member=owner_role.oid)
                  from pg_catalog.pg_roles owner_role
                 where owner_role.rolname=?
                """, Boolean.class, BATCH_OWNER)), urlProperty + " batch owner");
    }

    private static void assertAuthorityAndRetentionBoundariesExecuteOnlyForTheirOwnLogin(
            String url) {
        JdbcTemplate authority = jdbc(url, login(Kind.CONSUMER_REGISTRY_AUTHORITY));
        JdbcTemplate retention = jdbc(url, login(Kind.RETENTION_EXECUTOR));

        assertDomainRejection(
                "INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID",
                () -> authority.queryForObject("""
                        select ingestion_quality.
                          iq_ingest_quality_snapshot_retention_authority(
                            null::uuid, null::bytea, null::character)
                        """, String.class));
        assertDomainRejection(
                "INGESTION_QUALITY_RETENTION_REQUEST_INVALID",
                () -> retention.queryForObject("""
                        select ingestion_quality.iq_execute_quality_snapshot_retention(
                          null::uuid, null::uuid, null::uuid, null::character,
                          null::character, null::uuid, null::character)
                        """, String.class));

        for (Workload workload : WORKLOADS) {
            JdbcTemplate actual = jdbc(url, workload.login());
            if (workload.kind() != Kind.CONSUMER_REGISTRY_AUTHORITY) {
                assertPermissionDenied(() -> actual.queryForObject("""
                        select ingestion_quality.
                          iq_ingest_quality_snapshot_retention_authority(
                            null::uuid, null::bytea, null::character)
                        """, String.class));
            }
            if (workload.kind() != Kind.RETENTION_EXECUTOR) {
                assertPermissionDenied(() -> actual.queryForObject("""
                        select ingestion_quality.iq_execute_quality_snapshot_retention(
                          null::uuid, null::uuid, null::uuid, null::character,
                          null::character, null::uuid, null::character)
                        """, String.class));
            }
        }
    }

    private static void assertDomainRejection(String expected, Runnable command) {
        DataAccessException failure = assertThrows(DataAccessException.class, command::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.contains(expected), message);
        assertFalse(message.toLowerCase().contains("permission denied"), message);
    }

    private static void assertPermissionDenied(Runnable command) {
        DataAccessException failure = assertThrows(DataAccessException.class, command::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.toLowerCase().contains("permission denied"), message);
    }

    private static void ensureWorkloadLogins(JdbcTemplate admin) {
        admin.execute("""
                do $role_test$
                begin
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_actual_online_test_login') then
                        create role scholarsense_iq_actual_online_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles where
                            rolname='scholarsense_iq_actual_quality_worker_test_login') then
                        create role scholarsense_iq_actual_quality_worker_test_login login inherit;
                    end if;
                    if not exists (select 1 from pg_catalog.pg_roles
                                    where rolname='scholarsense_iq_actual_relay_test_login') then
                        create role scholarsense_iq_actual_relay_test_login login inherit;
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_actual_retention_test_login') then
                        create role scholarsense_iq_actual_retention_test_login login inherit;
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_actual_authority_test_login') then
                        create role scholarsense_iq_actual_authority_test_login login inherit;
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_actual_eligibility_consumer_test_login') then
                        create role scholarsense_iq_actual_eligibility_consumer_test_login
                            login inherit;
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_actual_task_relay_test_login') then
                        create role scholarsense_iq_actual_task_relay_test_login login inherit;
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_actual_recovery_worker_test_login') then
                        create role scholarsense_iq_actual_recovery_worker_test_login login inherit;
                    end if;
                    if not exists (
                        select 1 from pg_catalog.pg_roles
                         where rolname='scholarsense_iq_hidden_set_escape_test') then
                        create role scholarsense_iq_hidden_set_escape_test nologin noinherit
                            nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                    end if;
                end
                $role_test$;
                alter role scholarsense_iq_actual_online_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_actual_quality_worker_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_actual_relay_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_actual_retention_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_actual_authority_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_actual_eligibility_consumer_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_actual_task_relay_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                alter role scholarsense_iq_actual_recovery_worker_test_login login inherit
                    nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
                revoke scholarsense_ingestion_quality_online,
                       scholarsense_ingestion_quality_quality_worker,
                       scholarsense_ingestion_quality_relay,
                       scholarsense_ingestion_quality_retention_executor,
                       scholarsense_ingestion_quality_consumer_registry_authority,
                       scholarsense_ingestion_quality_eligibility_consumer,
                       scholarsense_ingestion_quality_task_relay,
                       scholarsense_ingestion_quality_recovery_worker,
                       scholarsense_ingestion_quality_batch_owner
                    from scholarsense_iq_actual_online_test_login,
                         scholarsense_iq_actual_quality_worker_test_login,
                         scholarsense_iq_actual_relay_test_login,
                         scholarsense_iq_actual_retention_test_login,
                         scholarsense_iq_actual_authority_test_login,
                         scholarsense_iq_actual_eligibility_consumer_test_login,
                         scholarsense_iq_actual_task_relay_test_login,
                         scholarsense_iq_actual_recovery_worker_test_login;
                revoke scholarsense_iq_hidden_set_escape_test
                    from scholarsense_iq_actual_quality_worker_test_login;
                grant scholarsense_ingestion_quality_online
                    to scholarsense_iq_actual_online_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_quality_worker
                    to scholarsense_iq_actual_quality_worker_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_relay
                    to scholarsense_iq_actual_relay_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_retention_executor
                    to scholarsense_iq_actual_retention_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_consumer_registry_authority
                    to scholarsense_iq_actual_authority_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_eligibility_consumer
                    to scholarsense_iq_actual_eligibility_consumer_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_task_relay
                    to scholarsense_iq_actual_task_relay_test_login
                    with inherit true, set false;
                grant scholarsense_ingestion_quality_recovery_worker
                    to scholarsense_iq_actual_recovery_worker_test_login
                    with inherit true, set false;
                """);
    }

    private static String login(Kind kind) {
        return WORKLOADS.stream()
                .filter(workload -> workload.kind() == kind)
                .map(Workload::login)
                .findFirst()
                .orElseThrow();
    }

    private static JdbcTemplate jdbc(String url, String username) {
        return new JdbcTemplate(dataSource(url, username));
    }

    private static DataSource dataSource(String url, String username) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(System.getProperty("scholarsense.audit.pg.password", ""));
        return source;
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " required");
        }
        return value;
    }

    private enum Kind {
        ONLINE,
        QUALITY_WORKER,
        RELAY,
        RETENTION_EXECUTOR,
        CONSUMER_REGISTRY_AUTHORITY,
        ELIGIBILITY_CONSUMER,
        TASK_RELAY,
        RECOVERY_WORKER
    }

    private record Workload(
            Kind kind, String groupRole, String login, Set<String> executableRoutines) {}
}
