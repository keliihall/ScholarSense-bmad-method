package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQueryPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibility;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityMemberEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Online-role adapter; one ID query plus two bounded bulk hydrations per page. */
public final class JdbcQualityEligibilityQueryStore implements QualityEligibilityQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcQualityEligibilityQueryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<QualityEligibility> findCurrent(QualityEligibilityQueryCriteria criteria) {
        List<UUID> ids = jdbc.query("""
                select eligibility_id
                  from ingestion_quality.iq_find_quality_eligibility_ids(?,?,?,?,?)
                """, (row, ignored) -> row.getObject(1, UUID.class),
                criteria.status(), criteria.ruleId(), timestamp(criteria.afterOccurredAt()),
                criteria.afterEligibilityId(), criteria.limit());
        return hydrate(ids);
    }

    @Override
    public Optional<QualityEligibility> findCurrentById(UUID eligibilityId) {
        Objects.requireNonNull(eligibilityId);
        List<QualityEligibility> values = hydrate(List.of(eligibilityId));
        if (values.size() > 1) throw invalid();
        return values.stream().findFirst();
    }

    private List<QualityEligibility> hydrate(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        UUID[] page = ids.toArray(UUID[]::new);
        List<EligibilityRow> rows = jdbc.query("""
                select *
                  from ingestion_quality.iq_find_quality_eligibility_page(?::uuid[])
                """, JdbcQualityEligibilityQueryStore::eligibilityRow, (Object) page);
        if (rows.size() != ids.size()
                || !rows.stream().map(EligibilityRow::eligibilityId).toList().equals(ids)) {
            if (ids.size() == 1 && rows.isEmpty()) return List.of();
            throw invalid();
        }
        Map<UUID, List<MemberRow>> members = new LinkedHashMap<>();
        ids.forEach(id -> members.put(id, new ArrayList<>()));
        for (MemberRow member : jdbc.query("""
                select *
                  from ingestion_quality.iq_find_quality_eligibility_page_members(?::uuid[])
                """, JdbcQualityEligibilityQueryStore::memberRow, (Object) page)) {
            List<MemberRow> target = members.get(member.eligibilityId());
            if (target == null) throw invalid();
            target.add(member);
        }
        return rows.stream().map(row -> row.toDomain(members.get(row.eligibilityId()))).toList();
    }

    private static EligibilityRow eligibilityRow(ResultSet row, int ignored) throws SQLException {
        int threshold = row.getInt("threshold");
        Integer optionalThreshold = row.wasNull() ? null : threshold;
        return new EligibilityRow(
                row.getObject("eligibility_id", UUID.class), row.getString("rule_id"),
                row.getString("rule_version"), row.getString("registry_version"),
                trim(row.getString("registry_digest")), row.getString("catalog_version"),
                trim(row.getString("catalog_digest")), row.getString("rule_catalog_version"),
                trim(row.getString("rule_catalog_digest")), row.getLong("aggregate_version"),
                status(row.getString("status")), reason(row.getString("reason_code")),
                operator(row.getString("composition_operator")), optionalThreshold,
                instant(row, "effective_at"), instant(row, "occurred_at"),
                trim(row.getString("trace_id")));
    }

    private static MemberRow memberRow(ResultSet row, int ignored) throws SQLException {
        return new MemberRow(
                row.getObject("eligibility_id", UUID.class), row.getLong("aggregate_version"),
                row.getInt("member_ordinal"), row.getString("source_id"),
                row.getLong("source_version"), row.getString("dependency_id"),
                row.getLong("dependency_version"), requirement(row.getString("requirement")),
                status(row.getString("state")), row.getBoolean("version_continuous"),
                DataBatchPersistenceCodec.decodeUtf8(row.getBytes("source_watermark_utf8")),
                DataBatchPersistenceCodec.decodeUtf8(row.getBytes("dependency_watermark_utf8")),
                row.getObject("snapshot_id", UUID.class),
                trim(row.getString("snapshot_immutable_hash")), row.getString("qmdp_version"),
                trim(row.getString("qmdp_digest")), row.getString("qshm_version"),
                trim(row.getString("qshm_digest")), row.getObject("lineage_id", UUID.class),
                row.getBoolean("failed"));
    }

    private static QualityEligibilityStatus status(String value) {
        return switch (value) {
            case "eligible" -> QualityEligibilityStatus.ELIGIBLE;
            case "fused" -> QualityEligibilityStatus.FUSED;
            case "recovering" -> QualityEligibilityStatus.RECOVERING;
            case "missing" -> QualityEligibilityStatus.MISSING;
            default -> throw invalid();
        };
    }

    private static QualityEligibilityReason reason(String value) {
        try {
            return QualityEligibilityReason.valueOf(value);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static DependencyOperator operator(String value) {
        return switch (value) {
            case "all-of" -> DependencyOperator.ALL_OF;
            case "any-of" -> DependencyOperator.ANY_OF;
            case "threshold" -> DependencyOperator.THRESHOLD;
            default -> throw invalid();
        };
    }

    private static DependencyRequirement requirement(String value) {
        return switch (value) {
            case "required" -> DependencyRequirement.REQUIRED;
            case "optional" -> DependencyRequirement.OPTIONAL;
            default -> throw invalid();
        };
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) throw invalid();
        return value.toInstant();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }

    private record EligibilityRow(
            UUID eligibilityId,
            String ruleId,
            String ruleVersion,
            String registryVersion,
            String registryDigest,
            String catalogVersion,
            String catalogDigest,
            String ruleCatalogVersion,
            String ruleCatalogDigest,
            long aggregateVersion,
            QualityEligibilityStatus status,
            QualityEligibilityReason reason,
            DependencyOperator operator,
            Integer threshold,
            Instant effectiveAt,
            Instant occurredAt,
            String traceId) {
        QualityEligibility toDomain(List<MemberRow> rows) {
            if (rows == null || rows.isEmpty()
                    || rows.stream().anyMatch(row -> row.aggregateVersion() != aggregateVersion)) {
                throw invalid();
            }
            List<QualityEligibilityMemberEvidence> evidence = rows.stream()
                    .map(MemberRow::toDomain).toList();
            List<String> failed = rows.stream().filter(MemberRow::failed)
                    .map(MemberRow::dependencyId).sorted().toList();
            return new QualityEligibility(
                    eligibilityId, new RuleVersionIdentity(ruleId, ruleVersion), registryVersion,
                    registryDigest, catalogVersion, catalogDigest, ruleCatalogVersion,
                    ruleCatalogDigest, aggregateVersion, status, reason, operator, threshold,
                    evidence, failed, effectiveAt, occurredAt, traceId);
        }
    }

    private record MemberRow(
            UUID eligibilityId,
            long aggregateVersion,
            int ordinal,
            String sourceId,
            long sourceVersion,
            String dependencyId,
            long dependencyVersion,
            DependencyRequirement requirement,
            QualityEligibilityStatus state,
            boolean versionContinuous,
            String sourceWatermark,
            String dependencyWatermark,
            UUID snapshotId,
            String snapshotImmutableHash,
            String qmdpVersion,
            String qmdpDigest,
            String qshmVersion,
            String qshmDigest,
            UUID lineageId,
            boolean failed) {
        QualityEligibilityMemberEvidence toDomain() {
            return new QualityEligibilityMemberEvidence(
                    sourceId, sourceVersion, dependencyId, dependencyVersion, requirement, state,
                    versionContinuous, sourceWatermark, dependencyWatermark, snapshotId,
                    snapshotImmutableHash, qmdpVersion, qmdpDigest, qshmVersion, qshmDigest,
                    lineageId);
        }
    }
}
