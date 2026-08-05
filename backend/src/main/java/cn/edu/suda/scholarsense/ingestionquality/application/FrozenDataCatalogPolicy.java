package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContractMetadata;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Complete runtime projection of the immutable DCC-1.0.0/QG-1.0.0 baseline. */
public final class FrozenDataCatalogPolicy implements CatalogContractPolicyPort {
    public static final String CATALOG_DIGEST =
            "sha256:f9731ed0084fbedfea166355ef023b6e066cc361d4c206085c81e2fdf7736c58";
    public static final String DEPENDENCY_REGISTRY_DIGEST =
            "sha256:8b21481e6daf474c1d34a99b9e3f951569120d4c6ca81f5bea540f21bab08ded";
    public static final String QUALITY_GATE_DIGEST =
            "sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a";
    public static final String MINIMAL_SLICE_BOUNDARIES_DIGEST =
            "sha256:833f35be4f3e0bc6b183ac9fe871d3d06d137c8f37a9bb8bc2cd2631e58c22fe";
    public static final String MINIMAL_SLICE_RECORDS_DIGEST =
            "sha256:588a0d33aaf352e35c9a6d082683d5509dcdac9b34e0dcb0ebcec423b7b6f9df";

    private static final Map<String, ExpectedSource> SOURCES = sources();
    private static final Map<String, String> DEPENDENCIES = dependencies();
    private static final int EXPECTED_DEPENDENCY_COUNT = 11;
    private static final Set<String> SENSITIVITY = Set.of(
            "internal", "sensitive", "highly-sensitive-deidentified");

    @Override
    public List<CatalogContractViolation> validate(DataSourceCatalog catalog) {
        List<CatalogContractViolation> failures = new ArrayList<>();
        Map<String, SourceContract> actualSources = new LinkedHashMap<>();
        catalog.sources().forEach(source -> actualSources.put(source.sourceId(), source));
        if (!actualSources.keySet().equals(SOURCES.keySet())
                || !CATALOG_DIGEST.equals(catalog.contentDigest())) {
            failures.add(failure("DCC_SOURCE_SET_INVALID", "sources"));
        }

        for (int index = 0; index < catalog.sources().size(); index++) {
            SourceContract source = catalog.sources().get(index);
            String path = "sources[" + index + "]";
            ExpectedSource expected = SOURCES.get(source.sourceId());
            if (expected == null || !expected.purpose().equals(source.purpose())
                    || !expected.schemaVersion().equals(source.schemaVersion())
                    || !"QG-1.0.0".equals(source.qualityGateVersion())
                    || !expected.consumerMode().equals(source.metadata().consumerMode())) {
                failures.add(failure("DCC_SOURCE_SET_INVALID", path));
            }
            if (expected == null || !complete(source.metadata(), source.sourceId())
                    || !expected.descriptorDigest().equals(descriptorDigest(source))) {
                failures.add(failure("DCC_SOURCE_DESCRIPTOR_INVALID", path + ".metadata"));
            }
            if (source.runtimeEvidenceClaim() != RuntimeEvidenceClaim.TARGET_VERIFIED) {
                failures.add(failure("DCC_EVIDENCE_MISSING", path + ".evidenceUri"));
            }
            if ("SRC-P0-CALENDAR-001".equals(source.sourceId()) && !calendar(source)) {
                failures.add(failure("DCC_CALENDAR_SLICE_INVALID", path));
            }
            if ("SRC-P0-TIMETABLE-001".equals(source.sourceId()) && !timetable(source)) {
                failures.add(failure("DCC_TIMETABLE_SLICE_INVALID", path));
            }
        }

        Set<String> dependencySourceIds = new HashSet<>();
        boolean dependencyShapeValid =
                catalog.dependencies().size() == EXPECTED_DEPENDENCY_COUNT;
        for (var binding : catalog.dependencies()) {
            String expectedDependency = DEPENDENCIES.get(binding.sourceId());
            dependencyShapeValid &= dependencySourceIds.add(binding.sourceId());
            dependencyShapeValid &= expectedDependency != null
                    && expectedDependency.equals(binding.dependencyId());
            dependencyShapeValid &= binding.requirement() == DependencyRequirement.REQUIRED
                    && binding.operator() == DependencyOperator.ALL_OF;
        }
        dependencyShapeValid &= dependencySourceIds.equals(DEPENDENCIES.keySet());
        if (!dependencyShapeValid) {
            failures.add(failure("DCC_DEPENDENCY_SET_INVALID", "dependencies"));
        }
        return List.copyOf(failures);
    }

    public static Set<String> expectedSourceIds() {
        return SOURCES.keySet();
    }

    public static Map<String, String> expectedDependencies() {
        return DEPENDENCIES;
    }

    private static boolean complete(SourceContractMetadata value, String sourceId) {
        return meaningful(value.ownerDepartment()) && meaningful(value.ownerName())
                && meaningful(value.responsibleRole()) && meaningful(value.businessDefinition())
                && value.businessKeys() != null && !value.businessKeys().isEmpty()
                && value.businessKeys().stream().allMatch(FrozenDataCatalogPolicy::meaningful)
                && "half-open-utc".equals(value.effectiveInterval())
                && meaningful(value.updateFrequency()) && meaningful(value.slo())
                && meaningful(value.coverage()) && SENSITIVITY.contains(value.sensitivity())
                && expectedSchemaRef(sourceId).equals(value.schemaRef())
                && meaningful(value.reconciliation())
                && value.reconciliationMinimumBasisPoints() >= 9950
                && value.reconciliationMinimumBasisPoints() <= 10000
                && value.backfillWindowDays() > 0 && value.watermarkRequired()
                && value.contractTests() != null && !value.contractTests().isEmpty()
                && value.contractTests().stream().allMatch(FrozenDataCatalogPolicy::meaningful)
                && Set.of("rule-dependency", "purpose-isolated").contains(value.consumerMode())
                && "approved-contract".equals(value.status());
    }

    private static boolean calendar(SourceContract source) {
        SourceContractMetadata value = source.metadata();
        return "BC-1.0.0".equals(source.schemaVersion())
                && "business-calendar-control".equals(source.purpose())
                && value.businessKeys().equals(List.of("localDate", "effectiveAt"))
                && "today-90d through today+180d".equals(value.coverage())
                && value.backfillWindowDays() == 270
                && value.contractTests().contains("gap-overlap-correction");
    }

    private static boolean timetable(SourceContract source) {
        SourceContractMetadata value = source.metadata();
        return "TIMETABLE-SLICE-1.0.0".equals(source.schemaVersion())
                && "teaching-activity-exclusion".equals(source.purpose())
                && value.businessKeys().equals(
                        List.of("studentRef", "teachingClassId", "activityStartsAt"))
                && value.coverage().contains("teaching activities only")
                && value.contractTests().contains("half-open-correction")
                && value.watermarkRequired();
    }

    private static boolean meaningful(String value) {
        return value != null && !value.isBlank()
                && !Set.of("未登记", "unregistered", "tbd", "todo", "unknown")
                        .contains(value.toLowerCase(Locale.ROOT));
    }

    private static String expectedSchemaRef(String sourceId) {
        return "sources/" + sourceId.toLowerCase(Locale.ROOT) + ".schema.json";
    }

    /**
     * Stable, length-delimited projection of every frozen source descriptor field. Evidence URI and
     * runtime claim are deliberately excluded because the verified target report supplies them.
     */
    private static String descriptorDigest(SourceContract source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, "DCC-DESCRIPTOR-FINGERPRINT-1.0.0");
            put(digest, source.sourceId());
            put(digest, source.purpose());
            put(digest, source.schemaVersion());
            put(digest, source.qualityGateVersion());
            SourceContractMetadata value = source.metadata();
            put(digest, value.ownerDepartment());
            put(digest, value.ownerName());
            put(digest, value.responsibleRole());
            put(digest, value.businessDefinition());
            put(digest, value.businessKeys());
            put(digest, value.effectiveInterval());
            put(digest, value.updateFrequency());
            put(digest, value.slo());
            put(digest, value.coverage());
            put(digest, value.sensitivity());
            put(digest, value.schemaRef());
            put(digest, value.reconciliation());
            put(digest, value.reconciliationMinimumBasisPoints());
            put(digest, value.backfillWindowDays());
            digest.update((byte) (value.watermarkRequired() ? 1 : 0));
            put(digest, value.contractTests());
            put(digest, value.consumerMode());
            put(digest, value.status());
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void put(MessageDigest digest, List<String> values) {
        put(digest, values == null ? -1 : values.size());
        if (values != null) values.forEach(value -> put(digest, value));
    }

    private static void put(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void put(MessageDigest digest, String value) {
        if (value == null) {
            put(digest, -1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        put(digest, encoded.length);
        digest.update(encoded);
    }

    private static Map<String, ExpectedSource> sources() {
        Map<String, ExpectedSource> values = new LinkedHashMap<>();
        add(values, "SRC-P0-STUDENT-001", "authoritative-student-status", "STUDENT-SLICE-1.0.0", "purpose-isolated",
                "sha256:b1894188f527d0fcba0fabd06758596bf2eb09136c6bac9e70f9308c2d841525");
        add(values, "SRC-P0-RESPONSIBILITY-001", "responsibility-authority-v2-reference", "RESPONSIBILITY-AUTHORITY-V2-2.0.0", "purpose-isolated",
                "sha256:90f2a98dd7290b2c8447642519e4c79930029ba11e16f49f10e03146b8bc58b8");
        add(values, "SRC-P0-ACCOMMODATION-001", "accommodation-exclusion", "ACCOMMODATION-SLICE-1.0.0", "rule-dependency",
                "sha256:b13ded86030242153a1e537dc3f9ac3ca62398896d227eeabb42b8eb9496f971");
        add(values, "SRC-P0-CARD-001", "consumption-signal-input", "CARD-SLICE-1.0.0", "rule-dependency",
                "sha256:3128a73a6a42fddd099f32fc0873877f5f64ece0b2b848dd71e55e8339040e22");
        add(values, "SRC-P0-CAMPUS-ACCESS-001", "campus-access-safety-input", "CAMPUS-ACCESS-SLICE-1.0.0", "rule-dependency",
                "sha256:68d70df2c417c7bf85454e397926a7affe97e694849aad41709c523a2f71687b");
        add(values, "SRC-P0-DORM-ACCESS-001", "dorm-access-safety-input", "DORM-ACCESS-SLICE-1.0.0", "rule-dependency",
                "sha256:6d3c4f23c84d1e4f6e2ccc96291a1ae2b256733f8c0ea1d0d36aa6298b7cc01f");
        add(values, "SRC-P0-DEVICE-001", "access-device-availability", "DEVICE-SLICE-1.0.0", "rule-dependency",
                "sha256:cf6344fdb9d6380cb5d0f9f76b0c6567d0fc61b14ba8b76ef184ab0f4cdb54cf");
        add(values, "SRC-P0-LEAVE-001", "leave-internship-exclusion", "LEAVE-SLICE-1.0.0", "rule-dependency",
                "sha256:c129d812cb839f52c935c0e36608e9b836699f4c0c19f0a3a5f49d8797a45666");
        add(values, "SRC-P0-CALENDAR-001", "business-calendar-control", "BC-1.0.0", "rule-dependency",
                "sha256:30074726678ac6938a81c9439ceae2554a4e346d16c94015d3bb7ab83723dea5");
        add(values, "SRC-P0-TIMETABLE-001", "teaching-activity-exclusion", "TIMETABLE-SLICE-1.0.0", "rule-dependency",
                "sha256:b50a3ece9cf8c2b508d5f60e05f984e176e948a98d1417a857aaaeec14a1e71a");
        add(values, "SRC-P1-OFFCAMPUS-001", "offcampus-exclusion", "OFFCAMPUS-SLICE-1.0.0", "rule-dependency",
                "sha256:5969f65b8f719ccd7550027f96c375804302a4518ae4bdd7820e7a8a5035fc6a");
        add(values, "SRC-P1-NETWORK-001", "night-network-aggregate", "NETWORK-AGGREGATE-1.0.0", "rule-dependency",
                "sha256:454678d64efb93113b9b362e770dc9501eeb07076a0d04047c7e7fe758fefa00");
        add(values, "SRC-P1-ACADEMIC-001", "academic-node-signal", "ACADEMIC-NODE-1.0.0", "rule-dependency",
                "sha256:f2330403f288540c58f2823bc3513259516cf0420ff8f663d1b31f0627bc4efc");
        add(values, "SRC-P1-CARE-LIST-001", "care-list-governed-input", "CARE-LIST-SLICE-1.0.0", "purpose-isolated",
                "sha256:4105d5e3710e2e1380df66034e5241ba8b41dce2f1522f76c3b0ff5e1cabb1e3");
        add(values, "SRC-P1-PSYCH-DEID-001", "approved-psych-deidentified-signal", "PSYCH-DEID-SLICE-1.0.0", "purpose-isolated",
                "sha256:e2d660799cade6a1e0dc91917abaffbd69ff3439cbecd345ac7a13b93fb8dc4d");
        add(values, "SRC-P1-AID-001", "aid-status-context-not-econ-hit", "AID-SLICE-1.0.0", "purpose-isolated",
                "sha256:bdc05c756f1c37367ce8ad19a1beeb065d0f86d80eec0d54996a51b599694c9f");
        add(values, "SRC-P1-WORK-VISIT-001", "work-record-only", "WORK-VISIT-SLICE-1.0.0", "purpose-isolated",
                "sha256:7ea621ca930946d8e0066da7a90426d282296eb5f69663f7c2e3155a831597ea");
        return Map.copyOf(values);
    }

    private static void add(
            Map<String, ExpectedSource> values, String id, String purpose,
            String schemaVersion, String consumerMode, String descriptorDigest) {
        values.put(id, new ExpectedSource(
                purpose, schemaVersion, consumerMode, descriptorDigest));
    }

    private static Map<String, String> dependencies() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001");
        values.put("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001");
        values.put("SRC-P0-ACCOMMODATION-001", "DEP-P0-ACCOMMODATION-001");
        values.put("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001");
        values.put("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001");
        values.put("SRC-P0-CARD-001", "DEP-P0-CONSUMPTION-001");
        values.put("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001");
        values.put("SRC-P0-DEVICE-001", "DEP-P0-DEVICE-001");
        values.put("SRC-P1-OFFCAMPUS-001", "DEP-P1-OFFCAMPUS-001");
        values.put("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001");
        values.put("SRC-P1-ACADEMIC-001", "DEP-P1-ACADEMIC-001");
        return Map.copyOf(values);
    }

    private static CatalogContractViolation failure(String code, String path) {
        return new CatalogContractViolation(code, path);
    }

    private record ExpectedSource(
            String purpose, String schemaVersion, String consumerMode, String descriptorDigest) {}
}
