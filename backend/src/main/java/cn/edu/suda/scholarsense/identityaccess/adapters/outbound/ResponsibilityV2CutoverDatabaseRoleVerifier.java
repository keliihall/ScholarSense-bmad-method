package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup proof that V2 cutover runs as a dedicated login with exactly one
 * application group role and cannot collapse onto either routine principal.
 */
public final class ResponsibilityV2CutoverDatabaseRoleVerifier {
    public static final String CUTOVER_ROLE =
            "scholarsense_identity_responsibility_v2_cutover";
    private static final String SYNC_ROLE =
            "scholarsense_identity_sync_worker";
    private static final String CONSUMER_ROLE =
            "scholarsense_identity_invalidation_consumer";

    private ResponsibilityV2CutoverDatabaseRoleVerifier() {}

    public static void verify(
            JdbcTemplate producer,
            TransactionTemplate producerTransactions,
            AccessInvalidationConsumerDatabase consumerDatabase,
            ResponsibilityV2CutoverDatabase cutoverDatabase) {
        ResponsibilityV2CutoverDatabaseTransactionBoundary.require(
                cutoverDatabase.jdbc(),
                cutoverDatabase.transactions(),
                "RESPONSIBILITY_V2_CUTOVER_TRANSACTION_BOUNDARY_INVALID");
        Identity producerIdentity = producerTransactions.execute(
                ignored -> identity(producer));
        Identity consumerIdentity = consumerDatabase.transactions().execute(
                ignored -> identity(consumerDatabase.jdbc()));
        PrincipalProof cutoverProof = cutoverDatabase.transactions().execute(
                ignored -> cutoverProof(cutoverDatabase.jdbc()));
        if (producerIdentity == null
                || consumerIdentity == null
                || cutoverProof == null) {
            throw new IllegalStateException(
                    "RESPONSIBILITY_V2_CUTOVER_DATABASE_IDENTITY_UNAVAILABLE");
        }
        Identity cutoverIdentity = cutoverProof.identity();
        boolean valid = cutoverProof.valid()
                && producerIdentity.currentUser().equals(
                        producerIdentity.sessionUser())
                && consumerIdentity.currentUser().equals(
                        consumerIdentity.sessionUser())
                && cutoverIdentity.currentUser().equals(
                        cutoverIdentity.sessionUser())
                && producerIdentity.database().equals(
                        consumerIdentity.database())
                && producerIdentity.database().equals(
                        cutoverIdentity.database())
                && producerIdentity.systemIdentifier().equals(
                        consumerIdentity.systemIdentifier())
                && producerIdentity.systemIdentifier().equals(
                        cutoverIdentity.systemIdentifier())
                && !cutoverIdentity.sessionUser().equals(
                        producerIdentity.sessionUser())
                && !cutoverIdentity.sessionUser().equals(
                        consumerIdentity.sessionUser())
                && !producerIdentity.sessionUser().equals(
                        consumerIdentity.sessionUser());
        if (!valid) {
            throw new IllegalStateException(
                    "RESPONSIBILITY_V2_CUTOVER_DATABASE_ROLE_ISOLATION_INVALID");
        }
    }

    private static PrincipalProof cutoverProof(JdbcTemplate jdbc) {
        Identity identity = identity(jdbc);
        boolean valid = restrictedLogin(jdbc)
                && onlyMemberOf(jdbc, CUTOVER_ROLE)
                && restrictedGroupRole(jdbc, CUTOVER_ROLE)
                && groupRoleHasNoParent(jdbc, CUTOVER_ROLE)
                && !memberOf(jdbc, SYNC_ROLE)
                && !memberOf(jdbc, CONSUMER_ROLE);
        return new PrincipalProof(identity, valid);
    }

    private static Identity identity(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                """
                select current_database(), system_identifier::text,
                       current_user, session_user
                  from pg_catalog.pg_control_system()
                """,
                (rs, row) -> new Identity(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)));
    }

    private static boolean restrictedLogin(JdbcTemplate jdbc) {
        Boolean restricted = jdbc.queryForObject(
                """
                select roles.rolcanlogin
                       and not (
                           roles.rolsuper
                           or roles.rolcreaterole
                           or roles.rolcreatedb
                           or roles.rolreplication
                           or roles.rolbypassrls)
                       and not has_database_privilege(
                           current_user, current_database(), 'CREATE')
                       and not has_schema_privilege(
                           current_user, 'identity_access', 'CREATE')
                       and has_schema_privilege(
                           current_user, 'identity_access', 'USAGE')
                  from pg_catalog.pg_roles roles
                 where roles.rolname=current_user
                """,
                Boolean.class);
        return Boolean.TRUE.equals(restricted);
    }

    private static boolean restrictedGroupRole(
            JdbcTemplate jdbc, String role) {
        Boolean restricted = jdbc.queryForObject(
                """
                select not roles.rolcanlogin
                       and not (
                           roles.rolsuper
                           or roles.rolcreaterole
                           or roles.rolcreatedb
                           or roles.rolreplication
                           or roles.rolbypassrls)
                       and not has_database_privilege(
                           ?, current_database(), 'CREATE')
                       and not has_schema_privilege(
                           ?, 'identity_access', 'CREATE')
                       and has_schema_privilege(
                           ?, 'identity_access', 'USAGE')
                  from pg_catalog.pg_roles roles
                 where roles.rolname=?
                """,
                Boolean.class,
                role,
                role,
                role,
                role);
        return Boolean.TRUE.equals(restricted);
    }

    private static boolean memberOf(JdbcTemplate jdbc, String role) {
        Boolean member = jdbc.queryForObject(
                "select pg_has_role(current_user, ?, 'member')",
                Boolean.class,
                role);
        return Boolean.TRUE.equals(member);
    }

    private static boolean onlyMemberOf(
            JdbcTemplate jdbc, String allowedRole) {
        if (!memberOf(jdbc, allowedRole)) {
            return false;
        }
        Boolean exact = jdbc.queryForObject(
                """
                select not exists (
                    select 1
                      from pg_catalog.pg_roles candidate
                     where candidate.rolname <> current_user
                       and candidate.rolname <> ?
                       and pg_has_role(
                           current_user, candidate.oid, 'member'))
                """,
                Boolean.class,
                allowedRole);
        return Boolean.TRUE.equals(exact);
    }

    private static boolean groupRoleHasNoParent(
            JdbcTemplate jdbc, String role) {
        Boolean isolated = jdbc.queryForObject(
                """
                select not exists (
                    select 1
                      from pg_catalog.pg_auth_members membership
                      join pg_catalog.pg_roles member
                        on member.oid=membership.member
                     where member.rolname=?)
                """,
                Boolean.class,
                role);
        return Boolean.TRUE.equals(isolated);
    }

    private record Identity(
            String database,
            String systemIdentifier,
            String currentUser,
            String sessionUser) {}

    private record PrincipalProof(Identity identity, boolean valid) {}
}
