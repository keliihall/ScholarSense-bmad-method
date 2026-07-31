package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityRecipientEvidencePort;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves account, active R1 employment, and college evidence without heuristics. */
public final class JdbcResponsibilityRecipientEvidenceAdapter
        implements ResponsibilityRecipientEvidencePort {
    private final JdbcTemplate jdbc;

    public JdbcResponsibilityRecipientEvidenceAdapter(
            JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public List<ResponsibilityRecipientEvidence> resolve(
            List<AuthoritativeResponsibilityRelation> relations,
            Instant serverNow) {
        List<ResponsibilityRecipientEvidence> result =
                new ArrayList<>();
        for (AuthoritativeResponsibilityRelation relation :
                relations) {
            result.add(resolve(relation, serverNow));
        }
        return List.copyOf(result);
    }

    private ResponsibilityRecipientEvidence resolve(
            AuthoritativeResponsibilityRelation relation,
            Instant serverNow) {
        return jdbc.query("""
                select account.account_id,
                       organization.organization_id,
                       coalesce(
                         account.status='active'
                         and account.effective_from<=?
                         and (account.effective_to is null
                              or account.effective_to>?), false)
                           as account_active,
                       exists (
                         select 1
                           from identity_access.ia_authoritative_role_current role
                          where role.account_id=account.account_id
                            and role.target_role_id='R1-COUNSELOR'
                            and role.status='active'
                            and role.effective_from<=?
                            and (role.effective_to is null
                                 or role.effective_to>?))
                           as r1_active,
                       coalesce(
                         organization.status='active'
                         and organization.organization_type='college'
                         and organization.effective_from<=?
                         and (organization.effective_to is null
                              or organization.effective_to>?), false)
                           as college_active,
                       exists (
                         select 1
                           from identity_access.ia_authoritative_role_current role
                          where role.account_id=account.account_id
                            and role.organization_id=
                                organization.organization_id
                            and role.target_role_id='R1-COUNSELOR'
                            and role.status='active'
                            and role.effective_from<=?
                            and (role.effective_to is null
                                 or role.effective_to>?))
                           as college_matches
                  from (values (1)) seed(value)
                  left join identity_access.ia_authoritative_account_current account
                    on account.source_id='SRC-P0-RESPONSIBILITY-001'
                   and account.consumer_projection='identity-org'
                   and account.external_ref_digest=?
                  left join identity_access.ia_authoritative_organization_current
                            organization
                    on organization.source_id=
                        'SRC-P0-RESPONSIBILITY-001'
                   and organization.consumer_projection='identity-org'
                   and organization.external_ref_digest=?
                """,
                (rs, row) -> new ResponsibilityRecipientEvidence(
                        relation,
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("account_id") != null,
                        rs.getBoolean("account_active"),
                        rs.getBoolean("r1_active"),
                        rs.getObject("organization_id") != null,
                        rs.getBoolean("college_active"),
                        rs.getBoolean("college_matches")),
                timestamp(serverNow),
                timestamp(serverNow),
                timestamp(serverNow),
                timestamp(serverNow),
                timestamp(serverNow),
                timestamp(serverNow),
                timestamp(serverNow),
                timestamp(serverNow),
                relation.counselorAccountRefDigest(),
                relation.collegeOrganizationRefDigest())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "RESPONSIBILITY_IDENTITY_PROJECTION_UNAVAILABLE"));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
