package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fail-closed startup proof that producer and local consumer are distinct DB
 * principals.
 */
public final class AccessInvalidationDatabaseRoleVerifier {
    public static final String RESPONSIBILITY_V2_CUTOVER_ROLE =
            "scholarsense_identity_responsibility_v2_cutover";

    private AccessInvalidationDatabaseRoleVerifier() {}

    public static void verify(
            JdbcTemplate producer,
            TransactionTemplate producerTransactions,
            AccessInvalidationConsumerDatabase consumerDatabase) {
        verifyProducerTransactionBoundary(
                producer, producerTransactions);
        JdbcTemplate consumer = consumerDatabase.jdbc();
        PrincipalProof producerProof = producerTransactions.execute(
                ignored -> producerProof(producer));
        PrincipalProof consumerProof = consumerDatabase.transactions()
                .execute(ignored -> consumerProof(consumer));
        if (producerProof == null || consumerProof == null) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_DATABASE_IDENTITY_UNAVAILABLE");
        }
        Identity producerIdentity = producerProof.identity();
        Identity consumerIdentity = consumerProof.identity();
        boolean valid = producerProof.valid()
                && consumerProof.valid()
                && producerIdentity.database()
                        .equals(consumerIdentity.database())
                && producerIdentity.systemIdentifier()
                        .equals(consumerIdentity.systemIdentifier())
                && producerIdentity.user()
                        .equals(producerIdentity.sessionUser())
                && consumerIdentity.user()
                        .equals(consumerIdentity.sessionUser())
                && !producerIdentity.sessionUser()
                        .equals(consumerIdentity.sessionUser());
        if (!valid) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_DATABASE_ROLE_ISOLATION_INVALID:"
                            + (!producerProof.valid()
                                    ? producerFailure(producer)
                                    : consumerFailure(consumer)));
        }
    }

    public static void verifyProducerTransactionBoundary(
            JdbcTemplate producer,
            TransactionTemplate producerTransactions) {
        AccessInvalidationDatabaseTransactionBoundary.require(
                producer,
                producerTransactions,
                "ACCESS_INVALIDATION_PRODUCER_TRANSACTION_BOUNDARY_INVALID");
    }

    /**
     * Fail-closed proof for the separately wired one-shot V2 cutover login.
     */
    public static void verifyResponsibilityV2Cutover(
            JdbcTemplate cutover,
            TransactionTemplate cutoverTransactions) {
        AccessInvalidationDatabaseTransactionBoundary.require(
                cutover,
                cutoverTransactions,
                "RESPONSIBILITY_V2_CUTOVER_TRANSACTION_BOUNDARY_INVALID");
        PrincipalProof proof = cutoverTransactions.execute(
                ignored -> cutoverProof(cutover));
        if (proof == null
                || !proof.valid()
                || !proof.identity().user().equals(
                        proof.identity().sessionUser())) {
            throw new IllegalStateException(
                    "RESPONSIBILITY_V2_CUTOVER_DATABASE_ROLE_ISOLATION_INVALID");
        }
    }

    private static PrincipalProof producerProof(JdbcTemplate producer) {
        Identity identity = identity(producer);
        boolean valid = restrictedPrincipal(producer)
                && onlyMemberOf(
                        producer,
                        "scholarsense_identity_sync_worker")
                && restrictedGroupRole(
                        producer,
                        "scholarsense_identity_sync_worker")
                && requiredProducerPrivileges(producer)
                && producerCannotForgeConsumerEvidence(producer)
                && cutoverGroupProof(producer);
        return new PrincipalProof(identity, valid);
    }

    private static String producerFailure(JdbcTemplate producer) {
        if (!restrictedPrincipal(producer)) {
            return "producer-principal";
        }
        if (!onlyMemberOf(producer, "scholarsense_identity_sync_worker")) {
            return "producer-membership";
        }
        if (!restrictedGroupRole(
                producer, "scholarsense_identity_sync_worker")) {
            return "producer-group";
        }
        if (!requiredProducerPrivileges(producer)) {
            return "producer-required-privileges";
        }
        if (!producerCannotForgeConsumerEvidence(producer)) {
            return "producer-forbidden-privileges";
        }
        if (!cutoverGroupProof(producer)) {
            return cutoverGroupFailure(producer);
        }
        return "producer-identity";
    }

    private static String consumerFailure(JdbcTemplate consumer) {
        if (!restrictedPrincipal(consumer)) {
            return "consumer-principal";
        }
        if (!onlyMemberOf(
                consumer,
                "scholarsense_identity_invalidation_consumer")) {
            return "consumer-membership";
        }
        if (!restrictedGroupRole(
                consumer,
                "scholarsense_identity_invalidation_consumer")) {
            return "consumer-group";
        }
        if (!requiredConsumerPrivileges(consumer)) {
            return "consumer-required-privileges";
        }
        if (!consumerCannotMutateProducerState(consumer)) {
            return "consumer-producer-privileges";
        }
        if (!consumerCannotAccessOtherIdentityTables(consumer)) {
            return "consumer-identity-privileges";
        }
        return "consumer-identity";
    }

    private static String cutoverGroupFailure(JdbcTemplate jdbc) {
        String role = RESPONSIBILITY_V2_CUTOVER_ROLE;
        if (!restrictedGroupRole(jdbc, role)) {
            return "cutover-group-principal";
        }
        if (!groupRoleHasNoGroupMembership(jdbc, role)) {
            return "cutover-group-membership";
        }
        if (!requiredCutoverPrivileges(jdbc, role)) {
            return "cutover-required-privileges";
        }
        if (!cutoverHasNoRoutineSyncPrivileges(jdbc, role)) {
            return "cutover-routine-privileges";
        }
        if (!cutoverOnlyUsesApprovedTables(jdbc, role)) {
            return "cutover-unapproved-table";
        }
        return "cutover-group-unknown";
    }

    private static PrincipalProof consumerProof(JdbcTemplate consumer) {
        Identity identity = identity(consumer);
        boolean valid = restrictedPrincipal(consumer)
                && onlyMemberOf(
                        consumer,
                        "scholarsense_identity_invalidation_consumer")
                && restrictedGroupRole(
                        consumer,
                        "scholarsense_identity_invalidation_consumer")
                && requiredConsumerPrivileges(consumer)
                && consumerCannotMutateProducerState(consumer)
                && consumerCannotAccessOtherIdentityTables(consumer);
        return new PrincipalProof(identity, valid);
    }

    private static PrincipalProof cutoverProof(JdbcTemplate cutover) {
        Identity identity = identity(cutover);
        boolean valid = restrictedPrincipal(cutover)
                && onlyMemberOf(
                        cutover, RESPONSIBILITY_V2_CUTOVER_ROLE)
                && cutoverGroupProof(cutover)
                && cutoverOnlyUsesApprovedTables(
                        cutover, identity.user());
        return new PrincipalProof(identity, valid);
    }

    private static boolean cutoverGroupProof(JdbcTemplate jdbc) {
        return restrictedGroupRole(
                        jdbc, RESPONSIBILITY_V2_CUTOVER_ROLE)
                && groupRoleHasNoGroupMembership(
                        jdbc, RESPONSIBILITY_V2_CUTOVER_ROLE)
                && requiredCutoverPrivileges(
                        jdbc, RESPONSIBILITY_V2_CUTOVER_ROLE)
                && cutoverHasNoRoutineSyncPrivileges(
                        jdbc, RESPONSIBILITY_V2_CUTOVER_ROLE)
                && cutoverOnlyUsesApprovedTables(
                        jdbc, RESPONSIBILITY_V2_CUTOVER_ROLE);
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

    private static boolean privilege(
            JdbcTemplate jdbc, String table, String privilege) {
        Boolean allowed = jdbc.queryForObject(
                "select has_table_privilege(current_user, ?, ?)",
                Boolean.class,
                "identity_access." + table,
                privilege);
        return Boolean.TRUE.equals(allowed);
    }

    private static boolean memberOf(
            JdbcTemplate jdbc, String role) {
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
        Boolean onlyAllowedMembership = jdbc.queryForObject(
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
        return Boolean.TRUE.equals(onlyAllowedMembership);
    }

    private static boolean restrictedPrincipal(JdbcTemplate jdbc) {
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
                 where roles.rolname = current_user
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
                 where roles.rolname = ?
                """,
                Boolean.class,
                role,
                role,
                role,
                role);
        return Boolean.TRUE.equals(restricted);
    }

    private static boolean requiredProducerPrivileges(
            JdbcTemplate producer) {
        return privileges(
                        producer,
                        "ia_responsibility_lineage_binding",
                        "SELECT", "INSERT")
                && privileges(
                        producer,
                        "ia_authoritative_role_binding_history",
                        "SELECT", "INSERT")
                && privileges(
                        producer,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "SELECT")
                && hasExactlyColumnPrivileges(
                        producer,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "INSERT",
                        "source_id",
                        "feed_id",
                        "partition_id",
                        "consumer_projection",
                        "source_version",
                        "source_watermark",
                        "aggregate_version",
                        "replay_started_at_zero",
                        "updated_at",
                        "trace_id")
                && hasExactlyColumnPrivileges(
                        producer,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "UPDATE",
                        "source_version",
                        "source_watermark",
                        "aggregate_version",
                        "last_successful_at",
                        "replay_started_at_zero",
                        "updated_at",
                        "trace_id")
                && privileges(
                        producer,
                        "ia_responsibility_v2_shadow_current",
                        "SELECT", "INSERT", "UPDATE")
                && privileges(
                        producer,
                        "ia_responsibility_v2_shadow_invalidation_fact",
                        "SELECT", "INSERT")
                && privileges(
                        producer,
                        "ia_responsibility_v2_shadow_lineage_head",
                        "SELECT", "INSERT", "UPDATE")
                && privileges(
                        producer,
                        "ia_responsibility_v2_source_fact",
                        "SELECT", "INSERT")
                && privileges(
                        producer,
                        "ia_access_invalidation_fact",
                        "SELECT", "INSERT")
                && privileges(
                        producer,
                        "ia_access_invalidation_lineage_head",
                        "SELECT", "INSERT", "UPDATE")
                && privileges(
                        producer,
                        "ia_access_invalidation_outbox",
                        "SELECT", "INSERT", "UPDATE")
                && privileges(
                        producer,
                        "ia_access_invalidation_delivery_attempt",
                        "SELECT", "INSERT")
                && privileges(
                        producer,
                        "ia_access_invalidation_consumer_registry",
                        "SELECT")
                && privileges(
                        producer,
                        "ia_access_invalidation_observed_ack",
                        "SELECT", "INSERT")
                && privileges(
                        producer,
                        "ia_access_invalidation_propagation",
                        "SELECT", "INSERT", "UPDATE")
                && privileges(
                        producer,
                        "ia_access_invalidation_consumer_applied_outbox",
                        "SELECT")
                && privileges(
                        producer,
                        "ia_access_invalidation_local_inbox",
                        "SELECT")
                && privileges(
                        producer,
                        "ia_access_invalidation_local_apply",
                        "SELECT")
                && privileges(
                        producer,
                        "ia_access_invalidation_consumer_watermark",
                        "SELECT")
                && privileges(
                        producer,
                        "ia_access_invalidation_local_fence",
                        "SELECT")
                && privileges(
                        producer,
                        "ia_access_invalidation_backfill_request",
                        "SELECT")
                && privileges(
                        producer,
                        "ia_access_invalidation_job",
                        "SELECT", "INSERT", "UPDATE")
                && privileges(
                        producer,
                        "ia_access_invalidation_reconciliation",
                        "SELECT", "INSERT")
                && columnPrivilege(
                        producer,
                        "ia_access_invalidation_consumer_applied_outbox",
                        "status",
                        "UPDATE")
                && columnPrivilege(
                        producer,
                        "ia_access_invalidation_consumer_applied_outbox",
                        "attempts",
                        "UPDATE");
    }

    private static boolean producerCannotForgeConsumerEvidence(
            JdbcTemplate producer) {
        return noMutation(
                        producer,
                        "ia_access_invalidation_consumer_registry",
                        "ia_access_invalidation_local_inbox",
                        "ia_access_invalidation_local_apply",
                        "ia_access_invalidation_consumer_watermark",
                        "ia_access_invalidation_local_fence",
                        "ia_access_invalidation_backfill_request")
                && noTableAccess(
                        producer,
                        "ia_responsibility_v2_reconciliation_snapshot",
                        "ia_responsibility_v2_snapshot_entry",
                        "ia_responsibility_v2_snapshot_lineage")
                && appendOnly(
                        producer,
                        "ia_responsibility_lineage_binding",
                        "ia_authoritative_role_binding_history",
                        "ia_responsibility_v2_source_fact",
                        "ia_responsibility_v2_shadow_invalidation_fact",
                        "ia_access_invalidation_fact",
                        "ia_access_invalidation_delivery_attempt",
                        "ia_access_invalidation_observed_ack",
                        "ia_access_invalidation_reconciliation")
                && mutableWithoutDelete(
                        producer,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "ia_responsibility_v2_shadow_current",
                        "ia_responsibility_v2_shadow_lineage_head",
                        "ia_access_invalidation_lineage_head",
                        "ia_access_invalidation_outbox",
                        "ia_access_invalidation_propagation",
                        "ia_access_invalidation_job")
                && !privilege(
                        producer,
                        "ia_responsibility_current",
                        "DELETE")
                && columnsLackPrivilege(
                        producer,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "UPDATE",
                        "reconciliation_status",
                        "reconciliation_watermark",
                        "reconciliation_expected_count",
                        "reconciliation_actual_count",
                        "reconciliation_expected_digest",
                        "reconciliation_actual_digest",
                        "reconciliation_lineage_conflicts",
                        "reconciliation_snapshot_id",
                        "reconciliation_snapshot_source_version",
                        "reconciliation_envelope_digest",
                        "reconciliation_signature_digest",
                        "reconciliation_expected_lineage_count",
                        "reconciliation_actual_lineage_count",
                        "reconciliation_expected_lineage_digest",
                        "reconciliation_actual_lineage_digest",
                        "reconciliation_live_source_version",
                        "reconciliation_live_watermark",
                        "invalidation_materialized",
                        "invalidation_materialized_count",
                        "active",
                        "reconciled_at",
                        "activated_at")
                && appendOnlyEvidenceOutboxForProducer(producer);
    }

    private static boolean appendOnlyEvidenceOutboxForProducer(
            JdbcTemplate producer) {
        String table =
                "ia_access_invalidation_consumer_applied_outbox";
        return !anyColumnPrivilege(producer, table, "INSERT")
                && !privilege(producer, table, "DELETE")
                && !privilege(producer, table, "TRUNCATE")
                && noStructuralPrivileges(producer, table)
                && Stream.of(
                                "applied_fact_id",
                                "apply_id",
                                "consumer_id",
                                "event_id",
                                "applied_version",
                                "payload_digest",
                                "next_attempt_at",
                                "created_at",
                                "trace_id")
                        .noneMatch(column -> columnPrivilege(
                                producer, table, column, "UPDATE"));
    }

    private static boolean requiredCutoverPrivileges(
            JdbcTemplate jdbc, String role) {
        return rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_lineage_binding",
                        "SELECT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_source_fact",
                        "SELECT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "SELECT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_shadow_current",
                        "SELECT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_shadow_invalidation_fact",
                        "SELECT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_shadow_lineage_head",
                        "SELECT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_reconciliation_snapshot",
                        "SELECT", "INSERT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_snapshot_entry",
                        "SELECT", "INSERT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_snapshot_lineage",
                        "SELECT", "INSERT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_cutover_command",
                        "SELECT", "INSERT")
                && roleHasExactlyColumnPrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_cutover_command",
                        "UPDATE",
                        "status",
                        "reason_code",
                        "snapshot_id",
                        "updated_at",
                        "completed_at")
                && roleHasExactlyColumnPrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "UPDATE",
                        "reconciliation_status",
                        "reconciliation_watermark",
                        "reconciliation_expected_count",
                        "reconciliation_actual_count",
                        "reconciliation_expected_digest",
                        "reconciliation_actual_digest",
                        "reconciliation_lineage_conflicts",
                        "reconciliation_snapshot_id",
                        "reconciliation_snapshot_source_version",
                        "reconciliation_envelope_digest",
                        "reconciliation_signature_digest",
                        "reconciliation_expected_lineage_count",
                        "reconciliation_actual_lineage_count",
                        "reconciliation_expected_lineage_digest",
                        "reconciliation_actual_lineage_digest",
                        "reconciliation_live_source_version",
                        "reconciliation_live_watermark",
                        "invalidation_materialized",
                        "invalidation_materialized_count",
                        "active",
                        "reconciled_at",
                        "activated_at",
                        "trace_id")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_current",
                        "INSERT", "DELETE")
                && roleHasExactlyColumnPrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_current",
                        "SELECT",
                        "source_id",
                        "feed_id",
                        "partition_id",
                        "consumer_projection")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_identity_sync_checkpoint",
                        "SELECT", "INSERT")
                && roleHasExactlyColumnPrivileges(
                        jdbc,
                        role,
                        "ia_identity_sync_checkpoint",
                        "UPDATE",
                        "source_version",
                        "source_watermark",
                        "aggregate_version",
                        "last_successful_at",
                        "health",
                        "freshness",
                        "updated_at",
                        "trace_id",
                        "retention_effective_at")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_access_invalidation_fact",
                        "SELECT", "INSERT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_access_invalidation_lineage_head",
                        "SELECT", "INSERT")
                && roleHasExactlyColumnPrivileges(
                        jdbc,
                        role,
                        "ia_access_invalidation_lineage_head",
                        "UPDATE",
                        "current_event_id",
                        "current_version",
                        "fencing_token",
                        "updated_at",
                        "trace_id")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_access_invalidation_outbox",
                        "INSERT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_access_invalidation_consumer_registry",
                        "SELECT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_access_invalidation_propagation",
                        "INSERT")
                && rolePrivileges(
                        jdbc,
                        role,
                        "ia_access_invalidation_job",
                        "SELECT", "INSERT")
                && roleHasExactlyColumnPrivileges(
                        jdbc,
                        role,
                        "ia_local_audit_fact",
                        "INSERT",
                        "audit_id",
                        "actor_pseudonym",
                        "session_pseudonym",
                        "action",
                        "result",
                        "occurred_at",
                        "source_ip_pseudonym",
                        "profile_version",
                        "schema_version",
                        "producer_module",
                        "actor_type",
                        "actor_search_token",
                        "role_ids",
                        "authorization_context",
                        "object_type",
                        "object_search_token",
                        "outcome",
                        "reason_code",
                        "purpose",
                        "projection_scope",
                        "recorded_at",
                        "time_source_profile",
                        "source_ip_search_token",
                        "tokenization_profile_version",
                        "key_version",
                        "aggregate_type",
                        "aggregate_id_search_token",
                        "aggregate_version",
                        "idempotency_key_digest",
                        "trace_id",
                        "policy_versions",
                        "retention_schedule_version")
                && roleHasExactlyColumnPrivileges(
                        jdbc,
                        role,
                        "ia_local_audit_outbox",
                        "INSERT",
                        "event_id",
                        "audit_id",
                        "event_type",
                        "schema_version",
                        "envelope",
                        "created_at",
                        "delivery_status",
                        "attempts",
                        "next_attempt_at");
    }

    private static boolean cutoverHasNoRoutineSyncPrivileges(
            JdbcTemplate jdbc, String role) {
        return roleReadOnly(
                        jdbc,
                        role,
                        "ia_responsibility_lineage_binding",
                        "ia_responsibility_v2_source_fact",
                        "ia_responsibility_v2_shadow_current",
                        "ia_responsibility_v2_shadow_invalidation_fact",
                        "ia_responsibility_v2_shadow_lineage_head")
                && roleAppendOnly(
                        jdbc,
                        role,
                        "ia_responsibility_v2_reconciliation_snapshot",
                        "ia_responsibility_v2_snapshot_entry",
                        "ia_responsibility_v2_snapshot_lineage")
                && !roleAnyColumnPrivilege(
                        jdbc,
                        role,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "INSERT")
                && roleColumnsLackPrivilege(
                        jdbc,
                        role,
                        "ia_responsibility_v2_shadow_checkpoint",
                        "UPDATE",
                        "source_version",
                        "source_watermark",
                        "aggregate_version",
                        "last_successful_at",
                        "replay_started_at_zero",
                        "updated_at")
                && roleNoDeleteTruncateOrStructural(
                        jdbc,
                        role,
                        "ia_responsibility_v2_cutover_command",
                        "ia_responsibility_v2_shadow_checkpoint",
                        "ia_identity_sync_checkpoint",
                        "ia_access_invalidation_lineage_head")
                && roleHasExactlyColumnPrivileges(
                        jdbc,
                        role,
                        "ia_responsibility_current",
                        "SELECT",
                        "source_id",
                        "feed_id",
                        "partition_id",
                        "consumer_projection")
                && !roleAnyColumnPrivilege(
                        jdbc,
                        role,
                        "ia_responsibility_current",
                        "UPDATE")
                && !rolePrivilege(
                        jdbc,
                        role,
                        "ia_responsibility_current",
                        "TRUNCATE")
                && roleNoStructuralPrivileges(
                        jdbc, role, "ia_responsibility_current")
                && roleAppendOnly(
                        jdbc,
                        role,
                        "ia_access_invalidation_fact",
                        "ia_access_invalidation_job")
                && roleReadOnly(
                        jdbc,
                        role,
                        "ia_access_invalidation_consumer_registry")
                && roleInsertOnly(
                        jdbc,
                        role,
                        "ia_access_invalidation_outbox",
                        "ia_access_invalidation_propagation",
                        "ia_local_audit_fact",
                        "ia_local_audit_outbox");
    }

    private static boolean groupRoleHasNoGroupMembership(
            JdbcTemplate jdbc, String role) {
        Boolean isolated = jdbc.queryForObject(
                """
                select not exists (
                    select 1
                      from pg_catalog.pg_auth_members membership
                      join pg_catalog.pg_roles granted
                        on granted.oid=membership.roleid
                      join pg_catalog.pg_roles member
                        on member.oid=membership.member
                     where member.rolname=?
                        or (granted.rolname=?
                            and not member.rolcanlogin))
                """,
                Boolean.class,
                role,
                role);
        return Boolean.TRUE.equals(isolated);
    }

    private static boolean cutoverOnlyUsesApprovedTables(
            JdbcTemplate jdbc, String role) {
        Boolean isolated = jdbc.queryForObject(
                """
                select not exists (
                    select 1
                      from pg_catalog.pg_class relation
                      join pg_catalog.pg_namespace namespace
                        on namespace.oid=relation.relnamespace
                     where namespace.nspname='identity_access'
                       and relation.relkind in ('r', 'p', 'v', 'm', 'f')
                       and relation.relname not in (
                           'ia_responsibility_lineage_binding',
                           'ia_responsibility_v2_source_fact',
                           'ia_responsibility_v2_reconciliation_snapshot',
                           'ia_responsibility_v2_snapshot_entry',
                           'ia_responsibility_v2_snapshot_lineage',
                           'ia_responsibility_v2_cutover_command',
                           'ia_responsibility_v2_shadow_checkpoint',
                           'ia_responsibility_v2_shadow_current',
                           'ia_responsibility_v2_shadow_invalidation_fact',
                           'ia_responsibility_v2_shadow_lineage_head',
                           'ia_responsibility_current',
                           'ia_identity_sync_checkpoint',
                           'ia_access_invalidation_fact',
                           'ia_access_invalidation_lineage_head',
                           'ia_access_invalidation_outbox',
                           'ia_access_invalidation_consumer_registry',
                           'ia_access_invalidation_propagation',
                           'ia_access_invalidation_job',
                           'ia_local_audit_fact',
                           'ia_local_audit_outbox')
                       and (
                           has_any_column_privilege(?, relation.oid, 'SELECT')
                           or has_any_column_privilege(?, relation.oid, 'INSERT')
                           or has_any_column_privilege(?, relation.oid, 'UPDATE')
                           or has_any_column_privilege(?, relation.oid, 'REFERENCES')
                           or has_table_privilege(?, relation.oid, 'DELETE')
                           or has_table_privilege(?, relation.oid, 'TRUNCATE')
                           or has_table_privilege(?, relation.oid, 'TRIGGER')
                           or has_table_privilege(?, relation.oid, 'MAINTAIN')))
                """,
                Boolean.class,
                role,
                role,
                role,
                role,
                role,
                role,
                role,
                role);
        return Boolean.TRUE.equals(isolated);
    }

    private static boolean requiredConsumerPrivileges(
            JdbcTemplate consumer) {
        return privileges(
                        consumer,
                        "ia_access_invalidation_fact",
                        "SELECT")
                && privileges(
                        consumer,
                        "ia_access_invalidation_consumer_registry",
                        "SELECT")
                && privileges(
                        consumer,
                        "ia_access_invalidation_local_inbox",
                        "SELECT", "INSERT")
                && privileges(
                        consumer,
                        "ia_access_invalidation_local_apply",
                        "SELECT", "INSERT")
                && privileges(
                        consumer,
                        "ia_access_invalidation_consumer_applied_outbox",
                        "SELECT", "INSERT")
                && privileges(
                        consumer,
                        "ia_access_invalidation_consumer_watermark",
                        "SELECT", "INSERT", "UPDATE")
                && privileges(
                        consumer,
                        "ia_access_invalidation_local_fence",
                        "SELECT", "INSERT", "UPDATE")
                && privileges(
                        consumer,
                        "ia_access_invalidation_backfill_request",
                        "SELECT", "INSERT", "UPDATE")
                && appendOnly(
                        consumer,
                        "ia_access_invalidation_local_inbox",
                        "ia_access_invalidation_local_apply",
                        "ia_access_invalidation_consumer_applied_outbox")
                && mutableWithoutDelete(
                        consumer,
                        "ia_access_invalidation_consumer_watermark",
                        "ia_access_invalidation_local_fence",
                        "ia_access_invalidation_backfill_request");
    }

    private static boolean consumerCannotMutateProducerState(
            JdbcTemplate consumer) {
        return noMutation(
                        consumer,
                        "ia_access_invalidation_fact",
                        "ia_access_invalidation_consumer_registry")
                && noTableAccess(
                        consumer,
                        "ia_access_invalidation_lineage_head",
                        "ia_access_invalidation_outbox",
                        "ia_access_invalidation_delivery_attempt",
                        "ia_access_invalidation_observed_ack",
                        "ia_access_invalidation_propagation",
                        "ia_access_invalidation_job",
                        "ia_access_invalidation_reconciliation");
    }

    private static boolean consumerCannotAccessOtherIdentityTables(
            JdbcTemplate consumer) {
        Boolean isolated = consumer.queryForObject(
                """
                select not exists (
                    select 1
                      from pg_catalog.pg_class relation
                      join pg_catalog.pg_namespace namespace
                        on namespace.oid=relation.relnamespace
                     where namespace.nspname='identity_access'
                       and relation.relkind in ('r', 'p', 'v', 'm', 'f')
                       and relation.relname not in (
                           'ia_access_invalidation_fact',
                           'ia_access_invalidation_consumer_registry',
                           'ia_access_invalidation_local_inbox',
                           'ia_access_invalidation_local_apply',
                           'ia_access_invalidation_consumer_applied_outbox',
                           'ia_access_invalidation_consumer_watermark',
                           'ia_access_invalidation_local_fence',
                           'ia_access_invalidation_backfill_request')
                       and (
                           has_any_column_privilege(
                               current_user, relation.oid, 'SELECT')
                           or has_any_column_privilege(
                               current_user, relation.oid, 'INSERT')
                           or has_any_column_privilege(
                               current_user, relation.oid, 'UPDATE')
                           or has_any_column_privilege(
                               current_user, relation.oid, 'REFERENCES')
                           or has_table_privilege(
                               current_user, relation.oid, 'DELETE')
                           or has_table_privilege(
                               current_user, relation.oid, 'TRUNCATE')
                           or has_table_privilege(
                               current_user, relation.oid, 'TRIGGER')
                           or has_table_privilege(
                               current_user, relation.oid, 'MAINTAIN')))
                """,
                Boolean.class);
        return Boolean.TRUE.equals(isolated);
    }

    private static boolean privileges(
            JdbcTemplate jdbc,
            String table,
            String... required) {
        return Stream.of(required)
                .allMatch(value -> privilege(jdbc, table, value));
    }

    private static boolean hasExactlyColumnPrivileges(
            JdbcTemplate jdbc,
            String table,
            String privilege,
            String... columns) {
        Set<String> granted = Set.copyOf(jdbc.queryForList(
                """
                select attribute.attname
                  from pg_catalog.pg_attribute attribute
                  join pg_catalog.pg_class relation
                    on relation.oid=attribute.attrelid
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=relation.relnamespace
                 where namespace.nspname='identity_access'
                   and relation.relname=?
                   and attribute.attnum>0
                   and not attribute.attisdropped
                   and has_column_privilege(
                       current_user, relation.oid,
                       attribute.attname, ?)
                """,
                String.class,
                table,
                privilege));
        return granted.equals(Set.of(columns));
    }

    private static boolean columnsLackPrivilege(
            JdbcTemplate jdbc,
            String table,
            String privilege,
            String... columns) {
        return Stream.of(columns)
                .noneMatch(column -> columnPrivilege(
                        jdbc, table, column, privilege));
    }

    private static boolean rolePrivileges(
            JdbcTemplate jdbc,
            String role,
            String table,
            String... required) {
        return Stream.of(required)
                .allMatch(value -> rolePrivilege(
                        jdbc, role, table, value));
    }

    private static boolean roleHasExactlyColumnPrivileges(
            JdbcTemplate jdbc,
            String role,
            String table,
            String privilege,
            String... columns) {
        Set<String> granted = Set.copyOf(jdbc.queryForList(
                """
                select attribute.attname
                  from pg_catalog.pg_attribute attribute
                  join pg_catalog.pg_class relation
                    on relation.oid=attribute.attrelid
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=relation.relnamespace
                 where namespace.nspname='identity_access'
                   and relation.relname=?
                   and attribute.attnum>0
                   and not attribute.attisdropped
                   and has_column_privilege(
                       ?, relation.oid, attribute.attname, ?)
                """,
                String.class,
                table,
                role,
                privilege));
        return granted.equals(Set.of(columns));
    }

    private static boolean roleColumnsLackPrivilege(
            JdbcTemplate jdbc,
            String role,
            String table,
            String privilege,
            String... columns) {
        return Stream.of(columns)
                .noneMatch(column -> roleColumnPrivilege(
                        jdbc, role, table, column, privilege));
    }

    private static boolean noMutation(
            JdbcTemplate jdbc, String... tables) {
        return Stream.of(tables)
                .allMatch(table -> noTableMutation(jdbc, table));
    }

    private static boolean noTableMutation(
            JdbcTemplate jdbc, String table) {
        return !anyColumnPrivilege(jdbc, table, "INSERT")
                && !anyColumnPrivilege(jdbc, table, "UPDATE")
                && !privilege(jdbc, table, "DELETE")
                && !privilege(jdbc, table, "TRUNCATE")
                && noStructuralPrivileges(jdbc, table);
    }

    private static boolean roleReadOnly(
            JdbcTemplate jdbc, String role, String... tables) {
        return Stream.of(tables)
                .allMatch(table ->
                        !roleAnyColumnPrivilege(
                                jdbc, role, table, "INSERT")
                        && !roleAnyColumnPrivilege(
                                jdbc, role, table, "UPDATE")
                        && !rolePrivilege(
                                jdbc, role, table, "DELETE")
                        && !rolePrivilege(
                                jdbc, role, table, "TRUNCATE")
                        && roleNoStructuralPrivileges(
                                jdbc, role, table));
    }

    private static boolean roleAppendOnly(
            JdbcTemplate jdbc, String role, String... tables) {
        return Stream.of(tables)
                .allMatch(table ->
                        !roleAnyColumnPrivilege(
                                jdbc, role, table, "UPDATE")
                        && !rolePrivilege(
                                jdbc, role, table, "DELETE")
                        && !rolePrivilege(
                                jdbc, role, table, "TRUNCATE")
                        && roleNoStructuralPrivileges(
                                jdbc, role, table));
    }

    private static boolean roleInsertOnly(
            JdbcTemplate jdbc, String role, String... tables) {
        return Stream.of(tables)
                .allMatch(table ->
                        !roleAnyColumnPrivilege(
                                jdbc, role, table, "SELECT")
                        && !roleAnyColumnPrivilege(
                                jdbc, role, table, "UPDATE")
                        && !rolePrivilege(
                                jdbc, role, table, "DELETE")
                        && !rolePrivilege(
                                jdbc, role, table, "TRUNCATE")
                        && roleNoStructuralPrivileges(
                                jdbc, role, table));
    }

    private static boolean roleNoDeleteTruncateOrStructural(
            JdbcTemplate jdbc, String role, String... tables) {
        return Stream.of(tables)
                .allMatch(table ->
                        !rolePrivilege(
                                jdbc, role, table, "DELETE")
                        && !rolePrivilege(
                                jdbc, role, table, "TRUNCATE")
                        && roleNoStructuralPrivileges(
                                jdbc, role, table));
    }

    private static boolean noTableAccess(
            JdbcTemplate jdbc, String... tables) {
        return Stream.of(tables).allMatch(table ->
                !anyColumnPrivilege(jdbc, table, "SELECT")
                        && noTableMutation(jdbc, table));
    }

    private static boolean appendOnly(
            JdbcTemplate jdbc, String... tables) {
        return Stream.of(tables).allMatch(table ->
                !anyColumnPrivilege(jdbc, table, "UPDATE")
                        && !privilege(jdbc, table, "DELETE")
                        && !privilege(jdbc, table, "TRUNCATE")
                        && noStructuralPrivileges(jdbc, table));
    }

    private static boolean mutableWithoutDelete(
            JdbcTemplate jdbc, String... tables) {
        return Stream.of(tables).allMatch(table ->
                !privilege(jdbc, table, "DELETE")
                        && !privilege(jdbc, table, "TRUNCATE")
                        && noStructuralPrivileges(jdbc, table));
    }

    private static boolean noStructuralPrivileges(
            JdbcTemplate jdbc, String table) {
        return !anyColumnPrivilege(jdbc, table, "REFERENCES")
                && !privilege(jdbc, table, "TRIGGER")
                && !privilege(jdbc, table, "MAINTAIN");
    }

    private static boolean roleNoStructuralPrivileges(
            JdbcTemplate jdbc, String role, String table) {
        return !roleAnyColumnPrivilege(
                        jdbc, role, table, "REFERENCES")
                && !rolePrivilege(jdbc, role, table, "TRIGGER")
                && !rolePrivilege(jdbc, role, table, "MAINTAIN");
    }

    private static boolean anyColumnPrivilege(
            JdbcTemplate jdbc, String table, String privilege) {
        Boolean allowed = jdbc.queryForObject(
                "select has_any_column_privilege(current_user, ?, ?)",
                Boolean.class,
                "identity_access." + table,
                privilege);
        return Boolean.TRUE.equals(allowed);
    }

    private static boolean roleAnyColumnPrivilege(
            JdbcTemplate jdbc,
            String role,
            String table,
            String privilege) {
        Boolean allowed = jdbc.queryForObject(
                "select has_any_column_privilege(?, ?, ?)",
                Boolean.class,
                role,
                "identity_access." + table,
                privilege);
        return Boolean.TRUE.equals(allowed);
    }

    private static boolean columnPrivilege(
            JdbcTemplate jdbc,
            String table,
            String column,
            String privilege) {
        Boolean allowed = jdbc.queryForObject(
                "select has_column_privilege(current_user, ?, ?, ?)",
                Boolean.class,
                "identity_access." + table,
                column,
                privilege);
        return Boolean.TRUE.equals(allowed);
    }

    private static boolean rolePrivilege(
            JdbcTemplate jdbc,
            String role,
            String table,
            String privilege) {
        Boolean allowed = jdbc.queryForObject(
                "select has_table_privilege(?, ?, ?)",
                Boolean.class,
                role,
                "identity_access." + table,
                privilege);
        return Boolean.TRUE.equals(allowed);
    }

    private static boolean roleColumnPrivilege(
            JdbcTemplate jdbc,
            String role,
            String table,
            String column,
            String privilege) {
        Boolean allowed = jdbc.queryForObject(
                "select has_column_privilege(?, ?, ?, ?)",
                Boolean.class,
                role,
                "identity_access." + table,
                column,
                privilege);
        return Boolean.TRUE.equals(allowed);
    }

    private record PrincipalProof(Identity identity, boolean valid) {}

    private record Identity(
            String database,
            String systemIdentifier,
            String user,
            String sessionUser) {
        private Identity {
            if (database == null
                    || systemIdentifier == null
                    || user == null
                    || sessionUser == null) {
                throw new IllegalStateException(
                        "ACCESS_INVALIDATION_DATABASE_IDENTITY_UNAVAILABLE");
            }
        }
    }
}
