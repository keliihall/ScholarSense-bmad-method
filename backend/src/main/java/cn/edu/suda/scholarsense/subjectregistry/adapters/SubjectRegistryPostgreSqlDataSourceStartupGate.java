package cn.edu.suda.scholarsense.subjectregistry.adapters;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Fails startup closed on transport, principal, role or privilege drift. */
public final class SubjectRegistryPostgreSqlDataSourceStartupGate {
    static final String REQUIRED_SERVER_VERSION_NUM = "180004";
    private static final List<String> COLUMN_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "REFERENCES");
    private static final List<String> TABLE_PRIVILEGES = List.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE",
            "REFERENCES", "TRIGGER", "MAINTAIN");
    private static final String PRINCIPAL_QUERY = """
            select current_user, session_user, current_setting('server_version_num'),
                   login.rolcanlogin and login.rolinherit and not (
                     login.rolsuper or login.rolcreaterole or login.rolcreatedb
                     or login.rolreplication or login.rolbypassrls)
                     and not has_database_privilege(current_user,current_database(),'CREATE')
                     and not has_schema_privilege(current_user,'subject_registry','CREATE')
                     and has_schema_privilege(current_user,'subject_registry','USAGE'),
                   pg_has_role(current_user,'scholarsense_subject_registry_online','MEMBER'),
                   pg_has_role(current_user,'scholarsense_subject_registry_online','USAGE'),
                   pg_has_role(current_user,'scholarsense_subject_registry_online','SET'),
                   pg_has_role(current_user,'scholarsense_subject_registry_relay','MEMBER'),
                   pg_has_role(current_user,'scholarsense_subject_registry_relay','USAGE'),
                   pg_has_role(current_user,'scholarsense_subject_registry_relay','SET'),
                   not exists (
                     select 1 from pg_catalog.pg_roles controlled
                      where controlled.rolname in (
                        'scholarsense_subject_registry_online',
                        'scholarsense_subject_registry_relay')
                        and (controlled.rolcanlogin or controlled.rolsuper
                          or controlled.rolcreaterole or controlled.rolcreatedb
                          or controlled.rolreplication or controlled.rolbypassrls))
              from pg_catalog.pg_roles login where login.rolname=current_user
            """;
    private static final String ONLINE_PRIVILEGE_QUERY = privilegeQuery(
            List.of(
                    entry("sr_student_ref_reservation", "SELECT"),
                    entry("sr_identifier_secret", "SELECT"),
                    entry("sr_subject_mapping", "SELECT"),
                    entry("sr_mapping_exception", "SELECT"),
                    entry("sr_correction_event", "SELECT"),
                    entry("sr_correction_target", "SELECT"),
                    entry("sr_repair_idempotency", "SELECT")),
            List.of(),
            List.of(
                    "sr_issue_student_ref(uuid, character varying, timestamp with time zone, "
                            + "uuid, uuid, character varying, character varying)",
                    "sr_record_subject_mapping(uuid, uuid, uuid, uuid, character varying, "
                            + "character varying, character varying, character varying, "
                            + "character varying, character varying, text, character varying, "
                            + "timestamp with time zone, timestamp with time zone, bigint, "
                            + "timestamp with time zone, uuid, uuid, character varying, "
                            + "character varying)",
                    "sr_record_mapping_exception(uuid, uuid, character varying, "
                            + "character varying, character varying, character varying, "
                            + "character varying, character varying, text, character varying, "
                            + "uuid, character varying, character varying, character varying, "
                            + "character varying, text, character varying, character varying, "
                            + "timestamp with time zone, uuid, uuid, character varying, "
                            + "character varying)",
                    "sr_repair_mapping_exception(uuid, bigint, character varying, "
                            + "character varying, uuid, uuid, character varying, "
                            + "character varying, uuid, uuid[], character varying, "
                            + "timestamp with time zone, uuid, character varying, uuid, uuid, "
                            + "character varying, character varying)",
                    "sr_find_pending_recompute_request(uuid)"));
    private static final String RELAY_PRIVILEGE_QUERY = privilegeQuery(
            List.of(
                    entry("sr_local_audit_fact", "SELECT"),
                    entry("sr_local_audit_outbox", "SELECT"),
                    entry("sr_mapping_recompute_outbox", "SELECT")),
            List.of(
                    update("sr_local_audit_outbox", "status"),
                    update("sr_local_audit_outbox", "attempts"),
                    update("sr_local_audit_outbox", "available_at"),
                    update("sr_local_audit_outbox", "claimed_until"),
                    update("sr_local_audit_outbox", "delivered_at"),
                    update("sr_local_audit_outbox", "last_error_code"),
                    update("sr_mapping_recompute_outbox", "status"),
                    update("sr_mapping_recompute_outbox", "attempts"),
                    update("sr_mapping_recompute_outbox", "available_at"),
                    update("sr_mapping_recompute_outbox", "claimed_until"),
                    update("sr_mapping_recompute_outbox", "delivered_at"),
                    update("sr_mapping_recompute_outbox", "last_error_code")),
            List.of());

    private SubjectRegistryPostgreSqlDataSourceStartupGate() {}

    public static SubjectRegistryPostgreSqlConnectionProfile verifyOnline(
            DataSource dataSource, String environment, String identity) {
        return verify(dataSource, environment, identity, Workload.ONLINE);
    }

    public static SubjectRegistryPostgreSqlConnectionProfile verifyRelay(
            DataSource dataSource, String environment, String identity) {
        return verify(dataSource, environment, identity, Workload.RELAY);
    }

    private static SubjectRegistryPostgreSqlConnectionProfile verify(
            DataSource dataSource, String environment, String identity, Workload workload) {
        Objects.requireNonNull(dataSource);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            SubjectRegistryPostgreSqlConnectionProfile profile;
            try (var proof = statement.executeQuery(PRINCIPAL_QUERY)) {
                if (!proof.next()) throw identityUnavailable();
                String current = proof.getString(1);
                String session = proof.getString(2);
                profile = SubjectRegistryPostgreSqlConnectionProfile.validate(
                        environment, connection.getMetaData().getURL(), current, identity);
                if (!Objects.equals(current, session)) {
                    throw mismatch("SESSION_IDENTITY");
                }
                if (!REQUIRED_SERVER_VERSION_NUM.equals(proof.getString(3))) {
                    throw mismatch("SERVER_VERSION");
                }
                if (!proof.getBoolean(4) || !proof.getBoolean(11)) {
                    throw mismatch("ROLE_RESTRICTION");
                }
                boolean onlineMember = proof.getBoolean(5);
                boolean onlineUsage = proof.getBoolean(6);
                boolean onlineSet = proof.getBoolean(7);
                boolean relayMember = proof.getBoolean(8);
                boolean relayUsage = proof.getBoolean(9);
                boolean relaySet = proof.getBoolean(10);
                boolean valid = switch (workload) {
                    case ONLINE -> onlineMember && onlineUsage && !onlineSet
                            && !relayMember && !relayUsage && !relaySet;
                    case RELAY -> relayMember && relayUsage && !relaySet
                            && !onlineMember && !onlineUsage && !onlineSet;
                };
                if (!valid) throw mismatch("ROLE_MEMBERSHIP");
                if (proof.next()) throw identityUnavailable();
            }
            try (var privileges = statement.executeQuery(workload.query)) {
                if (!privileges.next() || !privileges.getBoolean(1) || privileges.next()) {
                    throw mismatch("PRIVILEGE_MATRIX");
                }
            }
            return profile;
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "SUBJECT_REGISTRY_DATABASE_STARTUP_GATE_UNAVAILABLE", unavailable);
        }
    }

    private static String privilegeQuery(
            List<String> tables, List<String> extraColumns, List<String> functions) {
        return """
                with expected_table(table_name,privilege_type) as (values %s),
                expected_column_extra(table_name,column_name,privilege_type) as (
                  select * from (values %s) expected(table_name,column_name,privilege_type)
                   where table_name<>'__none__'),
                actual_table(table_name,privilege_type) as (
                  select tables.table_name, privileges.privilege_type
                    from information_schema.tables tables
                    cross join (values %s) privileges(privilege_type)
                   where tables.table_schema='subject_registry'
                     and tables.table_type='BASE TABLE'
                     and has_table_privilege(current_user,
                       format('%%I.%%I',tables.table_schema,tables.table_name),
                       privileges.privilege_type)),
                expected_column(table_name,column_name,privilege_type) as (
                  select columns.table_name,columns.column_name,expected_table.privilege_type
                    from information_schema.columns columns
                    join expected_table on expected_table.table_name=columns.table_name
                   where columns.table_schema='subject_registry'
                     and expected_table.privilege_type in ('SELECT','INSERT','UPDATE','REFERENCES')
                  union select * from expected_column_extra),
                actual_column(table_name,column_name,privilege_type) as (
                  select columns.table_name,columns.column_name,privileges.privilege_type
                    from information_schema.columns columns
                    cross join (values %s) privileges(privilege_type)
                   where columns.table_schema='subject_registry'
                     and has_column_privilege(current_user,
                       format('%%I.%%I',columns.table_schema,columns.table_name),
                       columns.column_name,privileges.privilege_type)),
                expected_function(function_signature) as (
                  select * from (values %s) expected(function_signature)
                   where function_signature<>'__none__()'),
                actual_function(function_signature) as (
                  select procedure.proname || '(' ||
                         pg_catalog.oidvectortypes(procedure.proargtypes) || ')'
                    from pg_catalog.pg_proc procedure
                    join pg_catalog.pg_namespace namespace
                      on namespace.oid=procedure.pronamespace
                   where namespace.nspname='subject_registry'
                     and has_function_privilege(current_user,procedure.oid,'EXECUTE'))
                select not exists ((select * from actual_table except select * from expected_table)
                         union all (select * from expected_table except select * from actual_table))
                   and not exists ((select * from actual_column except select * from expected_column)
                         union all (select * from expected_column except select * from actual_column))
                   and not exists ((select * from actual_function except select * from expected_function)
                         union all (select * from expected_function except select * from actual_function))
                """.formatted(
                    values(tables, "('__none__','SELECT')"),
                    values(extraColumns, "('__none__','__none__','SELECT')"),
                    privilegeValues(TABLE_PRIVILEGES),
                    privilegeValues(COLUMN_PRIVILEGES),
                    functionValues(functions));
    }

    private static String values(List<String> items, String empty) {
        return items.isEmpty() ? empty : String.join(",\n", items);
    }

    private static String functionValues(List<String> functions) {
        return functions.isEmpty() ? "('__none__()')" : functions.stream()
                .map(value -> "('%s')".formatted(value))
                .reduce((left, right) -> left + ",\n" + right).orElseThrow();
    }

    private static String privilegeValues(List<String> values) {
        return values.stream().map(value -> "('%s')".formatted(value))
                .reduce((left, right) -> left + "," + right).orElseThrow();
    }

    private static String entry(String table, String privilege) {
        return "('%s','%s')".formatted(table, privilege);
    }

    private static String update(String table, String column) {
        return "('%s','%s','UPDATE')".formatted(table, column);
    }

    private static IllegalStateException identityUnavailable() {
        return new IllegalStateException("SUBJECT_REGISTRY_DATABASE_IDENTITY_UNAVAILABLE");
    }

    private static IllegalArgumentException mismatch(String kind) {
        return new IllegalArgumentException("SUBJECT_REGISTRY_DATABASE_" + kind + "_MISMATCH");
    }

    private enum Workload {
        ONLINE(ONLINE_PRIVILEGE_QUERY), RELAY(RELAY_PRIVILEGE_QUERY);
        private final String query;
        Workload(String query) { this.query = query; }
    }
}
