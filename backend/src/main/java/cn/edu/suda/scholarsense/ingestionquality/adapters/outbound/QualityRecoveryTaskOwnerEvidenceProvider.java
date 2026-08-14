package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalEvidenceQueryPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves opaque RECOVERY_TASK identity/current version, then delegates SOURCE ownership. */
public final class QualityRecoveryTaskOwnerEvidenceProvider
        implements AuthorizationObjectEvidenceProvider {
    private static final Set<String> OBJECT_CLASSES = Set.of("RECOVERY_TASK");
    private final JdbcTemplate jdbc;
    private final CatalogOwnerEvidenceProvider sourceOwners;
    private final HighRiskApprovalEvidenceQueryPort approvals;

    public QualityRecoveryTaskOwnerEvidenceProvider(
            JdbcTemplate jdbc, CatalogOwnerEvidenceProvider sourceOwners) {
        this(jdbc, sourceOwners, HighRiskApprovalEvidenceQueryPort.notInstalled());
    }

    public QualityRecoveryTaskOwnerEvidenceProvider(
            JdbcTemplate jdbc,
            CatalogOwnerEvidenceProvider sourceOwners,
            HighRiskApprovalEvidenceQueryPort approvals) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.sourceOwners = Objects.requireNonNull(sourceOwners);
        this.approvals = Objects.requireNonNull(approvals);
    }

    @Override
    public Set<String> supportedObjectClasses() {
        return OBJECT_CLASSES;
    }

    @Override
    public AuthorizationObjectEvidence resolve(AuthorizationObjectEvidenceQuery query) {
        Objects.requireNonNull(query);
        if (!OBJECT_CLASSES.contains(query.objectClass())) {
            return AuthorizationObjectEvidence.notInstalled();
        }
        List<TaskOwnerRow> rows = jdbc.query("""
                select source_id, object_version, status
                  from ingestion_quality.iq_resolve_quality_recovery_task_owner(?)
                """, (row, ignored) -> new TaskOwnerRow(
                        row.getString("source_id"), row.getLong("object_version"),
                        row.getString("status")), "sha256:" + query.objectTokenDigest());
        if (rows.size() != 1 || !"open".equals(rows.getFirst().status())) {
            return AuthorizationObjectEvidence.unavailable();
        }
        TaskOwnerRow row = rows.getFirst();
        if (row.objectVersion() != query.expectedObjectVersion()) {
            return AuthorizationObjectEvidence.unavailable();
        }
        AuthorizationObjectEvidence source = sourceOwners.resolve(
                new AuthorizationObjectEvidenceQuery(
                        query.actorPseudonym(), query.accountId(), query.organizationIds(),
                        "SOURCE", query.actionId(), sha256(row.sourceId()), row.objectVersion(),
                        query.serverNow()));
        if (source.availability() != AuthorizationEvidenceAvailability.AVAILABLE) return source;
        Set<String> currentApprovals = Set.of();
        if ("quality-fuse.recover".equals(query.actionId())) {
            HighRiskApprovalEvidence approval;
            try {
                approval = approvals.query(new HighRiskApprovalEvidenceQuery(
                        query.actionId(), query.objectClass(),
                        "sha256:" + query.objectTokenDigest(), row.objectVersion(),
                        query.accountId(), query.serverNow(),
                        "00000000000000000000000000000001"));
            } catch (RuntimeException unavailable) {
                return AuthorizationObjectEvidence.unavailable();
            }
            if (approval == null
                    || approval.availability() != HighRiskApprovalEvidence.Availability.AVAILABLE) {
                return AuthorizationObjectEvidence.unavailable();
            }
            if (approval.currentlyApproved()) currentApprovals = Set.of(query.actionId());
        }
        return new AuthorizationObjectEvidence(
                AuthorizationEvidenceAvailability.AVAILABLE, source.scopeEvidence(),
                query.actionId(), Set.of(), null, null,
                currentApprovals, false, source.relationVersion(), source.grantVersion(),
                source.invalidationVersion(), row.objectVersion(), Optional.empty());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    private record TaskOwnerRow(String sourceId, long objectVersion, String status) {}
}
