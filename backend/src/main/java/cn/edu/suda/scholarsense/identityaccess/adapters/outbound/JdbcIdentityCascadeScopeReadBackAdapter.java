package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentityCascadeScopeReadBack;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityCascadeScopeReadBackPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityRecordKind;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityScopeReadBack;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityScopeReadBackPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Enumerates and reads back each exact affected responsibility lineage. */
public final class JdbcIdentityCascadeScopeReadBackAdapter
        implements IdentityCascadeScopeReadBackPort {
    private final JdbcTemplate jdbc;
    private final ResponsibilityScopeReadBackPort readBack;

    public JdbcIdentityCascadeScopeReadBackAdapter(
            JdbcTemplate jdbc,
            ResponsibilityScopeReadBackPort readBack) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.readBack = java.util.Objects.requireNonNull(readBack);
    }

    @Override
    public List<IdentityCascadeScopeReadBack> readBack(
            IdentityRecordKind recordKind,
            String externalRefDigest,
            long sourceVersion,
            long sourceWatermark,
            long aggregateVersion,
            Instant serverNow) {
        if (recordKind == null
                || externalRefDigest == null
                || !externalRefDigest.matches("[0-9a-f]{64}")
                || sourceVersion < 1
                || sourceWatermark < 1
                || aggregateVersion < 1
                || serverNow == null) {
            throw new IllegalArgumentException(
                    "IDENTITY_CASCADE_SCOPE_REQUEST_INVALID");
        }
        List<AffectedScope> affected = jdbc.query("""
                with parameters as (
                  select cast(? as varchar) as record_kind,
                         cast(? as char(64)) as external_ref_digest,
                         cast(? as bigint) as source_version,
                         cast(? as bigint) as source_watermark,
                         cast(? as bigint) as aggregate_version,
                         cast(? as timestamptz) as server_now
                ), committed_identity as (
                  select 'account'::varchar as record_kind,
                         account.source_id,
                         account.account_id,
                         null::uuid as organization_id,
                         account.source_version,
                         account.source_watermark,
                         account.aggregate_version
                    from identity_access
                         .ia_authoritative_account_current account
                   cross join parameters
                   where parameters.record_kind='account'
                     and account.external_ref_digest=
                         parameters.external_ref_digest
                  union all
                  select 'organization'::varchar,
                         organization.source_id,
                         null::uuid,
                         organization.organization_id,
                         organization.source_version,
                         organization.source_watermark,
                         organization.aggregate_version
                    from identity_access
                         .ia_authoritative_organization_current organization
                   cross join parameters
                   where parameters.record_kind='organization'
                     and organization.external_ref_digest=
                         parameters.external_ref_digest
                  union all
                  select 'employment-role'::varchar,
                         role.source_id,
                         role.account_id,
                         role.organization_id,
                         role.source_version,
                         role.source_watermark,
                         role.aggregate_version
                    from identity_access
                         .ia_authoritative_role_current role
                   cross join parameters
                   where parameters.record_kind='employment-role'
                     and role.external_ref_digest=
                         parameters.external_ref_digest
                )
                select current_scope.access_lineage_id,
                       current_scope.student_equivalence_digest,
                       scope_account.account_id as counselor_account_id,
                       scope_college.organization_id
                           as college_organization_id,
                       identity.source_version as identity_source_version,
                       identity.source_watermark
                           as identity_source_watermark,
                       identity.aggregate_version
                           as identity_aggregate_version,
                       current_scope.source_version as scope_source_version,
                       current_scope.source_watermark
                           as scope_source_watermark,
                       current_scope.aggregate_version
                           as scope_aggregate_version,
                       (
                         current_scope.responsibility_type='primary'
                         and scope_account.status='active'
                         and scope_account.effective_from<=
                             parameters.server_now
                         and (scope_account.effective_to is null
                              or scope_account.effective_to>
                                  parameters.server_now)
                         and scope_college.status='active'
                         and scope_college.organization_type='college'
                         and scope_college.effective_from<=
                             parameters.server_now
                         and (scope_college.effective_to is null
                              or scope_college.effective_to>
                                  parameters.server_now)
                         and exists (
                           select 1
                             from identity_access
                                  .ia_authoritative_role_current active_role
                            where active_role.source_id=
                                  current_scope.source_id
                              and active_role.account_id=
                                  scope_account.account_id
                              and active_role.organization_id=
                                  scope_college.organization_id
                              and active_role.target_role_id='R1-COUNSELOR'
                              and active_role.mapping_version=
                                  'IDENTITY-ROLE-MAPPING-1.0.0'
                              and active_role.mapping_digest
                                  ~ '^[0-9a-f]{64}$'
                              and active_role.status='active'
                              and active_role.effective_from<=
                                  parameters.server_now
                              and (active_role.effective_to is null
                                   or active_role.effective_to>
                                       parameters.server_now)
                         )
                       ) as expected_valid
                  from identity_access.ia_responsibility_current current_scope
                  join identity_access
                       .ia_authoritative_account_current scope_account
                    on scope_account.source_id=current_scope.source_id
                   and scope_account.external_ref_digest=
                       current_scope.counselor_account_ref_digest
                   and (current_scope.counselor_account_id is null
                        or current_scope.counselor_account_id=
                            scope_account.account_id)
                  join identity_access
                       .ia_authoritative_organization_current scope_college
                    on scope_college.source_id=current_scope.source_id
                   and scope_college.external_ref_digest=
                       current_scope.college_organization_ref_digest
                   and (current_scope.college_organization_id is null
                        or current_scope.college_organization_id=
                            scope_college.organization_id)
                  join committed_identity identity
                    on identity.source_id=current_scope.source_id
                  cross join parameters
                 where current_scope.relation_status='active'
                   and current_scope.quality_gate_status='trusted'
                   and current_scope.effective_from<=parameters.server_now
                   and (current_scope.effective_to is null
                        or current_scope.effective_to>parameters.server_now)
                   and current_scope.access_lineage_id is not null
                   and (
                     (parameters.record_kind='account'
                      and scope_account.account_id=
                          identity.account_id)
                     or
                     (parameters.record_kind='organization'
                      and scope_college.organization_id=
                          identity.organization_id)
                     or
                     (parameters.record_kind='employment-role'
                      and exists (
                        select 1
                          from identity_access
                               .ia_authoritative_role_binding_history history
                         where history.source_id=current_scope.source_id
                           and history.role_external_ref_digest=
                               parameters.external_ref_digest
                           and history.account_id=
                               scope_account.account_id
                           and history.organization_id=
                               scope_college.organization_id
                           and history.target_role_id='R1-COUNSELOR'
                           and history.mapping_version=
                               'IDENTITY-ROLE-MAPPING-1.0.0'
                           and history.mapping_digest
                               ~ '^[0-9a-f]{64}$'
                           and history.source_role_code
                               ~ '^[A-Z][A-Z0-9_]{2,63}$'
                           and history.status='active'
                           and history.effective_from<=parameters.server_now
                           and (history.effective_to is null
                                or history.effective_to>
                                    parameters.server_now)
                      ))
                   )
                 order by current_scope.access_lineage_id,
                          scope_account.account_id,
                          current_scope.student_equivalence_digest
                """,
                (rs, row) -> new AffectedScope(
                        new AccessInvalidationLineageId(
                                rs.getString("access_lineage_id")),
                        rs.getString("student_equivalence_digest"),
                        rs.getObject("counselor_account_id", UUID.class),
                        rs.getObject("college_organization_id", UUID.class),
                        rs.getBoolean("expected_valid")
                                ? ResponsibilityRecipientValidity.VALID
                                : ResponsibilityRecipientValidity.INVALID,
                        rs.getLong("identity_source_version"),
                        rs.getLong("identity_source_watermark"),
                        rs.getLong("identity_aggregate_version"),
                        rs.getLong("scope_source_version"),
                        rs.getLong("scope_source_watermark"),
                        rs.getLong("scope_aggregate_version")),
                recordKind.wireName(),
                externalRefDigest,
                sourceVersion,
                sourceWatermark,
                aggregateVersion,
                Timestamp.from(serverNow));
        return affected.stream()
                .map(scope -> new IdentityCascadeScopeReadBack(
                        scope.accessLineageId(),
                        scope.studentEquivalenceDigest(),
                        scope.counselorAccountId(),
                        scope.collegeOrganizationId(),
                        scope.expectedValidity(),
                        scope.identitySourceVersion(),
                        scope.identitySourceWatermark(),
                        scope.identityAggregateVersion(),
                        scope.scopeSourceVersion(),
                        scope.scopeSourceWatermark(),
                        scope.scopeAggregateVersion(),
                        readBack(scope, serverNow)))
                .toList();
    }

    private ResponsibilityScopeReadBack readBack(
            AffectedScope scope, Instant serverNow) {
        try {
            return readBack.readBack(
                    scope.accessLineageId(),
                    scope.counselorAccountId(),
                    scope.studentEquivalenceDigest(),
                    serverNow);
        } catch (RuntimeException unavailable) {
            return new ResponsibilityScopeReadBack(
                    ResponsibilityRecipientValidity
                            .DEPENDENCY_UNAVAILABLE,
                    "RESPONSIBILITY_SCOPE_TARGETED_READBACK_UNAVAILABLE",
                    0,
                    0,
                    0,
                    serverNow);
        }
    }

    private record AffectedScope(
            AccessInvalidationLineageId accessLineageId,
            String studentEquivalenceDigest,
            UUID counselorAccountId,
            UUID collegeOrganizationId,
            ResponsibilityRecipientValidity expectedValidity,
            long identitySourceVersion,
            long identitySourceWatermark,
            long identityAggregateVersion,
            long scopeSourceVersion,
            long scopeSourceWatermark,
            long scopeAggregateVersion) {}
}
