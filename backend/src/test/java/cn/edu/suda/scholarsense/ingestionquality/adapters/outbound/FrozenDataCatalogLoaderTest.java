package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FrozenDataCatalogLoaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONTRACTS = Path.of(
            "..", "contracts", "data-catalog").toAbsolutePath().normalize();
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-05T00:00:00Z");
    private static final byte[] AUTHORITY_KEY =
            "target-handoff-test-key-not-a-production-secret".getBytes(StandardCharsets.UTF_8);
    private static final FrozenDataCatalogSubject SUBJECT = new FrozenDataCatalogSubject(
            "approved-campus-source", "stage", "a".repeat(40), "b".repeat(40), 7);
    private static final String CROSS_LANGUAGE_FIRST_SIGNATURE =
            "sha256:516b15529c0b310d39df1c5f4726a1164a897a5a5cb05eb8648d174ea98808d6";

    @TempDir
    Path temporary;

    @Test
    void deterministicallyLoadsTheExactFrozenCatalogAndVerifiedSeventeenSourceReport()
            throws Exception {
        ReportFixture fixture = report();
        FrozenDataCatalogLoader loader = new FrozenDataCatalogLoader(
                JSON, CONTRACTS, fixture.path(), fixture.uri(), AUTHORITY_KEY, SUBJECT);

        var first = loader.load();
        var second = loader.load();

        assertEquals(first.catalogId(), second.catalogId());
        assertEquals(17, first.sources().size());
        assertEquals(11, first.dependencies().size());
        assertEquals(17, first.evidence().items().size());
        assertEquals(first.sources().stream()
                        .sorted(Comparator.comparing(item -> item.sourceId())).toList(),
                first.sources());
        assertEquals(first.dependencies().stream()
                        .sorted(Comparator.comparing(item -> item.dependencyId())).toList(),
                first.dependencies());
        assertEquals(CROSS_LANGUAGE_FIRST_SIGNATURE,
                first.evidence().items().stream()
                        .filter(item -> item.sourceId().equals("SRC-P0-STUDENT-001"))
                        .findFirst().orElseThrow().signatureDigest());
        assertTrue(new FrozenDataCatalogPolicy().validate(first.draft()).isEmpty());
        assertEquals(first.evidence(), loader.verifiedEvidenceFor(first.draft()));
    }

    @Test
    void rejectsEndpointSelfReportedOrTamperedEvidenceEvenWhenCountRemainsSeventeen()
            throws Exception {
        ReportFixture fixture = report();
        @SuppressWarnings("unchecked")
        Map<String, Object> tampered = JSON.readValue(
                Files.readString(fixture.path()), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) tampered.get("sources");
        sources.getFirst().put("result", "skip");
        Files.writeString(fixture.path(), JSON.writeValueAsString(tampered));

        FrozenDataCatalogLoader loader = new FrozenDataCatalogLoader(
                JSON, CONTRACTS, fixture.path(), fixture.uri(), AUTHORITY_KEY, SUBJECT);

        assertThrows(IngestionQualityException.class, loader::load);
    }

    @Test
    void rejectsEvidenceSignedByAnUntrustedAuthorityKey() throws Exception {
        ReportFixture fixture = report();
        byte[] wrongKey = "wrong-authority-key-is-also-32!!".getBytes(StandardCharsets.UTF_8);
        FrozenDataCatalogLoader loader = new FrozenDataCatalogLoader(
                JSON, CONTRACTS, fixture.path(), fixture.uri(), wrongKey, SUBJECT);

        assertThrows(IngestionQualityException.class, loader::load);
    }

    @Test
    void rejectsTamperedSignatureEvenWhenAllContentDigestsAreRecomputed() throws Exception {
        ReportFixture fixture = report();
        @SuppressWarnings("unchecked")
        Map<String, Object> tampered = JSON.readValue(
                Files.readString(fixture.path()), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) tampered.get("sources");
        sources.getFirst().put("signatureDigest", "sha256:" + "0".repeat(64));
        recomputeEvidenceDigest(sources.getFirst());
        tampered.remove("evidenceDigest");
        String reportDigest = CanonicalCatalogJson.digest(
                JSON, JSON.readTree(JSON.writeValueAsString(tampered)));
        tampered.put("evidenceDigest", reportDigest);
        Files.writeString(fixture.path(), JSON.writeValueAsString(tampered));
        String uri = "evidence+sha256://" + reportDigest.substring("sha256:".length());
        FrozenDataCatalogLoader loader = new FrozenDataCatalogLoader(
                JSON, CONTRACTS, fixture.path(), uri, AUTHORITY_KEY, SUBJECT);

        assertThrows(IngestionQualityException.class, loader::load);
    }

    @Test
    void rejectsUnsignedProvenanceRelabelingAfterContentDigestsAreRecomputed() throws Exception {
        Map<String, Object> replacements = Map.of(
                "authority", "other-approved-source",
                "environment", "test",
                "candidateCommit", "c".repeat(40),
                "occurredAt", "2026-08-05T00:01:00Z");
        for (var replacement : replacements.entrySet()) {
            ReportFixture fixture = report();
            @SuppressWarnings("unchecked")
            Map<String, Object> relabeled = JSON.readValue(
                    Files.readString(fixture.path()), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources =
                    (List<Map<String, Object>>) relabeled.get("sources");
            sources.forEach(source -> {
                source.put(replacement.getKey(), replacement.getValue());
                recomputeEvidenceDigest(source);
            });
            relabeled.put(replacement.getKey(), replacement.getValue());
            relabeled.remove("evidenceDigest");
            String reportDigest = CanonicalCatalogJson.digest(
                    JSON, JSON.readTree(JSON.writeValueAsString(relabeled)));
            relabeled.put("evidenceDigest", reportDigest);
            Files.writeString(fixture.path(), JSON.writeValueAsString(relabeled));
            String uri = "evidence+sha256://" + reportDigest.substring("sha256:".length());

            FrozenDataCatalogLoader loader = new FrozenDataCatalogLoader(
                    JSON, CONTRACTS, fixture.path(), uri, AUTHORITY_KEY, SUBJECT);

            assertThrows(IngestionQualityException.class, loader::load,
                    "unsigned provenance relabel must fail: " + replacement.getKey());
        }
    }

    @Test
    void rejectsAStillValidSignedReportForAnotherReleaseSubject() throws Exception {
        ReportFixture fixture = report();
        FrozenDataCatalogLoader loader = new FrozenDataCatalogLoader(
                JSON, CONTRACTS, fixture.path(), fixture.uri(), AUTHORITY_KEY,
                new FrozenDataCatalogSubject(
                        "approved-campus-source", "stage",
                        "c".repeat(40), "b".repeat(40)));

        assertThrows(IngestionQualityException.class, loader::load);
    }

    @Test
    void rejectsAValidlyResignedSameCandidateReportBelowTheDeploymentFloor()
            throws Exception {
        ReportFixture fixture = reportAtRevision(6);

        FrozenDataCatalogLoader loader = new FrozenDataCatalogLoader(
                JSON, CONTRACTS, fixture.path(), fixture.uri(), AUTHORITY_KEY, SUBJECT);

        assertThrows(IngestionQualityException.class, loader::load);
    }

    @Test
    void acceptsAValidMonotonicRevisionBeyondTheSignedIntRange() throws Exception {
        long revision = (long) Integer.MAX_VALUE + 1;
        ReportFixture fixture = reportAtRevision(revision);
        FrozenDataCatalogSubject expected = new FrozenDataCatalogSubject(
                "approved-campus-source", "stage",
                "a".repeat(40), "b".repeat(40), revision);

        var loaded = new FrozenDataCatalogLoader(
                JSON, CONTRACTS, fixture.path(), fixture.uri(), AUTHORITY_KEY, expected).load();

        assertEquals(revision, loaded.evidence().items().getFirst().handoffRevision());
    }

    @Test
    void deploymentSubjectMustMatchTheActualRuntimeEnvironment() {
        FrozenDataCatalogBuildSubject build = new FrozenDataCatalogBuildSubject(
                "a".repeat(40), "b".repeat(40));

        assertEquals("prod", FrozenDataCatalogSubject.boundToRuntime(
                "approved-campus-source", "prod", "prod", build, 7, 7).environment());
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> FrozenDataCatalogSubject.boundToRuntime(
                        "approved-campus-source", "stage", "prod", build, 7, 7));
        assertEquals("INGESTION_QUALITY_TARGET_ENVIRONMENT_MISMATCH", mismatch.getMessage());
        IllegalStateException floorMismatch = assertThrows(
                IllegalStateException.class,
                () -> FrozenDataCatalogSubject.boundToRuntime(
                        "approved-campus-source", "prod", "prod", build, 7, 8));
        assertEquals(
                "INGESTION_QUALITY_HANDOFF_REVISION_FLOOR_MISMATCH",
                floorMismatch.getMessage());
        IllegalArgumentException devDisabled = assertThrows(
                IllegalArgumentException.class,
                () -> FrozenDataCatalogSubject.boundToRuntime(
                        "approved-campus-source", "dev", "dev", build, 7, 7));
        assertEquals("INGESTION_QUALITY_EXPECTED_SUBJECT_INVALID", devDisabled.getMessage());
        IllegalArgumentException unsafeMaximum = assertThrows(
                IllegalArgumentException.class,
                () -> FrozenDataCatalogSubject.boundToRuntime(
                        "approved-campus-source", "prod", "prod", build,
                        FrozenDataCatalogSubject.MAX_HANDOFF_REVISION + 1,
                        FrozenDataCatalogSubject.MAX_HANDOFF_REVISION + 1));
        assertEquals("INGESTION_QUALITY_EXPECTED_SUBJECT_INVALID", unsafeMaximum.getMessage());
    }

    @Test
    void rejectsTamperedOrMissingSchemaOutsideTheFormerMinimalSubset() throws Exception {
        ReportFixture fixture = report();
        Path tamperedRoot = contractCopy("tampered-contract-root");
        Path networkSchema = tamperedRoot.resolve(
                "sources/src-p1-network-001.schema.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> tampered = JSON.readValue(
                Files.readString(networkSchema), Map.class);
        tampered.put("title", "tampered-network-contract");
        Files.writeString(networkSchema, JSON.writeValueAsString(tampered));

        assertThrows(IngestionQualityException.class, () -> new FrozenDataCatalogLoader(
                JSON, tamperedRoot, fixture.path(), fixture.uri(), AUTHORITY_KEY, SUBJECT).load());

        Path missingRoot = contractCopy("missing-contract-root");
        Files.delete(missingRoot.resolve("sources/src-p1-network-001.schema.json"));
        assertThrows(IngestionQualityException.class, () -> new FrozenDataCatalogLoader(
                JSON, missingRoot, fixture.path(), fixture.uri(), AUTHORITY_KEY, SUBJECT).load());
    }

    @Test
    void rejectsTamperedApprovedAdditiveContract() throws Exception {
        ReportFixture fixture = report();
        Path tamperedRoot = contractCopy("tampered-additive-contract-root");
        Path successor = tamperedRoot.resolve("dcc-1.1.0.json");
        Files.writeString(successor, Files.readString(successor) + "\n");

        assertThrows(IngestionQualityException.class, () -> new FrozenDataCatalogLoader(
                JSON, tamperedRoot, fixture.path(), fixture.uri(), AUTHORITY_KEY, SUBJECT).load());
    }

    @Test
    void rejectsUnapprovedAdditionalContractFile() throws Exception {
        ReportFixture fixture = report();
        Path expandedRoot = contractCopy("expanded-contract-root");
        Files.writeString(expandedRoot.resolve("unapproved-successor.json"), "{}\n");

        assertThrows(IngestionQualityException.class, () -> new FrozenDataCatalogLoader(
                JSON, expandedRoot, fixture.path(), fixture.uri(), AUTHORITY_KEY, SUBJECT).load());
    }

    @Test
    void rejectsTextualFalseInTargetReportPrivacyClaims() throws Exception {
        ReportFixture fixture = report();
        @SuppressWarnings("unchecked")
        Map<String, Object> textual = JSON.readValue(
                Files.readString(fixture.path()), Map.class);
        textual.put("privacy", Map.of(
                "studentPlaintextStored", "false",
                "rawBodyStored", false));
        textual.remove("evidenceDigest");
        String reportDigest = CanonicalCatalogJson.digest(
                JSON, JSON.readTree(JSON.writeValueAsString(textual)));
        textual.put("evidenceDigest", reportDigest);
        Files.writeString(fixture.path(), JSON.writeValueAsString(textual));

        assertThrows(IngestionQualityException.class, () -> new FrozenDataCatalogLoader(
                JSON, CONTRACTS, fixture.path(),
                "evidence+sha256://" + reportDigest.substring(7),
                AUTHORITY_KEY, SUBJECT).load());
    }

    private Path contractCopy(String directory) throws Exception {
        Path copy = temporary.resolve(directory);
        try (var paths = Files.walk(CONTRACTS)) {
            for (Path source : paths.toList()) {
                Path destination = copy.resolve(CONTRACTS.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return copy;
    }

    private ReportFixture report() throws Exception {
        JsonNode catalog = JSON.readTree(Files.readString(CONTRACTS.resolve("dcc-1.0.0.json")));
        List<Map<String, Object>> evidence = new ArrayList<>();
        catalog.required("sources").forEach(descriptor -> {
            String sourceId = descriptor.required("sourceId").asText();
            List<Map<String, Object>> scenarios = new ArrayList<>();
            descriptor.required("contractTests").forEach(test -> scenarios.add(Map.of(
                    "id", test.asText(), "result", "pass",
                    "observationDigest", "sha256:" + "d".repeat(64))));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", sourceId);
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
            item.put("signatureDigest", signature(item, AUTHORITY_KEY));
            recomputeEvidenceDigest(item);
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
        JsonNode reportNode = JSON.readTree(JSON.writeValueAsString(report));
        String reportDigest = CanonicalCatalogJson.digest(JSON, reportNode);
        report.put("evidenceDigest", reportDigest);
        Path path = temporary.resolve("target-report.json");
        Files.writeString(path, JSON.writeValueAsString(report));
        return new ReportFixture(path, "evidence+sha256://" + reportDigest.substring(7));
    }

    private ReportFixture reportAtRevision(long revision) throws Exception {
        ReportFixture fixture = report();
        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = JSON.readValue(
                Files.readString(fixture.path()), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources =
                (List<Map<String, Object>>) rewritten.get("sources");
        sources.forEach(source -> {
            source.put("handoffRevision", revision);
            source.put("signatureDigest", signature(source, AUTHORITY_KEY));
            recomputeEvidenceDigest(source);
        });
        rewritten.put("handoffRevision", revision);
        rewritten.remove("evidenceDigest");
        String reportDigest = CanonicalCatalogJson.digest(
                JSON, JSON.readTree(JSON.writeValueAsString(rewritten)));
        rewritten.put("evidenceDigest", reportDigest);
        Files.writeString(fixture.path(), JSON.writeValueAsString(rewritten));
        return new ReportFixture(
                fixture.path(),
                "evidence+sha256://" + reportDigest.substring("sha256:".length()));
    }

    private static void recomputeEvidenceDigest(Map<String, Object> item) {
        try {
            item.remove("evidenceDigest");
            JsonNode itemNode = JSON.readTree(JSON.writeValueAsString(item));
            item.put("evidenceDigest", CanonicalCatalogJson.digest(JSON, itemNode));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String signature(Map<String, Object> evidence, byte[] key) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            evidence.forEach((field, value) -> {
                if (!field.equals("signatureDigest") && !field.equals("evidenceDigest")) {
                    payload.put(field, value);
                }
            });
            JsonNode node = JSON.readTree(JSON.writeValueAsString(payload));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return "sha256:" + HexFormat.of().formatHex(
                    mac.doFinal(CanonicalCatalogJson.canonicalBytes(JSON, node)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record ReportFixture(Path path, String uri) {}
}
