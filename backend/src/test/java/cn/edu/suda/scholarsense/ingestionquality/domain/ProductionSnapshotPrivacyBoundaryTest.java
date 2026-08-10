package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.application.RecordQualityImpactScopeCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Contract for the production-only text boundary in Story 2.3.
 *
 * <p>The approved QSHM transport remains Unicode-scalar capable. Only the production seal and
 * impact-staging entry points are narrowed so a student identifier or free text cannot become a
 * two-year snapshot, event, log, or trace value.
 */
class ProductionSnapshotPrivacyBoundaryTest {
    private static final UUID BATCH_ID =
            UUID.fromString("019fe780-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID =
            UUID.fromString("019fe780-0000-7000-8000-000000000002");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-10T01:00:00Z");
    private static final String SOURCE_ID = "SRC-P0-CARD-001";
    private static final String MANIFEST_DIGEST = "sha256:" + "a".repeat(64);
    private static final Path QSHM_GOLDENS = Path.of("..").toAbsolutePath().normalize()
            .resolve("contracts/ingestion-quality/batch-quality/fixtures/valid/"
                    + "quality-snapshot-hash-vectors-1.0.0.json");
    private static final Path JDBC_FAILURES = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
                    + "adapters/outbound/DataBatchJdbcFailures.java");

    @Test
    void productionSealAcceptsOnlySourceBoundDateOrOpaqueSha256Watermarks() {
        for (String rejected : List.of(
                "wm-7",
                "wm-test-1",
                "student-20260001",
                "student name and class",
                "学生王某某20260001",
                "src-p0-card-001@2026-08-10\0student-20260001",
                "SRC-P0-CARD-001@2026-08-10",
                "src-p0-student-001@2026-08-10",
                "src-p0-card-001@2026-02-30",
                "sha256:" + "A".repeat(64))) {
            IngestionQualityException failure = assertThrows(
                    IngestionQualityException.class,
                    () -> receiving().seal(
                            manifest(rejected), contractEvidence(), RECEIVED_AT.plusSeconds(60)),
                    rejected);
            assertEquals("INGESTION_QUALITY_CONTRACT_INVALID", failure.code());
        }

        DataBatch sourceBound = receiving().seal(
                manifest("src-p0-card-001@2026-08-10"),
                contractEvidence(), RECEIVED_AT.plusSeconds(60));
        DataBatch opaque = receiving().seal(
                manifest("sha256:" + "b".repeat(64)),
                contractEvidence(), RECEIVED_AT.plusSeconds(60));

        assertEquals("src-p0-card-001@2026-08-10", sourceBound.manifest().watermark());
        assertEquals("sha256:" + "b".repeat(64), opaque.manifest().watermark());
    }

    @Test
    void javaImpactCommandRejectsSubjectTextButAllowsControlledMetricIdShape() {
        for (String rejected : List.of(
                "student-20260001",
                "student name and class",
                "学生王某某20260001",
                "PRIMARY_KEY_COMPLETENESS_BP\0student-20260001")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RecordQualityImpactScopeCommand(
                            BATCH_ID, rejected, RECEIVED_AT),
                    rejected);
            assertEquals("INGESTION_QUALITY_REQUEST_INVALID", failure.getMessage());
        }

        assertDoesNotThrow(() -> new RecordQualityImpactScopeCommand(
                BATCH_ID, "PRIMARY_KEY_COMPLETENESS_BP", RECEIVED_AT));
        assertDoesNotThrow(() -> new RecordQualityImpactScopeCommand(
                BATCH_ID, "SOURCE_CONTINUITY_GATE", RECEIVED_AT));
    }

    @Test
    void genericQshmUnicodeAndFrozenTaskZeroBytesRemainUntouched() throws IOException {
        assertEquals(
                "80637ea3fc15aeecccef5a1afd0b3924a1f3adc98e1044507b797a7c72626199",
                sha256(Files.readAllBytes(QSHM_GOLDENS)),
                "the production-only restriction must not rewrite the frozen QSHM vector");

        String canonical = new String(QualityCanonicalJson.canonicalUtf8(Map.of(
                "watermark", "任意Unicode\0student-20260001",
                "impactScopeCodes", List.of("\0终", "自由文本"))), StandardCharsets.UTF_8);
        assertTrue(canonical.contains("\"watermark\":\"任意Unicode\\u0000student-20260001\""));
        assertTrue(canonical.contains("\"impactScopeCodes\":[\"\\u0000终\",\"自由文本\"]"));
    }

    @Test
    void databaseImpactRejectionHasAnExactStableJavaTranslation() throws IOException {
        assertTrue(Files.readString(JDBC_FAILURES).contains(
                "stable(\"INGESTION_QUALITY_IMPACT_SCOPE_INVALID\")"));
    }

    @Test
    void snapshotReadSurfaceHasNoSensitiveFieldOrHighCardinalityTelemetrySeam()
            throws IOException {
        List<String> fields = Arrays.stream(QualitySnapshotView.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase()).toList();
        for (String forbidden : List.of(
                "student", "recordid", "businesskey", "evidencebody",
                "diagnosis", "counseling", "contact")) {
            assertTrue(fields.stream().noneMatch(name -> name.contains(forbidden)), forbidden);
        }

        Path sourceRoot = Path.of("src/main/java/cn/edu/suda/scholarsense/");
        for (Path source : List.of(
                sourceRoot.resolve("ingestionquality/application/QualitySnapshotQueryService.java"),
                sourceRoot.resolve("ingestionquality/adapters/inbound/QualitySnapshotController.java"),
                sourceRoot.resolve("ingestionquality/adapters/outbound/JdbcQualitySnapshotQueryStore.java"),
                sourceRoot.resolve("ingestionquality/adapters/outbound/JdbcQualitySnapshotReadAudit.java"))) {
            String text = Files.readString(source);
            for (String forbidden : List.of(
                    "LoggerFactory", "MeterRegistry", "ObservationRegistry",
                    "io.opentelemetry", ".tag(", ".attribute(")) {
                assertTrue(!text.contains(forbidden),
                        source.getFileName() + " must not emit ad-hoc/high-cardinality telemetry: "
                                + forbidden);
            }
        }
    }

    private static DataBatch receiving() {
        return DataBatch.receiving(
                BATCH_ID,
                new BatchIdentity(SOURCE_ID, "card-partition:2026-08-10", 1),
                BatchLineage.root(LINEAGE_ID, RECEIVED_AT.minusSeconds(30)),
                MANIFEST_DIGEST,
                RECEIVED_AT,
                "0123456789abcdef0123456789abcdef");
    }

    private static BatchManifest manifest(String watermark) {
        return new BatchManifest(
                1, 1, 0,
                new BatchObservationWindow(
                        RECEIVED_AT.minusSeconds(720L * 3_600L), RECEIVED_AT),
                RECEIVED_AT, "Asia/Shanghai", watermark,
                "CARD-SLICE-1.0.0", "sha256:" + "1".repeat(64),
                "DCC-1.1.0", "sha256:" + "2".repeat(64),
                "QG-1.0.0", "sha256:" + "3".repeat(64),
                "QMDP-1.0.0", "sha256:" + "4".repeat(64),
                RECEIVED_AT.minusSeconds(1), RECEIVED_AT.plusSeconds(60), RECEIVED_AT,
                "transaction-record", MANIFEST_DIGEST);
    }

    private static SealedQualityContractEvidence contractEvidence() {
        return new SealedQualityContractEvidence(
                "QMDP-1.0.0", "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                "EXECUTABLE-QUALITY-CONTRACT-LOCK-1.0.0",
                "sha256:" + "3".repeat(64), "sha256:" + "4".repeat(64),
                "AUTH-2026-08-08-001", "AUTH-2026-08-08-001", RECEIVED_AT,
                "QSHM-1.0.0", "sha256:" + "5".repeat(64),
                "sha256:" + "6".repeat(64), "QSHM-CONTRACT-LOCK-1.0.0",
                "sha256:" + "7".repeat(64), "AUTH-2026-08-09-001",
                "AUTH-2026-08-09-001", RECEIVED_AT);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
