package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Cross-adapter fixture that emits the same signed, intentionally non-lexicographic report as target CI. */
public final class FrozenDataCatalogTestReport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONTRACTS = Path.of(
            "..", "contracts", "data-catalog").toAbsolutePath().normalize();
    private static final byte[] KEY =
            "target-handoff-test-key-not-a-production-secret".getBytes(StandardCharsets.UTF_8);
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-05T00:00:00Z");

    private FrozenDataCatalogTestReport() {}

    public static Fixture create(Path directory) throws Exception {
        JsonNode catalog = JSON.readTree(Files.readString(CONTRACTS.resolve("dcc-1.0.0.json")));
        List<Map<String, Object>> evidence = new ArrayList<>();
        catalog.required("sources").forEach(descriptor -> {
            List<Map<String, Object>> scenarios = new ArrayList<>();
            descriptor.required("contractTests").forEach(test -> scenarios.add(Map.of(
                    "id", test.asText(), "result", "pass",
                    "observationDigest", "sha256:" + "d".repeat(64))));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", descriptor.required("sourceId").asText());
            item.put("contractVersion", "DCC-1.0.0");
            item.put("schemaVersion", descriptor.required("schemaVersion").asText());
            item.put("qualityGateVersion", "QG-1.0.0");
            item.put("environment", "stage");
            item.put("authority", "approved-campus-source");
            item.put("candidateCommit", "a".repeat(40));
            item.put("candidateTree", "b".repeat(40));
            item.put("handoffRevision", 7);
            item.put("handoffDigest", "sha256:" + "e".repeat(64));
            item.put("inputDigest", "sha256:" + "c".repeat(64));
            item.put("scenarios", scenarios);
            item.put("result", "pass");
            item.put("occurredAt", OBSERVED_AT.toString());
            item.put("cleanupResult", "pass");
            item.put("runtimeEvidenceClaim", "target-verified");
            item.put("signatureDigest", signature(item));
            item.put("evidenceDigest", digest(item));
            evidence.add(item);
        });
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportVersion", "DCC-TARGET-REPORT-1.0.0");
        report.put("contractVersion", "DCC-1.0.0");
        report.put("qualityGateVersion", "QG-1.0.0");
        report.put("authority", "approved-campus-source");
        report.put("environment", "stage");
        report.put("candidateCommit", "a".repeat(40));
        report.put("candidateTree", "b".repeat(40));
        report.put("handoffRevision", 7);
        report.put("handoffDigest", "sha256:" + "e".repeat(64));
        report.put("catalogDigest", FrozenDataCatalogPolicy.CATALOG_DIGEST);
        report.put("qualityGateDigest", FrozenDataCatalogPolicy.QUALITY_GATE_DIGEST);
        report.put("sourceCount", 17);
        report.put("sources", evidence);
        report.put("privacy", Map.of("studentPlaintextStored", false, "rawBodyStored", false));
        report.put("occurredAt", OBSERVED_AT.toString());
        report.put("runtimeEvidenceClaim", "target-verified");
        report.put("result", "pass");
        String reportDigest = digest(report);
        report.put("evidenceDigest", reportDigest);
        Path reportPath = directory.resolve("target-report.json");
        Files.writeString(reportPath, JSON.writeValueAsString(report));
        return new Fixture(
                CONTRACTS, reportPath,
                "evidence+sha256://" + reportDigest.substring("sha256:".length()),
                KEY.clone(),
                new FrozenDataCatalogSubject(
                        "approved-campus-source", "stage",
                        "a".repeat(40), "b".repeat(40), 7));
    }

    private static String digest(Map<String, Object> value) {
        try {
            Map<String, Object> unsigned = new LinkedHashMap<>(value);
            unsigned.remove("evidenceDigest");
            return CanonicalCatalogJson.digest(
                    JSON, JSON.readTree(JSON.writeValueAsString(unsigned)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String signature(Map<String, Object> evidence) {
        try {
            JsonNode node = JSON.readTree(JSON.writeValueAsString(evidence));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return "sha256:" + HexFormat.of().formatHex(
                    mac.doFinal(CanonicalCatalogJson.canonicalBytes(JSON, node)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public record Fixture(
            Path contractRoot,
            Path reportPath,
            String reportUri,
            byte[] signingKey,
            FrozenDataCatalogSubject subject) {
        public Fixture {
            signingKey = signingKey.clone();
        }

        @Override
        public byte[] signingKey() {
            return signingKey.clone();
        }
    }
}
