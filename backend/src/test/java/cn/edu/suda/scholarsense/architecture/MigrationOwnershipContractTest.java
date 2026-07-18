package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MigrationOwnershipContractTest {

    private static final Path REGISTRY = Path.of("src/main/resources/db/module-ownership.csv");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void registryAndOwnerDirectoriesMatchAd2WithoutPrematureBusinessTables() throws Exception {
        MigrationRules.Result result = MigrationRules.validate(REGISTRY, MIGRATIONS);
        assertTrue(result.violations().isEmpty(), () -> String.join("\n", result.violations()));
        assertEquals(expectedFacts(), result.ownership().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().factOwners())));
        try (var walk = Files.walk(MIGRATIONS)) {
            assertEquals(0, walk.filter(path -> path.toString().endsWith(".sql")).count(),
                    "Story 1.1b must not create empty business tables");
        }
    }

    @Test
    void invalidMigrationFixturesAreRejectedForEveryRequiredReason() throws Exception {
        assertRejected("duplicate-version", "MIGRATION_VERSION_DUPLICATE");
        assertRejected("unknown-owner", "UNKNOWN_OWNER");
        assertRejected("cross-schema", "CROSS_SCHEMA_REFERENCE");
        assertRejected("cross-module-foreign-key", "CROSS_MODULE_FOREIGN_KEY");
        assertRejected("quoted-cross-owner", "CROSS_SCHEMA_REFERENCE");
        assertRejected("quoted-cross-owner", "CROSS_MODULE_FOREIGN_KEY");
        assertRejected("duplicate-fact-owner", "FACT_OWNER_DUPLICATE");
        assertRejected("cross-owner-index", "TABLE_PREFIX_MISMATCH");
        assertRejected("alias-scope-leak", "CROSS_SCHEMA_REFERENCE");
        assertRejectedCount("index-syntax-bypass", "TABLE_PREFIX_MISMATCH", 4);
    }

    @Test
    void validTableAliasQualifiedColumnsAreAccepted() throws Exception {
        Path root = Path.of("src/test/resources/migration/fixtures/alias-column-valid");
        MigrationRules.Result result = MigrationRules.validate(
                root.resolve("module-ownership.csv"), root.resolve("migration"));
        assertTrue(result.violations().isEmpty(), () -> String.join("\n", result.violations()));
    }

    private void assertRejected(String fixture, String reason) throws Exception {
        Path root = Path.of("src/test/resources/migration/fixtures").resolve(fixture);
        MigrationRules.Result result = MigrationRules.validate(root.resolve("module-ownership.csv"), root.resolve("migration"));
        assertTrue(result.violations().stream().anyMatch(value -> value.startsWith(reason)),
                () -> fixture + " did not report " + reason + ":\n" + String.join("\n", result.violations()));
    }

    private void assertRejectedCount(String fixture, String reason, long expectedCount) throws Exception {
        Path root = Path.of("src/test/resources/migration/fixtures").resolve(fixture);
        MigrationRules.Result result = MigrationRules.validate(root.resolve("module-ownership.csv"), root.resolve("migration"));
        long actualCount = result.violations().stream().filter(value -> value.startsWith(reason)).count();
        assertEquals(expectedCount, actualCount,
                () -> fixture + " reported unexpected violations:\n" + String.join("\n", result.violations()));
    }

    private Map<String, Set<String>> expectedFacts() {
        return Map.of(
                "identity-access", Set.of("IdentityPolicy", "Grant", "DelegationGrant"),
                "subject-registry", Set.of("StudentRef", "SubjectMapping"),
                "ingestion-quality", Set.of("DataBatch", "NormalizedFact", "QualitySnapshot"),
                "rule-governance", Set.of("RuleDefinition", "RuleVersion", "Tag", "GovernanceApproval", "QueuePolicyVersion"),
                "signal-evaluation", Set.of("RuleEvaluation"),
                "clue-care", Set.of("CandidateAdmissionDecision", "Candidate", "Clue", "EvidenceSnapshot", "CareAction", "Observation", "TaskLink", "ExplanationFeedback"),
                "collaboration", Set.of("TransferOrder", "TransferEvent"),
                "reporting", Set.of("CrossDomainReadModel", "Report", "Export", "GovernanceAction"),
                "audit-operations", Set.of("AuditLedger", "ArchiveStatus", "RetentionExecution", "DeletionReceipt"));
    }
}
