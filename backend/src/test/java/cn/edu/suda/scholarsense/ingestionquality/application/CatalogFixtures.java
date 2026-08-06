package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogEvidenceScenario;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

public final class CatalogFixtures {
    private CatalogFixtures() {}

    public static DataSourceCatalog draft(UUID id, Instant now) {
        return DataSourceCatalog.draft(
                id, "DCC-1.0.0",
                sources(), dependencies(),
                "sha256:" + "a".repeat(64), now);
    }

    public static List<SourceContract> sources() {
        return List.of(
                source("SRC-P0-STUDENT-001", "authoritative-student-status", "STUDENT-SLICE-1.0.0"),
                source("SRC-P0-RESPONSIBILITY-001", "responsibility-authority-v2-reference", "RESPONSIBILITY-AUTHORITY-V2-2.0.0"),
                source("SRC-P0-ACCOMMODATION-001", "accommodation-exclusion", "ACCOMMODATION-SLICE-1.0.0"),
                source("SRC-P0-CARD-001", "consumption-signal-input", "CARD-SLICE-1.0.0"),
                source("SRC-P0-CAMPUS-ACCESS-001", "campus-access-safety-input", "CAMPUS-ACCESS-SLICE-1.0.0"),
                source("SRC-P0-DORM-ACCESS-001", "dorm-access-safety-input", "DORM-ACCESS-SLICE-1.0.0"),
                source("SRC-P0-DEVICE-001", "access-device-availability", "DEVICE-SLICE-1.0.0"),
                source("SRC-P0-LEAVE-001", "leave-internship-exclusion", "LEAVE-SLICE-1.0.0"),
                source("SRC-P0-CALENDAR-001", "business-calendar-control", "BC-1.0.0"),
                source("SRC-P0-TIMETABLE-001", "teaching-activity-exclusion", "TIMETABLE-SLICE-1.0.0"),
                source("SRC-P1-OFFCAMPUS-001", "offcampus-exclusion", "OFFCAMPUS-SLICE-1.0.0"),
                source("SRC-P1-NETWORK-001", "night-network-aggregate", "NETWORK-AGGREGATE-1.0.0"),
                source("SRC-P1-ACADEMIC-001", "academic-node-signal", "ACADEMIC-NODE-1.0.0"),
                source("SRC-P1-CARE-LIST-001", "care-list-governed-input", "CARE-LIST-SLICE-1.0.0"),
                source("SRC-P1-PSYCH-DEID-001", "approved-psych-deidentified-signal", "PSYCH-DEID-SLICE-1.0.0"),
                source("SRC-P1-AID-001", "aid-status-context-not-econ-hit", "AID-SLICE-1.0.0"),
                source("SRC-P1-WORK-VISIT-001", "work-record-only", "WORK-VISIT-SLICE-1.0.0"));
    }

    public static List<DependencyBinding> dependencies() {
        return List.of(
                dependency("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001"),
                dependency("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001"),
                dependency("SRC-P0-ACCOMMODATION-001", "DEP-P0-ACCOMMODATION-001"),
                dependency("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001"),
                dependency("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001"),
                dependency("SRC-P0-CARD-001", "DEP-P0-CONSUMPTION-001"),
                dependency("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001"),
                dependency("SRC-P0-DEVICE-001", "DEP-P0-DEVICE-001"),
                dependency("SRC-P1-OFFCAMPUS-001", "DEP-P1-OFFCAMPUS-001"),
                dependency("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001"),
                dependency("SRC-P1-ACADEMIC-001", "DEP-P1-ACADEMIC-001"));
    }

    public static CatalogEvidenceSet evidence(DataSourceCatalog catalog, Instant occurredAt) {
        List<CatalogEvidence> items = IntStream.range(0, catalog.sources().size())
                .mapToObj(index -> {
                    SourceContract source = catalog.sources().get(index);
                    String hex = Integer.toHexString(index + 1);
                    String digestBody = hex.repeat(64).substring(0, 64);
                    return new CatalogEvidence(
                            source.sourceId(), "evidence+sha256://" + digestBody,
                            "DCC-1.0.0", source.schemaVersion(), "QG-1.0.0", "stage",
                            "approved-campus-source", "a".repeat(40), "b".repeat(40),
                            7, "sha256:" + "f".repeat(64),
                            "sha256:" + "c".repeat(64),
                            List.of(new CatalogEvidenceScenario(
                                    "provider", "pass", "sha256:" + "d".repeat(64))),
                            "pass", occurredAt, "sha256:" + "e".repeat(64),
                            "sha256:" + digestBody, "pass", RuntimeEvidenceClaim.TARGET_VERIFIED);
                }).toList();
        return CatalogEvidenceSet.verifiedFor(catalog, items);
    }

    private static SourceContract source(String id, String purpose, String schemaVersion) {
        return new SourceContract(
                id, purpose, schemaVersion, "QG-1.0.0",
                "evidence+sha256://" + "f".repeat(64), RuntimeEvidenceClaim.TARGET_VERIFIED);
    }

    private static DependencyBinding dependency(String sourceId, String dependencyId) {
        return new DependencyBinding(
                sourceId, dependencyId, DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF);
    }
}
