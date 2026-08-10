package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves dynamic snapshot identity in PostgreSQL, then reuses the static SOURCE owner oracle. */
public final class QualitySnapshotOwnerEvidenceProvider
        implements AuthorizationObjectEvidenceProvider {
    private static final Set<String> OBJECT_CLASSES = Set.of("QUALITY_SNAPSHOT");
    private final JdbcTemplate jdbc;
    private final CatalogOwnerEvidenceProvider sourceOwners;

    public QualitySnapshotOwnerEvidenceProvider(
            JdbcTemplate jdbc, CatalogOwnerEvidenceProvider sourceOwners) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.sourceOwners = Objects.requireNonNull(sourceOwners);
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
        List<SnapshotOwnerRow> rows = jdbc.query("""
                select source_id, object_version
                  from ingestion_quality.iq_resolve_quality_snapshot_source(?)
                """, (row, ignored) -> new SnapshotOwnerRow(
                        row.getString("source_id"), row.getLong("object_version")),
                query.objectTokenDigest());
        if (rows.size() != 1) return AuthorizationObjectEvidence.unavailable();
        SnapshotOwnerRow row = rows.getFirst();
        AuthorizationObjectEvidence source = sourceOwners.resolve(
                new AuthorizationObjectEvidenceQuery(
                        query.actorPseudonym(), query.accountId(), query.organizationIds(),
                        "SOURCE", query.actionId(), sha256(row.sourceId()),
                        query.expectedObjectVersion(), query.serverNow()));
        if (source.availability() != AuthorizationEvidenceAvailability.AVAILABLE) return source;
        return new AuthorizationObjectEvidence(
                AuthorizationEvidenceAvailability.AVAILABLE,
                source.scopeEvidence(),
                "data-quality.read",
                Set.of(),
                null,
                null,
                Set.of(),
                false,
                source.relationVersion(),
                source.grantVersion(),
                source.invalidationVersion(),
                row.objectVersion(),
                java.util.Optional.empty());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record SnapshotOwnerRow(String sourceId, long objectVersion) {}
}
