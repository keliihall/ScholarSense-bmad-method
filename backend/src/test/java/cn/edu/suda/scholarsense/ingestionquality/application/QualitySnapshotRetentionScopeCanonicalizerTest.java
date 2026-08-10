package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualitySnapshotRetentionScopeCanonicalizerTest {
    private static final String SNAPSHOT_HASH = "sha256:" + "1".repeat(64);
    private static final String EXACT_DUE_DIGEST =
            "sha256:083f0779c7b4926163dd9f8df9fe500c0f2d8672b8f6e1b54ae83bf4c203ff62";
    private static final String LEAP_DIGEST =
            "sha256:8a2ad943c1f7dc49a738c4794ad70f1c560b9585ee72eaf8eca3721380fd751e";

    @Test
    void exactTask0GoldensBindCanonicalBytesAndUtcCalendarClamp() {
        var exact = material(
                Instant.parse("2024-08-09T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:00Z"));
        String canonical = new String(
                QualitySnapshotRetentionScopeCanonicalizer.canonicalUtf8(exact),
                StandardCharsets.UTF_8);

        assertEquals(
                "{\"evaluatedAt\":\"2024-08-09T00:00:00Z\","
                        + "\"objectType\":\"QualitySnapshot\","
                        + "\"retentionDueAt\":\"2026-08-09T00:00:00Z\","
                        + "\"retentionPolicyVersion\":"
                        + "\"QUALITY-SNAPSHOT-RETENTION-1.0.0\","
                        + "\"retentionScheduleVersion\":\"RS-1.0.0\","
                        + "\"snapshotAggregateVersion\":9,"
                        + "\"snapshotId\":\"019d2c7d-4000-7000-8000-000000000110\","
                        + "\"snapshotImmutableHash\":\"" + SNAPSHOT_HASH + "\","
                        + "\"sourceId\":\"SRC-P0-STUDENT-001\"}",
                canonical);
        assertEquals(EXACT_DUE_DIGEST,
                QualitySnapshotRetentionScopeCanonicalizer.digest(exact));

        Instant leapDay = Instant.parse("2024-02-29T12:00:00Z");
        Instant clamped = QualitySnapshotRetentionScopeCanonicalizer.retentionDueAt(leapDay);
        assertEquals(Instant.parse("2026-02-28T12:00:00Z"), clamped);
        assertEquals(LEAP_DIGEST,
                QualitySnapshotRetentionScopeCanonicalizer.digest(material(leapDay, clamped)));
    }

    @Test
    void runtimeInstantConventionOmitsZeroFractionAndEmitsSixDigitsOtherwise() {
        var material = material(
                Instant.parse("2024-08-09T00:00:00.999000Z"),
                Instant.parse("2026-08-09T00:00:00.999000Z"));

        String canonical = new String(
                QualitySnapshotRetentionScopeCanonicalizer.canonicalUtf8(material),
                StandardCharsets.UTF_8);

        assertTrue(canonical.contains("\"2024-08-09T00:00:00.999000Z\""));
        assertTrue(canonical.contains("\"2026-08-09T00:00:00.999000Z\""));
        assertThrows(IllegalArgumentException.class, () -> material(
                Instant.parse("2024-08-09T00:00:00.000000001Z"),
                Instant.parse("2026-08-09T00:00:00Z")));
    }

    @Test
    void everyFrozenMaterialFieldMutationChangesTheDigest() {
        var base = material(
                Instant.parse("2024-08-09T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:00Z"));
        Map<String, QualitySnapshotRetentionScopeCanonicalizer.Material> mutations =
                new LinkedHashMap<>();
        mutations.put("objectType", copy("OtherSnapshot", base.snapshotId(),
                base.sourceId(), base.snapshotAggregateVersion(), base.evaluatedAt(),
                base.retentionDueAt(), base.snapshotImmutableHash(),
                base.retentionPolicyVersion(), base.retentionScheduleVersion()));
        mutations.put("snapshotId", copy(base.objectType(),
                UUID.fromString("019d2c7d-4000-7000-8000-000000000111"), base.sourceId(),
                base.snapshotAggregateVersion(), base.evaluatedAt(), base.retentionDueAt(),
                base.snapshotImmutableHash(), base.retentionPolicyVersion(),
                base.retentionScheduleVersion()));
        mutations.put("sourceId", copy(base.objectType(), base.snapshotId(),
                "SRC-P0-CARD-001", base.snapshotAggregateVersion(), base.evaluatedAt(),
                base.retentionDueAt(), base.snapshotImmutableHash(),
                base.retentionPolicyVersion(), base.retentionScheduleVersion()));
        mutations.put("snapshotAggregateVersion", copy(base.objectType(),
                base.snapshotId(), base.sourceId(), 10L, base.evaluatedAt(),
                base.retentionDueAt(), base.snapshotImmutableHash(),
                base.retentionPolicyVersion(), base.retentionScheduleVersion()));
        mutations.put("evaluatedAt", copy(base.objectType(), base.snapshotId(),
                base.sourceId(), base.snapshotAggregateVersion(),
                base.evaluatedAt().plusSeconds(1), base.retentionDueAt(),
                base.snapshotImmutableHash(), base.retentionPolicyVersion(),
                base.retentionScheduleVersion()));
        mutations.put("retentionDueAt", copy(base.objectType(), base.snapshotId(),
                base.sourceId(), base.snapshotAggregateVersion(), base.evaluatedAt(),
                base.retentionDueAt().plusSeconds(1), base.snapshotImmutableHash(),
                base.retentionPolicyVersion(), base.retentionScheduleVersion()));
        mutations.put("snapshotImmutableHash", copy(base.objectType(),
                base.snapshotId(), base.sourceId(), base.snapshotAggregateVersion(),
                base.evaluatedAt(), base.retentionDueAt(), "sha256:" + "2".repeat(64),
                base.retentionPolicyVersion(), base.retentionScheduleVersion()));
        mutations.put("retentionPolicyVersion", copy(base.objectType(),
                base.snapshotId(), base.sourceId(), base.snapshotAggregateVersion(),
                base.evaluatedAt(), base.retentionDueAt(), base.snapshotImmutableHash(),
                "QUALITY-SNAPSHOT-RETENTION-1.0.1", base.retentionScheduleVersion()));
        mutations.put("retentionScheduleVersion", copy(base.objectType(),
                base.snapshotId(), base.sourceId(), base.snapshotAggregateVersion(),
                base.evaluatedAt(), base.retentionDueAt(), base.snapshotImmutableHash(),
                base.retentionPolicyVersion(), "RS-1.0.1"));

        assertEquals(9, mutations.size());
        mutations.forEach((field, mutation) -> assertNotEquals(
                EXACT_DUE_DIGEST,
                QualitySnapshotRetentionScopeCanonicalizer.digest(mutation), field));
    }

    private static QualitySnapshotRetentionScopeCanonicalizer.Material material(
            Instant evaluatedAt, Instant retentionDueAt) {
        return new QualitySnapshotRetentionScopeCanonicalizer.Material(
                "QualitySnapshot",
                UUID.fromString("019d2c7d-4000-7000-8000-000000000110"),
                "SRC-P0-STUDENT-001",
                9L,
                evaluatedAt,
                retentionDueAt,
                SNAPSHOT_HASH,
                "QUALITY-SNAPSHOT-RETENTION-1.0.0",
                "RS-1.0.0");
    }

    private static QualitySnapshotRetentionScopeCanonicalizer.Material copy(
            String objectType,
            UUID snapshotId,
            String sourceId,
            long aggregateVersion,
            Instant evaluatedAt,
            Instant retentionDueAt,
            String immutableHash,
            String policyVersion,
            String scheduleVersion) {
        return new QualitySnapshotRetentionScopeCanonicalizer.Material(
                objectType, snapshotId, sourceId, aggregateVersion, evaluatedAt,
                retentionDueAt, immutableHash, policyVersion, scheduleVersion);
    }
}
