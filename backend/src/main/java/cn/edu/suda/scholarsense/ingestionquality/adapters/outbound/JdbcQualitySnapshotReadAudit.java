package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizedValue;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotActorContext;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotReadAuditPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Commits the sensitive snapshot-read fact and its relay outbox row atomically in PostgreSQL. */
public final class JdbcQualitySnapshotReadAudit implements QualitySnapshotReadAuditPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditTokenizationPort tokenization;
    private final TrustedTimeSource time;

    public JdbcQualitySnapshotReadAudit(
            JdbcTemplate jdbc,
            ObjectMapper json,
            AuditTokenizationPort tokenization,
            TrustedTimeSource time) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.tokenization = Objects.requireNonNull(tokenization);
        this.time = Objects.requireNonNull(time);
    }

    @Override
    public void record(
            QualitySnapshot snapshot,
            QualitySnapshotActorContext actor,
            String action,
            String traceId) {
        TrustedTime trusted = time.now();
        Instant now = trusted.instant();
        AuditTokenizedValue actorToken = token(
                AuditTokenizationDomain.ACTOR, actor.auditActorRef());
        AuditTokenizedValue objectToken = token(
                AuditTokenizationDomain.OBJECT, snapshot.snapshotId().toString());
        AuditTokenizedValue ipToken = token(
                AuditTokenizationDomain.SOURCE_IP, actor.sourceIp());
        AuditTokenizedValue aggregateToken = token(
                AuditTokenizationDomain.AGGREGATE, snapshot.snapshotId().toString());
        requireSameProfile(actorToken, objectToken, ipToken, aggregateToken);
        UUID auditId = CatalogUuidV7.generate(now);
        UUID eventId = CatalogUuidV7.generate(now);
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("decision", "allow");
        authorization.put("policyVersion", "RFP-1.0.0");
        authorization.put("scopeCodes", List.of("OWNED_SOURCE"));
        authorization.put("grantSearchTokens", List.of());
        authorization.put("notApplicableReason", null);
        LocalAuditFact fact = new LocalAuditFact(
                auditId, "LOCAL-AUDIT-FACT-1.0.0", "ingestion-quality", ActorType.USER,
                actorToken.value(), List.of("R6"), authorization, action,
                "quality-snapshot", objectToken.value(), "accepted",
                "INGESTION_QUALITY_SNAPSHOT_READ_ALLOWED", "DATA_QUALITY", "OWNED_SOURCE",
                now, now, trusted.profile(), ipToken.value(), actorToken.profileVersion(),
                actorToken.keyVersion(), traceId, "quality-snapshot", aggregateToken.value(),
                snapshot.aggregateVersion(), null, Map.of("roleFieldPolicy", "RFP-1.0.0"),
                "RS-1.0.0");
        LocalAuditOutboxRecord record = LocalAuditOutboxRecord.forFact(eventId, fact, now);
        String payload = write(record);
        String digest = CanonicalCatalogJson.digest(json, readTree(payload))
                .substring("sha256:".length());
        jdbc.query("""
                select ingestion_quality.iq_append_quality_snapshot_read_audit(
                       ?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """, ignored -> null, auditId, eventId, snapshot.snapshotId(), actorToken.value(),
                objectToken.value(), ipToken.value(), aggregateToken.value(), action,
                snapshot.aggregateVersion(), traceId, Timestamp.from(now), payload, digest);
    }

    private AuditTokenizedValue token(AuditTokenizationDomain domain, String value) {
        return Objects.requireNonNull(tokenization.tokenize(domain, value));
    }

    private static void requireSameProfile(AuditTokenizedValue... values) {
        String profile = values[0].profileVersion();
        String key = values[0].keyVersion();
        for (AuditTokenizedValue value : values) {
            if (!profile.equals(value.profileVersion()) || !key.equals(value.keyVersion())) {
                throw new IllegalStateException("INGESTION_QUALITY_AUDIT_TOKEN_PROFILE_MISMATCH");
            }
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("INGESTION_QUALITY_JSON_INVALID", invalid);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(value);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("INGESTION_QUALITY_JSON_INVALID", invalid);
        }
    }
}
