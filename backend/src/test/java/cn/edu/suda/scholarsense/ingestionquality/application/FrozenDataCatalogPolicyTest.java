package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContractMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FrozenDataCatalogPolicyTest {
    private static final UUID ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000031");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CATALOG = Path.of(
            "..", "contracts", "data-catalog", "dcc-1.0.0.json")
            .toAbsolutePath().normalize();

    @Test
    void rejectsTwelveBindingsWhenDuplicateSourceWouldOverwriteAnExtraDependency() throws Exception {
        List<DependencyBinding> twelve = new ArrayList<>();
        twelve.add(new DependencyBinding(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-EXTRA-999",
                DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF));
        twelve.addAll(CatalogFixtures.dependencies());
        assertEquals(12, twelve.size());
        assertEquals(12, twelve.stream().map(DependencyBinding::dependencyId).distinct().count());

        List<CatalogContractViolation> failures = new FrozenDataCatalogPolicy().validate(
                catalog(exactSources(), twelve));

        assertTrue(failures.stream().anyMatch(item ->
                item.code().equals("DCC_DEPENDENCY_SET_INVALID")
                        && item.fieldPath().equals("dependencies")));
    }

    @Test
    void rejectsWrongExactMappingEvenWhenCountsRemainEleven() {
        List<DependencyBinding> wrong = new ArrayList<>(CatalogFixtures.dependencies());
        wrong.set(0, new DependencyBinding(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-DORM-ACCESS-001",
                DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF));
        wrong.set(1, new DependencyBinding(
                "SRC-P0-DORM-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001",
                DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF));
        DataSourceCatalog catalog = DataSourceCatalog.draft(
                ID, "DCC-1.0.0", CatalogFixtures.sources(), wrong,
                "sha256:" + "a".repeat(64), Instant.parse("2026-08-05T00:00:00Z"));

        List<CatalogContractViolation> failures = new FrozenDataCatalogPolicy().validate(catalog);

        assertTrue(failures.stream().anyMatch(item ->
                item.code().equals("DCC_DEPENDENCY_SET_INVALID")
                        && item.fieldPath().startsWith("dependencies")));
    }

    @Test
    void rejectsIncompleteDescriptorsAndMinimalSliceProjection() {
        DataSourceCatalog catalog = CatalogFixtures.draft(
                ID, Instant.parse("2026-08-05T00:00:00Z"));

        List<CatalogContractViolation> failures = new FrozenDataCatalogPolicy().validate(catalog);

        assertTrue(failures.stream().anyMatch(item -> item.code().equals("DCC_SOURCE_DESCRIPTOR_INVALID")));
        assertTrue(failures.stream().anyMatch(item -> item.code().equals("DCC_CALENDAR_SLICE_INVALID")));
        assertTrue(failures.stream().anyMatch(item -> item.code().equals("DCC_TIMETABLE_SLICE_INVALID")));
    }

    @Test
    void rejectsMeaningfulDescriptorSubstitutionsForEveryFrozenFieldFamily() throws Exception {
        List<SourceContract> exact = exactSources();
        SourceContract original = exact.getFirst();
        SourceContractMetadata metadata = original.metadata();
        List<SourceContractMetadata> substitutions = List.of(
                changed(metadata, "Mallory", null, null, null),
                changed(metadata, null, "99.9% within one minute", null, null),
                changed(metadata, null, null, List.of("studentRef", "alternateEffectiveFrom"), null),
                changed(metadata, null, null, null, List.of("provider", "consumer", "alternate")));

        DataSourceCatalog baseline = catalog(exact);
        assertTrue(new FrozenDataCatalogPolicy().validate(baseline).isEmpty());
        int canonicalIndex = baseline.sources().indexOf(original);
        assertTrue(canonicalIndex >= 0);
        for (SourceContractMetadata replacement : substitutions) {
            List<SourceContract> mutated = new ArrayList<>(exact);
            mutated.set(0, new SourceContract(
                    original.sourceId(), original.purpose(), original.schemaVersion(),
                    original.qualityGateVersion(), original.evidenceUri(),
                    original.runtimeEvidenceClaim(), replacement));

            List<CatalogContractViolation> failures =
                    new FrozenDataCatalogPolicy().validate(catalog(mutated));

            assertTrue(failures.stream().anyMatch(item ->
                    item.code().equals("DCC_SOURCE_DESCRIPTOR_INVALID")
                            && item.fieldPath().equals(
                                    "sources[" + canonicalIndex + "].metadata")));
        }
    }

    private static DataSourceCatalog catalog(List<SourceContract> sources) {
        return catalog(sources, CatalogFixtures.dependencies());
    }

    private static DataSourceCatalog catalog(
            List<SourceContract> sources, List<DependencyBinding> dependencies) {
        return DataSourceCatalog.draft(
                ID, "DCC-1.0.0", sources, dependencies,
                FrozenDataCatalogPolicy.CATALOG_DIGEST,
                Instant.parse("2026-08-05T00:00:00Z"));
    }

    private static List<SourceContract> exactSources() throws Exception {
        JsonNode document = JSON.readTree(Files.readString(CATALOG));
        List<SourceContract> result = new ArrayList<>();
        document.required("sources").forEach(descriptor -> {
            JsonNode reconciliation = descriptor.required("reconciliation");
            JsonNode backfill = descriptor.required("backfill");
            SourceContractMetadata metadata = new SourceContractMetadata(
                    text(descriptor, "ownerDepartment"), text(descriptor, "ownerName"),
                    text(descriptor, "responsibleRole"), text(descriptor, "businessDefinition"),
                    strings(descriptor.required("businessKeys")),
                    text(descriptor, "effectiveInterval"), text(descriptor, "updateFrequency"),
                    text(descriptor, "slo"), text(descriptor, "coverage"),
                    text(descriptor, "sensitivity"), text(descriptor, "schemaRef"),
                    text(reconciliation, "schedule"),
                    reconciliation.required("minimumBasisPoints").asInt(),
                    backfill.required("windowDays").asInt(),
                    backfill.required("watermarkRequired").asBoolean(),
                    strings(descriptor.required("contractTests")),
                    text(descriptor, "consumerMode"), text(descriptor, "status"));
            result.add(new SourceContract(
                    text(descriptor, "sourceId"), text(descriptor, "purpose"),
                    text(descriptor, "schemaVersion"), text(descriptor, "qualityGateVersion"),
                    "evidence+sha256://" + "f".repeat(64),
                    RuntimeEvidenceClaim.TARGET_VERIFIED, metadata));
        });
        return List.copyOf(result);
    }

    private static SourceContractMetadata changed(
            SourceContractMetadata value, String ownerName, String slo,
            List<String> businessKeys, List<String> contractTests) {
        return new SourceContractMetadata(
                value.ownerDepartment(), ownerName == null ? value.ownerName() : ownerName,
                value.responsibleRole(), value.businessDefinition(),
                businessKeys == null ? value.businessKeys() : businessKeys,
                value.effectiveInterval(), value.updateFrequency(), slo == null ? value.slo() : slo,
                value.coverage(), value.sensitivity(), value.schemaRef(), value.reconciliation(),
                value.reconciliationMinimumBasisPoints(), value.backfillWindowDays(),
                value.watermarkRequired(),
                contractTests == null ? value.contractTests() : contractTests,
                value.consumerMode(), value.status());
    }

    private static String text(JsonNode parent, String field) {
        return parent.required(field).asText();
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }
}
