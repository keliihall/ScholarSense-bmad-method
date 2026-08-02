package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationIdPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationImpactResolverPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationStorePort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationCause;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageHead;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves only V2 lineages in bounded, stable current-projection pages. */
public final class JdbcAccessInvalidationImpactResolver
        implements AccessInvalidationImpactResolverPort {
    private static final Duration RETENTION = Duration.ofDays(2190);
    private final JdbcTemplate jdbc;
    private final AccessInvalidationStorePort store;
    private final AccessInvalidationIdPort identifiers;

    public JdbcAccessInvalidationImpactResolver(
            JdbcTemplate jdbc,
            AccessInvalidationStorePort store,
            AccessInvalidationIdPort identifiers) {
        this.jdbc = jdbc;
        this.store = store;
        this.identifiers = identifiers;
    }

    @Override
    public List<AccessInvalidationFact> resolve(
            AccessInvalidationCause cause,
            int batchSize,
            long sequence,
            String afterLineageId) {
        if (batchSize < 1
                || batchSize > 250
                || sequence < 0
                || (afterLineageId != null
                        && !afterLineageId.matches(
                                "lin_[A-Za-z0-9_-]{32,128}"))) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_IMPACT_BATCH_INVALID");
        }
        String causeDigest = causeObjectDigest(cause);
        return jdbc.query("""
                with parameters as (
                  select cast(? as varchar) as reason_code,
                         cast(? as char(64)) as cause_digest,
                         cast(? as timestamptz) as effective_at,
                         cast(? as varchar) as after_lineage_id
                )
                select current_scope.access_lineage_id,
                       current_scope.student_equivalence_digest,
                       current_scope.payload_digest,
                       exists (
                         select 1
                           from identity_access
                             .ia_authoritative_account_current account
                          where account.external_ref_digest=
                                current_scope.counselor_account_ref_digest
                            and account.status='active'
                            and account.effective_from<=parameters.effective_at
                            and (account.effective_to is null
                                 or account.effective_to>parameters.effective_at)
                       ) as account_active,
                       exists (
                         select 1
                           from identity_access
                             .ia_authoritative_role_current role
                           join identity_access
                             .ia_authoritative_account_current account
                             on account.account_id=role.account_id
                           join identity_access
                             .ia_authoritative_organization_current role_college
                             on role_college.organization_id=
                                role.organization_id
                          where account.external_ref_digest=
                                current_scope.counselor_account_ref_digest
                            and account.status='active'
                            and account.effective_from<=parameters.effective_at
                            and (account.effective_to is null
                                 or account.effective_to>parameters.effective_at)
                            and role_college.external_ref_digest=
                                current_scope.college_organization_ref_digest
                            and role_college.organization_type='college'
                            and role.target_role_id='R1-COUNSELOR'
                            and role.status='active'
                            and role.effective_from<=parameters.effective_at
                            and (role.effective_to is null
                                 or role.effective_to>parameters.effective_at)
                       ) as r1_employment_valid,
                       exists (
                         select 1
                           from identity_access
                             .ia_authoritative_organization_current college
                          where college.external_ref_digest=
                                current_scope.college_organization_ref_digest
                            and college.organization_type='college'
                            and college.status='active'
                            and college.effective_from<=parameters.effective_at
                            and (college.effective_to is null
                                 or college.effective_to>parameters.effective_at)
                       ) as college_active,
                       current_scope.relation_status='active'
                         and current_scope.quality_gate_status='trusted'
                         and current_scope.effective_from<=parameters.effective_at
                         and (current_scope.effective_to is null
                              or current_scope.effective_to>
                                 parameters.effective_at)
                           as relation_current
                  from identity_access.ia_responsibility_current current_scope
                 cross join parameters
                 where current_scope.access_lineage_id is not null
                   and (parameters.after_lineage_id is null
                        or current_scope.access_lineage_id>
                           parameters.after_lineage_id)
                   and (
                     (parameters.reason_code='ACCOUNT_DISABLED'
                       and current_scope.counselor_account_ref_digest=
                           parameters.cause_digest)
                     or
                     (parameters.reason_code='COLLEGE_INVALID'
                       and current_scope.college_organization_ref_digest=
                           parameters.cause_digest)
                     or
                     (parameters.reason_code='R1_EMPLOYMENT_INVALID'
                       and exists (
                       select 1
                         from identity_access
                           .ia_authoritative_role_binding_history role_history
                        where role_history.role_external_ref_digest=
                              parameters.cause_digest
                          and role_history.target_role_id='R1-COUNSELOR'
                          and role_history.account_id=
                              current_scope.counselor_account_id
                          and role_history.organization_id=
                              current_scope.college_organization_id
                     ))
                     or
                     (parameters.reason_code='SOURCE_CORRECTION'
                       and (
                         current_scope.counselor_account_ref_digest=
                           parameters.cause_digest
                         or current_scope.college_organization_ref_digest=
                           parameters.cause_digest
                         or exists (
                           select 1
                             from identity_access
                               .ia_authoritative_role_binding_history
                                 role_history
                            where role_history.role_external_ref_digest=
                                  parameters.cause_digest
                              and role_history.target_role_id='R1-COUNSELOR'
                              and role_history.account_id=
                                  current_scope.counselor_account_id
                              and role_history.organization_id=
                                  current_scope.college_organization_id
                         )
                       ))
                   )
                 order by current_scope.access_lineage_id
                 limit ?
                """,
                (rs, row) -> fact(
                        cause,
                        new AccessInvalidationLineageId(
                                rs.getString("access_lineage_id")),
                        rs.getString("student_equivalence_digest"),
                        rs.getString("payload_digest"),
                        sequence + row,
                        rs.getBoolean("account_active"),
                        rs.getBoolean("r1_employment_valid"),
                        rs.getBoolean("college_active"),
                        rs.getBoolean("relation_current")),
                cause.reasonCode().name(),
                causeDigest,
                java.sql.Timestamp.from(cause.effectiveAt()),
                afterLineageId,
                batchSize);
    }

    private AccessInvalidationFact fact(
            AccessInvalidationCause cause,
            AccessInvalidationLineageId lineage,
            String objectDigest,
            String payloadDigest,
            long sequence,
            boolean accountActive,
            boolean roleValid,
            boolean collegeActive,
            boolean relationCurrent) {
        var head = store.head(lineage.value());
        long version = head.map(
                        AccessInvalidationLineageHead::aggregateVersion)
                .orElse(0L)
                + 1;
        boolean correction = cause.reasonCode()
                == AccessInvalidationReason.SOURCE_CORRECTION;
        boolean recovered = correction
                && accountActive
                && roleValid
                && collegeActive
                && relationCurrent;
        return new AccessInvalidationFact(
                identifiers.next(
                        cause.effectiveAt().plusNanos(sequence + 1)),
                cause.traceId(),
                recovered
                        ? AccessInvalidationChangeKind.REVALIDATED
                        : correction
                                ? AccessInvalidationChangeKind.CORRECTED
                                : AccessInvalidationChangeKind.INVALIDATED,
                recovered
                        ? AccessInvalidationReason.RECONCILIATION_RECOVERED
                        : cause.reasonCode(),
                lineage,
                head.map(AccessInvalidationLineageHead::eventId)
                        .orElse(null),
                cause.causeEventId(),
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                lineage.value(),
                version,
                version,
                cause.effectiveAt(),
                cause.sourceVector(),
                new AccessInvalidationSubjectSnapshot(
                        "subtok_" + objectDigest,
                        "scptok_" + payloadDigest,
                        objectDigest,
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                new AccessInvalidationAuthorizationSnapshot(
                        recovered
                                ? AccessInvalidationAuthorizationState
                                        .REVALIDATED
                                : AccessInvalidationAuthorizationState
                                        .INVALIDATED,
                        accountActive,
                        roleValid,
                        collegeActive,
                        relationCurrent,
                        "RFP-1.0.0"),
                new AccessInvalidationRetention(
                        "restricted",
                        "RS-1.0.0",
                        cause.effectiveAt().plus(RETENTION),
                        false),
                payloadDigest);
    }

    private String causeObjectDigest(
            AccessInvalidationCause cause) {
        return store.find(cause.causeEventId())
                .orElseThrow(() -> new IllegalStateException(
                        "ACCESS_INVALIDATION_CAUSE_FACT_MISSING"))
                .subjectSnapshot()
                .objectDigest();
    }
}
