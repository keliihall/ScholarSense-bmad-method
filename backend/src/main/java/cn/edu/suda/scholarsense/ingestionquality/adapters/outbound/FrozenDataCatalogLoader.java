package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuthorizationProbePort;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogEvidenceSet;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogTargetEvidencePort;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogSnapshot;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogSource;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogEvidenceScenario;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityException;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContractMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Loads only the frozen DCC and a digest-bound, target-verified report from controlled paths. */
public final class FrozenDataCatalogLoader
        implements FrozenDataCatalogSource, CatalogTargetEvidencePort, CatalogAuthorizationProbePort {
    private static final String FULL_LOCK_DIGEST =
            "sha256:d1d701338c39c9b480a06f699f09309e8d89c699333f113fc8b909aa3bc3e6e2";
    private static final String LOCK_PATH_PREFIX = "contracts/data-catalog/";
    private static final Map<Path, String> APPROVED_ADDITIVE_FILES = Map.of(
            Path.of("data-source-catalog-1.1.0.schema.json"),
                    "2b967a7b89cd8febed7cc3d2a59d79e7e702ac2b638058bfb452e9a6672695f2",
            Path.of("dcc-1.1.0.json"),
                    "aeb19962071e2144a85bb2e07fd124eff0d69edf93f7202e821204616a60f219",
            Path.of("sources/src-p0-responsibility-001-2.1.0.schema.json"),
                    "a5e3b7a1673b0c55d09eadd4cc491be297aa46f24dc83bc6e56053a33b8b86a1",
            Path.of("sources/src-p1-care-list-001-1.1.0.schema.json"),
                    "d4ed4ca282b12c23024600635546729be6550ff1b3c773209dd3c2634a4c9e01");
    private static final Map<String, String> MINIMAL_SCHEMA_DIGESTS = Map.of(
            "SRC-P0-ACCOMMODATION-001", "sha256:eb06f4eea84531e0461911bef272419b22d69737dcb0d73b12b28d2e7fde2927",
            "SRC-P0-CALENDAR-001", "sha256:7436c9709e1c331c99d6c018d3129537bb4c24db1462396b3b82abcbe2cf2f87",
            "SRC-P0-CAMPUS-ACCESS-001", "sha256:ad4594c9cfe8a59f6fdf65d5609b85e1155dbe7c8ffb2088ea2774ed0830a5e8",
            "SRC-P0-DEVICE-001", "sha256:50995d14b6c60c894658d9ae22d486a9aefb89e104acb8440dcd2d0fe3db9ecd",
            "SRC-P0-DORM-ACCESS-001", "sha256:f1b37a888a1e1fb6757c2bdd22499f2b40a400ae00f641ade8ceb03122349f14",
            "SRC-P0-LEAVE-001", "sha256:2107b63edc167ebe838b380bb39420be4f51bd0f0f2e4534f9146a163fc79263",
            "SRC-P0-TIMETABLE-001", "sha256:4b497b9f6fb2cb0fcb816897f2ec529948026eabba6f8c55f7d9da8c8a9e56e2",
            "SRC-P1-OFFCAMPUS-001", "sha256:3f932032fbc88ca9bc057cdda5456f258d965dd2dd37c3d01498236e1f094496");
    private static final Set<String> REPORT_FIELDS = Set.of(
            "reportVersion", "contractVersion", "qualityGateVersion", "authority", "environment",
            "candidateCommit", "candidateTree", "handoffRevision", "handoffDigest",
            "catalogDigest", "qualityGateDigest", "sourceCount",
            "sources", "privacy", "occurredAt", "runtimeEvidenceClaim", "result", "evidenceDigest");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "sourceId", "contractVersion", "schemaVersion", "qualityGateVersion", "environment",
            "authority", "candidateCommit", "candidateTree", "handoffRevision", "handoffDigest",
            "inputDigest", "scenarios", "result",
            "occurredAt", "signatureDigest", "evidenceDigest", "cleanupResult", "runtimeEvidenceClaim");
    private static final Set<String> SIGNED_EVIDENCE_FIELDS = Set.of(
            "sourceId", "contractVersion", "schemaVersion", "qualityGateVersion", "environment",
            "authority", "candidateCommit", "candidateTree", "handoffRevision", "handoffDigest",
            "inputDigest", "scenarios", "result",
            "occurredAt", "cleanupResult", "runtimeEvidenceClaim");
    private static final Set<String> SCENARIO_FIELDS = Set.of(
            "id", "result", "observationDigest");

    private final ObjectMapper json;
    private final Path contractRoot;
    private final Path targetReport;
    private final String immutableReportUri;
    private final byte[] trustedAuthorityKey;
    private final FrozenDataCatalogSubject expectedSubject;

    public FrozenDataCatalogLoader(
            ObjectMapper json, Path contractRoot, Path targetReport, String immutableReportUri,
            byte[] trustedAuthorityKey, FrozenDataCatalogSubject expectedSubject) {
        this.json = Objects.requireNonNull(json);
        this.contractRoot = Objects.requireNonNull(contractRoot).toAbsolutePath().normalize();
        this.targetReport = Objects.requireNonNull(targetReport).toAbsolutePath().normalize();
        this.immutableReportUri = Objects.requireNonNull(immutableReportUri);
        if (trustedAuthorityKey == null || trustedAuthorityKey.length < 32) {
            throw new IllegalArgumentException("INGESTION_QUALITY_TARGET_SIGNING_KEY_INVALID");
        }
        this.trustedAuthorityKey = trustedAuthorityKey.clone();
        this.expectedSubject = Objects.requireNonNull(expectedSubject);
    }

    @Override
    public FrozenDataCatalogSnapshot load() {
        verifyFullContractLock();
        JsonNode catalog = read(contractRoot.resolve("dcc-1.0.0.json"));
        JsonNode registry = read(contractRoot.resolve("dependency-registry-1.0.0.json"));
        JsonNode quality = read(contractRoot.resolve("qg-1.0.0.json"));
        requireDigest(catalog, FrozenDataCatalogPolicy.CATALOG_DIGEST);
        requireDigest(registry, FrozenDataCatalogPolicy.DEPENDENCY_REGISTRY_DIGEST);
        requireDigest(quality, FrozenDataCatalogPolicy.QUALITY_GATE_DIGEST);
        requireDigest(read(contractRoot.resolve("fixtures/valid/minimal-slice-boundaries-1.0.0.json")),
                FrozenDataCatalogPolicy.MINIMAL_SLICE_BOUNDARIES_DIGEST);
        requireDigest(read(contractRoot.resolve("fixtures/valid/minimal-slice-records-1.0.0.json")),
                FrozenDataCatalogPolicy.MINIMAL_SLICE_RECORDS_DIGEST);
        verifyMinimalSchemas(catalog);

        JsonNode report = read(targetReport);
        requireExact(report, REPORT_FIELDS);
        String reportDigest = text(report, "evidenceDigest");
        if (!reportDigest.equals(CanonicalCatalogJson.digestWithout(json, report, "evidenceDigest"))
                || !immutableReportUri.equals("evidence+sha256://" + reportDigest.substring("sha256:".length()))) {
            throw evidenceInvalid();
        }
        verifyReportHeader(report);

        Map<String, JsonNode> evidenceBySource = indexed(report.required("sources"), "sourceId");
        Map<String, JsonNode> descriptorBySource = indexed(catalog.required("sources"), "sourceId");
        if (!evidenceBySource.keySet().equals(FrozenDataCatalogPolicy.expectedSourceIds())
                || !descriptorBySource.keySet().equals(FrozenDataCatalogPolicy.expectedSourceIds())) {
            throw evidenceInvalid();
        }

        List<SourceContract> sources = new ArrayList<>();
        List<CatalogEvidence> evidence = new ArrayList<>();
        catalog.required("sources").forEach(descriptor -> {
            String sourceId = text(descriptor, "sourceId");
            JsonNode observed = evidenceBySource.get(sourceId);
            verifySourceEvidence(report, descriptor, observed);
            String evidenceUri = immutableReportUri + "#source=" + sourceId;
            sources.add(source(descriptor, evidenceUri));
            evidence.add(evidence(observed, evidenceUri));
        });

        List<DependencyBinding> dependencies = new ArrayList<>();
        registry.required("bindings").forEach(binding -> dependencies.add(new DependencyBinding(
                text(binding, "sourceId"), text(binding, "dependencyId"),
                DependencyRequirement.valueOf(text(binding, "requirement").toUpperCase(java.util.Locale.ROOT)),
                DependencyOperator.valueOf(text(binding, "operator").replace('-', '_')
                        .toUpperCase(java.util.Locale.ROOT)))));
        if (!dependencies.stream().collect(java.util.stream.Collectors.toMap(
                DependencyBinding::sourceId, DependencyBinding::dependencyId))
                .equals(FrozenDataCatalogPolicy.expectedDependencies())) {
            throw contractInvalid();
        }

        Instant reportTime = instant(text(report, "occurredAt"));
        java.util.UUID catalogId = CatalogUuidV7.deterministic(reportTime, reportDigest);
        DataSourceCatalog projection = DataSourceCatalog.draft(
                catalogId, "DCC-1.0.0", sources, dependencies,
                FrozenDataCatalogPolicy.CATALOG_DIGEST, reportTime);
        CatalogEvidenceSet evidenceSet = CatalogEvidenceSet.verifiedFor(projection, evidence);
        return new FrozenDataCatalogSnapshot(
                catalogId, "DCC-1.0.0", sources, dependencies,
                FrozenDataCatalogPolicy.CATALOG_DIGEST, reportTime, evidenceSet);
    }

    @Override
    public CatalogEvidenceSet verifiedEvidenceFor(DataSourceCatalog catalog) {
        FrozenDataCatalogSnapshot snapshot = load();
        if (!snapshot.catalogId().equals(catalog.catalogId())
                || !snapshot.contentDigest().equals(catalog.contentDigest())
                || !snapshot.sources().equals(catalog.sources())
                || !snapshot.dependencies().equals(catalog.dependencies())) {
            throw evidenceInvalid();
        }
        return snapshot.evidence();
    }

    @Override
    public DataSourceCatalog authorizationProbe() {
        return load().draft();
    }

    private void verifyReportHeader(JsonNode report) {
        JsonNode privacy = report.required("privacy");
        requireExact(privacy, Set.of("studentPlaintextStored", "rawBodyStored"));
        JsonNode studentPlaintextStored = privacy.required("studentPlaintextStored");
        JsonNode rawBodyStored = privacy.required("rawBodyStored");
        if (!"DCC-TARGET-REPORT-1.0.0".equals(text(report, "reportVersion"))
                || !"DCC-1.0.0".equals(text(report, "contractVersion"))
                || !"QG-1.0.0".equals(text(report, "qualityGateVersion"))
                || !FrozenDataCatalogPolicy.CATALOG_DIGEST.equals(text(report, "catalogDigest"))
                || !FrozenDataCatalogPolicy.QUALITY_GATE_DIGEST.equals(text(report, "qualityGateDigest"))
                || !expectedSubject.authority().equals(text(report, "authority"))
                || !expectedSubject.environment().equals(text(report, "environment"))
                || !expectedSubject.candidateCommit().equals(text(report, "candidateCommit"))
                || !expectedSubject.candidateTree().equals(text(report, "candidateTree"))
                || longInteger(report, "handoffRevision") < expectedSubject.minimumHandoffRevision()
                || integer(report, "sourceCount") != 17
                || !"target-verified".equals(text(report, "runtimeEvidenceClaim"))
                || !"pass".equals(text(report, "result"))
                || !studentPlaintextStored.isBoolean()
                || studentPlaintextStored.booleanValue()
                || !rawBodyStored.isBoolean()
                || rawBodyStored.booleanValue()) {
            throw evidenceInvalid();
        }
        digest(text(report, "handoffDigest"));
        instant(text(report, "occurredAt"));
    }

    private void verifySourceEvidence(JsonNode report, JsonNode descriptor, JsonNode observed) {
        requireExact(observed, EVIDENCE_FIELDS);
        if (!text(observed, "evidenceDigest").equals(
                    CanonicalCatalogJson.digestWithout(json, observed, "evidenceDigest"))
                || !text(observed, "sourceId").equals(text(descriptor, "sourceId"))
                || !"DCC-1.0.0".equals(text(observed, "contractVersion"))
                || !text(observed, "schemaVersion").equals(text(descriptor, "schemaVersion"))
                || !"QG-1.0.0".equals(text(observed, "qualityGateVersion"))
                || !text(observed, "environment").equals(text(report, "environment"))
                || !text(observed, "authority").equals(text(report, "authority"))
                || !text(observed, "candidateCommit").equals(text(report, "candidateCommit"))
                || !text(observed, "candidateTree").equals(text(report, "candidateTree"))
                || longInteger(observed, "handoffRevision")
                        != longInteger(report, "handoffRevision")
                || !text(observed, "handoffDigest").equals(text(report, "handoffDigest"))
                || !text(observed, "occurredAt").equals(text(report, "occurredAt"))
                || !"pass".equals(text(observed, "result"))
                || !"pass".equals(text(observed, "cleanupResult"))
                || !"target-verified".equals(text(observed, "runtimeEvidenceClaim"))) {
            throw evidenceInvalid();
        }
        List<String> expectedScenarios = stringsInOrder(descriptor.required("contractTests"));
        List<String> actualScenarios = new ArrayList<>();
        Set<String> uniqueScenarios = new HashSet<>();
        JsonNode scenarios = observed.required("scenarios");
        if (!scenarios.isArray() || scenarios.isEmpty()) throw evidenceInvalid();
        scenarios.forEach(scenario -> {
            requireExact(scenario, SCENARIO_FIELDS);
            if (!"pass".equals(text(scenario, "result"))
                    || !uniqueScenarios.add(text(scenario, "id"))) {
                throw evidenceInvalid();
            }
            actualScenarios.add(text(scenario, "id"));
            digest(text(scenario, "observationDigest"));
        });
        if (!actualScenarios.equals(expectedScenarios)) throw evidenceInvalid();
        verifyAuthoritySignature(observed);
    }

    private void verifyAuthoritySignature(JsonNode observed) {
        String stored = text(observed, "signatureDigest");
        digest(stored);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(trustedAuthorityKey, "HmacSHA256"));
            byte[] expected = mac.doFinal(CanonicalCatalogJson.canonicalBytesOnly(
                    json, observed, SIGNED_EVIDENCE_FIELDS));
            byte[] actual = HexFormat.of().parseHex(stored.substring("sha256:".length()));
            if (!MessageDigest.isEqual(expected, actual)) throw evidenceInvalid();
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("INGESTION_QUALITY_HMAC_UNAVAILABLE", impossible);
        } catch (IllegalArgumentException invalidHex) {
            throw evidenceInvalid();
        }
    }

    private SourceContract source(JsonNode descriptor, String evidenceUri) {
        JsonNode reconciliation = descriptor.required("reconciliation");
        JsonNode backfill = descriptor.required("backfill");
        JsonNode watermarkRequired = backfill.required("watermarkRequired");
        if (!watermarkRequired.isBoolean()) throw contractInvalid();
        SourceContractMetadata metadata = new SourceContractMetadata(
                text(descriptor, "ownerDepartment"), text(descriptor, "ownerName"),
                text(descriptor, "responsibleRole"), text(descriptor, "businessDefinition"),
                stringsInOrder(descriptor.required("businessKeys")), text(descriptor, "effectiveInterval"),
                text(descriptor, "updateFrequency"), text(descriptor, "slo"),
                text(descriptor, "coverage"), text(descriptor, "sensitivity"),
                text(descriptor, "schemaRef"), text(reconciliation, "schedule"),
                integer(reconciliation, "minimumBasisPoints"), integer(backfill, "windowDays"),
                watermarkRequired.booleanValue(),
                stringsInOrder(descriptor.required("contractTests")), text(descriptor, "consumerMode"),
                text(descriptor, "status"));
        return new SourceContract(
                text(descriptor, "sourceId"), text(descriptor, "purpose"),
                text(descriptor, "schemaVersion"), text(descriptor, "qualityGateVersion"),
                evidenceUri, RuntimeEvidenceClaim.TARGET_VERIFIED, metadata);
    }

    private CatalogEvidence evidence(JsonNode observed, String evidenceUri) {
        List<CatalogEvidenceScenario> scenarios = new ArrayList<>();
        observed.required("scenarios").forEach(item -> scenarios.add(new CatalogEvidenceScenario(
                text(item, "id"), text(item, "result"), text(item, "observationDigest"))));
        return new CatalogEvidence(
                text(observed, "sourceId"), evidenceUri, text(observed, "contractVersion"),
                text(observed, "schemaVersion"), text(observed, "qualityGateVersion"),
                text(observed, "environment"), text(observed, "authority"),
                text(observed, "candidateCommit"), text(observed, "candidateTree"),
                longInteger(observed, "handoffRevision"), text(observed, "handoffDigest"),
                text(observed, "inputDigest"), scenarios, text(observed, "result"),
                instant(text(observed, "occurredAt")), text(observed, "signatureDigest"),
                text(observed, "evidenceDigest"), text(observed, "cleanupResult"),
                RuntimeEvidenceClaim.TARGET_VERIFIED);
    }

    private void verifyMinimalSchemas(JsonNode catalog) {
        Map<String, JsonNode> descriptors = indexed(catalog.required("sources"), "sourceId");
        MINIMAL_SCHEMA_DIGESTS.forEach((sourceId, expectedDigest) -> {
            JsonNode schema = read(contractRoot.resolve(text(descriptors.get(sourceId), "schemaRef")));
            requireDigest(schema, expectedDigest);
        });
        JsonNode calendar = read(contractRoot.resolve(text(
                descriptors.get("SRC-P0-CALENDAR-001"), "schemaRef")));
        JsonNode calendarProperties = calendar.required("properties");
        if (!"BC-1.0.0".equals(text(calendarProperties.required("businessCalendarVersion"), "const"))
                || !"Asia/Shanghai".equals(text(calendarProperties.required("businessTimezone"), "const"))
                || !strings(calendarProperties.required("dayType").required("enum")).equals(Set.of(
                        "workday", "weekend", "statutory-holiday", "makeup-workday",
                        "school-holiday", "emergency-closure"))) {
            throw contractInvalid();
        }
        JsonNode timetable = read(contractRoot.resolve(text(
                descriptors.get("SRC-P0-TIMETABLE-001"), "schemaRef")));
        Set<String> timetableProperties = names(timetable.required("properties"));
        if (!Boolean.FALSE.equals(timetable.required("additionalProperties").booleanValue())
                || !timetableProperties.equals(Set.of(
                        "studentRef", "termId", "teachingClassId", "activityStartsAt",
                        "activityEndsAt", "campusCode", "locationCode", "enrollmentState",
                        "activityState", "effectiveAt", "sourceUpdatedAt", "sourceVersion",
                        "watermark", "supersedesVersion"))) {
            throw contractInvalid();
        }
    }

    private void verifyFullContractLock() {
        Path lockPath = contractRoot.resolve("data-catalog-contract-lock-1.0.1.json");
        JsonNode lock = read(lockPath);
        requireDigest(lock, FULL_LOCK_DIGEST);
        requireExact(lock, Set.of(
                "lockVersion", "catalogVersion", "qualityGateVersion", "files"));
        if (!"DCC-LOCK-1.0.1".equals(text(lock, "lockVersion"))
                || !"DCC-1.0.0".equals(text(lock, "catalogVersion"))
                || !"QG-1.0.0".equals(text(lock, "qualityGateVersion"))) {
            throw contractInvalid();
        }

        Map<Path, String> expected = new HashMap<>();
        JsonNode files = lock.required("files");
        if (!files.isArray() || files.size() != 41) throw contractInvalid();
        files.forEach(item -> {
            requireExact(item, Set.of("path", "canonicalDigest"));
            String lockedPath = text(item, "path");
            String lockedDigest = text(item, "canonicalDigest");
            digest(lockedDigest);
            if (!lockedPath.startsWith(LOCK_PATH_PREFIX)) throw contractInvalid();
            String relativeText = lockedPath.substring(LOCK_PATH_PREFIX.length());
            Path relative = Path.of(relativeText).normalize();
            if (relative.isAbsolute() || relativeText.isBlank()
                    || relative.startsWith("..")
                    || !relative.toString().replace('\\', '/').equals(relativeText)
                    || expected.put(relative, lockedDigest) != null) {
                throw contractInvalid();
            }
        });

        Set<Path> actual = new HashSet<>();
        try (var paths = Files.walk(contractRoot)) {
            paths.filter(path -> Files.isRegularFile(
                            path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> actual.add(contractRoot.relativize(path)));
        } catch (IOException unavailable) {
            throw contractInvalid();
        }
        Set<Path> expectedClosure = new HashSet<>(expected.keySet());
        expectedClosure.add(Path.of("data-catalog-contract-lock-1.0.1.json"));
        expectedClosure.addAll(APPROVED_ADDITIVE_FILES.keySet());
        if (!actual.equals(expectedClosure)) throw contractInvalid();
        expected.forEach((relative, expectedDigest) ->
                requireDigest(read(contractRoot.resolve(relative)), expectedDigest));
        APPROVED_ADDITIVE_FILES.forEach((relative, expectedDigest) ->
                requireRawDigest(contractRoot.resolve(relative), expectedDigest));
    }

    private static void requireRawDigest(Path path, String expectedDigest) {
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            byte[] expected = HexFormat.of().parseHex(expectedDigest);
            if (!MessageDigest.isEqual(expected, actual)) throw contractInvalid();
        } catch (IOException | java.security.NoSuchAlgorithmException | IllegalArgumentException failure) {
            throw contractInvalid();
        }
    }

    private JsonNode read(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if ((!normalized.equals(targetReport) && !normalized.startsWith(contractRoot))
                || !Files.isRegularFile(normalized)) {
            throw contractInvalid();
        }
        try {
            return json.readTree(Files.readString(normalized));
        } catch (IOException | JacksonException failure) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FROZEN_JSON_INVALID", failure);
        }
    }

    private void requireDigest(JsonNode value, String expected) {
        String actual = CanonicalCatalogJson.digest(json, value);
        if (!expected.equals(actual)) throw contractInvalid();
    }

    private static Map<String, JsonNode> indexed(JsonNode array, String key) {
        if (!array.isArray()) throw contractInvalid();
        Map<String, JsonNode> result = new HashMap<>();
        array.forEach(item -> {
            String value = text(item, key);
            if (result.put(value, item) != null) throw contractInvalid();
        });
        return Map.copyOf(result);
    }

    private static Set<String> names(JsonNode object) {
        Set<String> result = new HashSet<>();
        object.forEachEntry((name, ignored) -> result.add(name));
        return Set.copyOf(result);
    }

    private static void requireExact(JsonNode object, Set<String> expected) {
        if (object == null || !object.isObject() || !names(object).equals(expected)) {
            throw evidenceInvalid();
        }
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isTextual() || value.asText().isBlank()) throw evidenceInvalid();
        return value.asText();
    }

    private static int integer(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw evidenceInvalid();
        return value.asInt();
    }

    private static long longInteger(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.asLong() < 1
                || value.asLong() > FrozenDataCatalogSubject.MAX_HANDOFF_REVISION) {
            throw evidenceInvalid();
        }
        return value.asLong();
    }

    private static Set<String> strings(JsonNode values) {
        return Set.copyOf(stringsInOrder(values));
    }

    private static List<String> stringsInOrder(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) throw evidenceInvalid();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (!value.isTextual() || value.asText().isBlank()) throw evidenceInvalid();
            result.add(value.asText());
        });
        if (new HashSet<>(result).size() != result.size()) throw evidenceInvalid();
        return List.copyOf(result);
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException failure) {
            throw evidenceInvalid();
        }
    }

    private static void digest(String value) {
        if (!value.matches("^sha256:[0-9a-f]{64}$")) throw evidenceInvalid();
    }

    private static IngestionQualityException evidenceInvalid() {
        return new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
    }

    private static IngestionQualityException contractInvalid() {
        return new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_CONTRACT_INVALID);
    }
}
