package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizedValue;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityReadAuditPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityView;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotActorContext;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibility;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Exact object/version audit binding committed atomically for one response projection. */
public final class JdbcQualityEligibilityReadAudit implements QualityEligibilityReadAuditPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TrustedTimeSource time;
    private final TransactionTemplate transaction;
    private final AuditTokenizationPort tokenization;

    public JdbcQualityEligibilityReadAudit(
            JdbcTemplate jdbc,
            ObjectMapper json,
            TrustedTimeSource time,
            TransactionTemplate transaction,
            AuditTokenizationPort tokenization) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.time = Objects.requireNonNull(time);
        this.transaction = Objects.requireNonNull(transaction);
        this.tokenization = Objects.requireNonNull(tokenization);
    }

    @Override
    public void record(
            List<QualityEligibility> values,
            QualitySnapshotActorContext actor,
            String action,
            String traceId) {
        if (values == null || values.isEmpty() || values.size() > 100) {
            throw new IllegalArgumentException("INGESTION_QUALITY_ELIGIBILITY_AUDIT_INVALID");
        }
        Instant now = time.now().instant();
        AuditTokenizedValue actorToken = tokenization.tokenize(
                AuditTokenizationDomain.ACTOR, actor.auditActorRef());
        AuditTokenizedValue ipToken = tokenization.tokenize(
                AuditTokenizationDomain.SOURCE_IP, actor.sourceIp());
        if (!actorToken.profileVersion().equals(ipToken.profileVersion())
                || !actorToken.keyVersion().equals(ipToken.keyVersion())) {
            throw new IllegalStateException("INGESTION_QUALITY_AUDIT_TOKEN_PROFILE_MISMATCH");
        }
        transaction.executeWithoutResult(ignored -> values.forEach(value -> {
            String id = value.eligibilityId().toString();
            String payloadDigest = digest(write(QualityEligibilityView.from(value)));
            jdbc.query("""
                    select ingestion_quality.iq_append_quality_eligibility_read_audit(
                           ?,?,?,?,?,?,?,?,?,?,?)
                    """, row -> null, CatalogUuidV7.generate(now), value.eligibilityId(),
                    value.aggregateVersion(), token("ost", "quality-eligibility:" + id),
                    token("agt", "quality-eligibility-aggregate:" + id), actorToken.value(),
                    ipToken.value(), action, traceId, Timestamp.from(now), payloadDigest);
        }));
    }

    private byte[] write(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("INGESTION_QUALITY_JSON_INVALID", invalid);
        }
    }

    private static String token(String domain, String material) {
        return domain + "_v1_k1_" + digest(material.getBytes(StandardCharsets.UTF_8))
                .substring("sha256:".length());
    }

    private static String digest(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
