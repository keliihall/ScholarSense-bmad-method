package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContextQueryPort;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads only the rebuildable current projection using the stable actor pseudonym. */
public final class JdbcAuthoritativeIdentityContextAdapter
        implements AuthoritativeIdentityContextQueryPort {
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;
    private final Duration freshnessWindow;

    public JdbcAuthoritativeIdentityContextAdapter(
            JdbcTemplate jdbc, java.time.Clock clock, Duration freshnessWindow) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.freshnessWindow = freshnessWindow;
    }

    @Override
    public Optional<AuthoritativeIdentityContext> findCurrent(String actorPseudonym) {
        Instant now = clock.instant();
        List<Row> rows = jdbc.query("""
                select account.account_id,
                       checkpoint.aggregate_version, checkpoint.source_version,
                       checkpoint.source_watermark,
                       checkpoint.last_successful_at as applied_at,
                       account.mapping_version,
                       role.mapping_digest,
                       role.target_role_id, role.organization_id,
                       checkpoint.last_successful_at, checkpoint.health
                  from identity_access.ia_authoritative_account_current account
                  join identity_access.ia_authoritative_role_current role
                    on role.account_id=account.account_id
                  join identity_access.ia_authoritative_organization_current organization
                    on organization.organization_id=role.organization_id
                  join identity_access.ia_identity_sync_checkpoint checkpoint
                    on checkpoint.source_id=account.source_id
                   and checkpoint.feed_id=account.feed_id
                   and checkpoint.partition_id=account.partition_id
                   and checkpoint.consumer_projection=account.consumer_projection
                 where (account.subject_binding_token=?
                    or exists (
                      select 1
                        from identity_access.ia_authoritative_subject_binding_history binding
                       where binding.account_id=account.account_id
                         and binding.subject_binding_token=?
                         and (binding.is_current
                              or binding.read_approved_until>?)))
                   and account.status='active'
                   and account.effective_from<=?
                   and (account.effective_to is null or account.effective_to>?)
                   and role.status='active'
                   and role.effective_from<=?
                   and (role.effective_to is null or role.effective_to>?)
                   and organization.status='active'
                   and organization.effective_from<=?
                   and (organization.effective_to is null or organization.effective_to>?)
                """,
                (rs, index) -> new Row(
                        rs.getObject("account_id", UUID.class),
                        rs.getLong("aggregate_version"),
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        rs.getTimestamp("applied_at").toInstant(),
                        rs.getString("mapping_version"),
                        rs.getString("mapping_digest"),
                        rs.getString("target_role_id"),
                        rs.getObject("organization_id", UUID.class),
                        rs.getTimestamp("last_successful_at").toInstant(),
                        rs.getString("health")),
                actorPseudonym,
                actorPseudonym,
                timestamp(now),
                timestamp(now), timestamp(now),
                timestamp(now), timestamp(now),
                timestamp(now), timestamp(now));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row first = rows.getFirst();
        if (rows.stream().anyMatch(row -> !row.accountId().equals(first.accountId()))) {
            return Optional.empty();
        }
        if (rows.stream().anyMatch(row ->
                !row.mappingVersion().equals(first.mappingVersion())
                        || !row.mappingDigest().equals(first.mappingDigest()))) {
            return Optional.empty();
        }
        var roles = new LinkedHashSet<String>();
        var organizations = new LinkedHashSet<UUID>();
        rows.forEach(row -> {
            roles.add(row.roleId());
            organizations.add(row.organizationId());
        });
        boolean stale = now.isAfter(first.lastSuccessfulAt().plus(freshnessWindow));
        IdentityFreshness freshness = stale
                ? IdentityFreshness.STALE
                : "degraded".equals(first.health())
                    ? IdentityFreshness.DEGRADED : IdentityFreshness.FRESH;
        return Optional.of(new AuthoritativeIdentityContext(
                first.accountId(),
                new ArrayList<>(roles),
                new ArrayList<>(organizations),
                first.aggregateVersion(),
                first.sourceVersion(),
                first.sourceWatermark(),
                freshness,
                Map.of(
                        "identitySessionPolicy", "ISP-1.0.0",
                        "roleFieldPolicy", "RFP-1.0.0",
                        "roleMapping", first.mappingVersion(),
                        "roleMappingDigest", "sha256:" + first.mappingDigest()),
                first.appliedAt()));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private record Row(
            UUID accountId,
            long aggregateVersion,
            long sourceVersion,
            long sourceWatermark,
            Instant appliedAt,
            String mappingVersion,
            String mappingDigest,
            String roleId,
            UUID organizationId,
            Instant lastSuccessfulAt,
            String health) {}
}
