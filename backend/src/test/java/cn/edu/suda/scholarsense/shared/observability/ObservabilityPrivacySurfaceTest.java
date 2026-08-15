package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservabilityPrivacySurfaceTest {
    private static final List<String> DENIED_VALUES = List.of(
            "张三",
            "20260001",
            "证据正文-不可导出",
            "api-key-secret",
            "Bearer secret-token",
            "session=cookie-secret",
            "-----BEGIN CERTIFICATE-----");

    @Test
    void spanAndMetricDimensionsRejectIdsSecretsTokensCookiesAndCertificates() {
        for (String denied : DENIED_VALUES) {
            assertThrows(IllegalArgumentException.class,
                    () -> SafeObservationAttributes.create().low("code", denied), denied);
        }
        assertThrows(IllegalArgumentException.class, () ->
                SafeObservationAttributes.create().low(
                        "traceId", "00112233445566778899aabbccddeeff"));
        assertThrows(IllegalArgumentException.class, () ->
                SafeObservationAttributes.create().low("studentId", "20260001"));
    }

    @Test
    void approvedLowCardinalityKeysRejectArbitraryStudentAndObjectIdentifiers() {
        for (String identifier : List.of(
                "student-31415926",
                "subject-550e8400-e29b-41d4-a716-446655440000",
                "01jz8m4q9y7x6w5v4u3t2s1r0q",
                "unlisted-object")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SafeObservationAttributes.create().low("outcome", identifier),
                    identifier);
            assertThrows(IllegalArgumentException.class,
                    () -> SafeObservationAttributes.create().low("operation", identifier),
                    identifier);
        }

        SafeObservationAttributes approved = SafeObservationAttributes.create()
                .low("service", "scholarsense")
                .low("module", "ingestion-quality")
                .low("operation", "event.consume")
                .low("outcome", "duplicate")
                .high("aggregateVersion", "7");
        assertTrue(approved.metricLabels().containsValue("duplicate"));
        assertTrue(approved.spanAttributes().containsValue("7"));
        assertThrows(IllegalArgumentException.class, () ->
                SafeObservationAttributes.create().high("aggregateVersion", "student-7"));
    }

    @Test
    void checkedInValidAndOrderingEventFixturesContainNoDeniedValues() throws Exception {
        Path base = Path.of("..", "contracts", "events", "ingestion-quality", "fixtures");
        for (String directory : List.of("valid", "ordering")) {
            try (var files = Files.walk(base.resolve(directory))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String content = Files.readString(file);
                    for (String denied : DENIED_VALUES) {
                        assertTrue(!content.contains(denied), file + " contains " + denied);
                    }
                }
            }
        }
    }
}
