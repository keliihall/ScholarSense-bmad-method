package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Canonical retention-scope material shared by snapshot commit and retention evidence code. */
public final class QualitySnapshotRetentionScopeCanonicalizer {
    public static final String OBJECT_TYPE = "QualitySnapshot";
    public static final String POLICY_VERSION = "QUALITY-SNAPSHOT-RETENTION-1.0.0";
    public static final String SCHEDULE_VERSION = "RS-1.0.0";

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final DateTimeFormatter UTC_SECONDS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .withZone(ZoneOffset.UTC);

    private QualitySnapshotRetentionScopeCanonicalizer() {}

    /** Builds the exact nine-field material frozen by the Task 0 retention policy. */
    public static Material materialize(QualitySnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        return new Material(
                OBJECT_TYPE,
                snapshot.snapshotId(),
                snapshot.sourceId(),
                snapshot.aggregateVersion(),
                snapshot.evaluatedAt(),
                retentionDueAt(snapshot.evaluatedAt()),
                snapshot.immutableHash(),
                POLICY_VERSION,
                snapshot.retentionScheduleVersion());
    }

    /** Applies P2Y in the UTC calendar; Java's plusYears supplies end-of-month clamping. */
    public static Instant retentionDueAt(Instant evaluatedAt) {
        requireMicrosecond(evaluatedAt);
        return evaluatedAt.atZone(ZoneOffset.UTC).plusYears(2).toInstant();
    }

    public static byte[] canonicalUtf8(QualitySnapshot snapshot) {
        return canonicalUtf8(materialize(snapshot));
    }

    public static byte[] canonicalUtf8(Material material) {
        Objects.requireNonNull(material);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("objectType", material.objectType());
        value.put("snapshotId", material.snapshotId().toString());
        value.put("sourceId", material.sourceId());
        value.put("snapshotAggregateVersion", material.snapshotAggregateVersion());
        value.put("evaluatedAt", canonicalInstant(material.evaluatedAt()));
        value.put("retentionDueAt", canonicalInstant(material.retentionDueAt()));
        value.put("snapshotImmutableHash", material.snapshotImmutableHash());
        value.put("retentionPolicyVersion", material.retentionPolicyVersion());
        value.put("retentionScheduleVersion", material.retentionScheduleVersion());
        return DataBatchCanonicalJson.bytes(value);
    }

    public static String digest(QualitySnapshot snapshot) {
        return digest(materialize(snapshot));
    }

    public static String digest(Material material) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalUtf8(material)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String canonicalInstant(Instant value) {
        requireMicrosecond(value);
        String seconds = UTC_SECONDS.format(value);
        int micros = value.getNano() / 1_000;
        return micros == 0 ? seconds + "Z" : seconds + ".%06dZ".formatted(micros);
    }

    private static void requireMicrosecond(Instant value) {
        Objects.requireNonNull(value);
        int year = value.atZone(ZoneOffset.UTC).getYear();
        if (value.getNano() % 1_000 != 0 || year < 1 || year > 9_999) {
            throw invalid();
        }
    }

    /** Typed material is public so Java retention producers can reuse one exact encoder. */
    public record Material(
            String objectType,
            UUID snapshotId,
            String sourceId,
            long snapshotAggregateVersion,
            Instant evaluatedAt,
            Instant retentionDueAt,
            String snapshotImmutableHash,
            String retentionPolicyVersion,
            String retentionScheduleVersion) {
        public Material {
            objectType = requireText(objectType);
            snapshotId = Objects.requireNonNull(snapshotId);
            sourceId = requireText(sourceId);
            if (snapshotAggregateVersion < 1 || snapshotAggregateVersion > MAX_SAFE_INTEGER) {
                throw invalid();
            }
            requireMicrosecond(evaluatedAt);
            requireMicrosecond(retentionDueAt);
            if (snapshotImmutableHash == null
                    || !snapshotImmutableHash.matches("sha256:[0-9a-f]{64}")) {
                throw invalid();
            }
            retentionPolicyVersion = requireText(retentionPolicyVersion);
            retentionScheduleVersion = requireText(retentionScheduleVersion);
        }

        private static String requireText(String value) {
            if (value == null || value.isBlank()) throw invalid();
            return value;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_RETENTION_SCOPE_INVALID");
    }
}
