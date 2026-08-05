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
                   not exists (
                     select 1
                       from pg_catalog.pg_roles controlled
                      where controlled.rolname in (
                              'scholarsense_ingestion_quality_online',
                              'scholarsense_ingestion_quality_relay')
                        and (
                          controlled.rolcanlogin
                          or controlled.rolsuper
                          or controlled.rolcreaterole
                          or controlled.rolcreatedb
                          or controlled.rolreplication
                          or controlled.rolbypassrls))
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
                    entry("iq_local_audit_outbox", "INSERT")),
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
                            + "timestamp with time zone, bigint, jsonb)"));
    private static final String RELAY_PRIVILEGE_QUERY = privilegeQuery(
            List.of(
                    entry("iq_local_audit_fact", "SELECT"),
                    entry("iq_local_audit_outbox", "SELECT")),
            List.of(
                    columnEntry("iq_local_audit_outbox", "status", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "attempts", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "available_at", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "claimed_until", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "delivered_at", "UPDATE"),
                    columnEntry("iq_local_audit_outbox", "last_error_code", "UPDATE")),
            List.of("iq_cleanup_expired(timestamp with time zone)"));

    private PostgreSqlDataSourceStartupGate() {}

    public static PostgreSqlConnectionProfile verifyOnline(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(dataSource, environment, expectedWorkloadIdentity, Workload.ONLINE);
    }

    public static PostgreSqlConnectionProfile verifyRelay(
            DataSource dataSource, String environment, String expectedWorkloadIdentity) {
        return verify(dataSource, environment, expectedWorkloadIdentity, Workload.RELAY);
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
                boolean relayMember = proof.getBoolean(8);
                boolean relayUsage = proof.getBoolean(9);
                boolean relaySet = proof.getBoolean(10);
                boolean restrictedGroups = proof.getBoolean(11);
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
                            && !relayMember && !relayUsage && !relaySet;
                    case RELAY -> relayMember && relayUsage && !relaySet
                            && !onlineMember && !onlineUsage && !onlineSet;
                };
                if (!membershipValid) {
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
                  values %s
                ),
                expected_column_extra(table_name, column_name, privilege_type) as (
                  values %s
                ),
                actual_table(table_name, privilege_type) as (
                  select tables.table_name, privileges.privilege_type
                    from information_schema.tables tables
                    cross join (values %s) privileges(privilege_type)
                   where tables.table_schema='ingestion_quality'
                     and tables.table_type='BASE TABLE'
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
                  values %s
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
                String.join(",\n", tablePrivileges),
                String.join(",\n", columnPrivileges),
                privilegeValues(TABLE_PRIVILEGES),
                privilegeValues(COLUMN_PRIVILEGES),
                executableFunctions.stream()
                        .map(function -> "('%s')".formatted(function))
                        .reduce((left, right) -> left + ",\n" + right)
                        .orElseThrow());
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
        RELAY(RELAY_PRIVILEGE_QUERY);

        private final String privilegeQuery;

        Workload(String privilegeQuery) {
            this.privilegeQuery = privilegeQuery;
        }
    }
}
