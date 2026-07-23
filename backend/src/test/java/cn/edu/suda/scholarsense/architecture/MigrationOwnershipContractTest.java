package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void registryAndOwnerDirectoriesMatchAd2WithIdentityAccessOwnedSessionTables() throws Exception {
        MigrationRules.Result result = MigrationRules.validate(REGISTRY, MIGRATIONS);
        assertTrue(result.violations().isEmpty(), () -> String.join("\n", result.violations()));
        assertEquals(expectedFacts(), result.ownership().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().factOwners())));
        try (var walk = Files.walk(MIGRATIONS)) {
            assertEquals(5, walk.filter(path -> path.toString().endsWith(".sql")).count(),
                    "Stories 1.2 through 1.5 own exactly five forward migrations");
        }
        Path firstMigration = MIGRATIONS.resolve(
                "identity-access/V000001__identity-access__session_boundary.sql");
        assertEquals(
                "796eb3610b955c103bd89e274dbfb3f2d9f1370b6c06ef7f5fa20c5ae0360338",
                sha256(firstMigration),
                "V000001 is an immutable delivered migration");
        String migration = Files.readString(firstMigration);
        for (String table : Set.of(
                "ia_identity_session", "ia_refresh_secret", "ia_idempotency_result",
                "ia_oidc_attempt", "ia_continuation", "ia_local_audit_fact", "ia_remote_logout_outbox")) {
            assertTrue(migration.contains("identity_access." + table), () -> "missing owned table " + table);
        }
    }

    @Test
    void story13MigrationAddsOneFactExtensionAndSeparateAppendOnlyAuditOutbox() throws Exception {
        String migration = Files.readString(MIGRATIONS.resolve(
                "identity-access/V000002__identity-access__local_audit_v1.sql"));

        assertTrue(migration.contains("alter table identity_access.ia_local_audit_fact"));
        assertTrue(migration.contains("create table identity_access.ia_local_audit_outbox"));
        assertTrue(migration.contains("event_id uuid primary key"));
        assertTrue(migration.contains("audit_id uuid not null unique"));
        assertTrue(migration.contains("foreign key (audit_id) references identity_access.ia_local_audit_fact(audit_id)"));
        assertFalse(migration.toLowerCase().contains("producer_sequence"));
        assertFalse(migration.toLowerCase().contains("ledger_sequence"));
        assertFalse(migration.toLowerCase().contains("previous_hash"));
        assertTrue(migration.contains("grant insert ("));
        assertTrue(migration.contains("on identity_access.ia_local_audit_fact to scholarsense_identity_online"));
        assertFalse(migration.contains("grant insert (\n    schema_version"));
        assertTrue(migration.contains("alter column schema_version set default 'LOCAL-AUDIT-FACT-1.0.0'"));
        assertTrue(migration.contains("revoke all privileges on identity_access.ia_local_audit_fact"));
    }

    @Test
    void story14MigrationCreatesGaplessAppendOnlyAuditOwnedLedgerAndLeastPrivilegeRoles() throws Exception {
        String migration = Files.readString(MIGRATIONS.resolve(
                "audit-operations/V000003__audit-operations__immutable_ledger_v1.sql"));
        String lower = migration.toLowerCase();

        for (String table : Set.of(
                "ao_audit_ledger", "ao_audit_ledger_head", "ao_ingestion_receipt",
                "ao_verification_run", "ao_integrity_finding", "ao_finding_disposition",
                "ao_alert_outbox", "ao_availability_observation")) {
            assertTrue(lower.contains("audit_operations." + table), table);
        }
        assertTrue(lower.contains("ledger_sequence bigint primary key"));
        assertFalse(lower.contains("bigserial"));
        assertFalse(lower.contains("generated always as identity"));
        assertFalse(lower.contains("create sequence"));
        assertTrue(lower.contains("insert into audit_operations.ao_audit_ledger_head"));
        assertTrue(lower.contains("repeat('0', 64)"));
        assertTrue(lower.contains("unique (audit_id)"));
        assertTrue(lower.contains("unique (source_event_id)"));
        assertTrue(lower.contains("revoke all privileges on audit_operations.ao_audit_ledger"));
        assertFalse(lower.contains("grant update on audit_operations.ao_audit_ledger"));
        assertFalse(lower.contains("grant delete on audit_operations.ao_audit_ledger"));
        assertFalse(lower.contains("grant truncate on audit_operations.ao_audit_ledger"));
        assertFalse(lower.contains("grant select, insert on audit_operations.ao_finding_disposition\n"
                + "    to scholarsense_audit_verifier"));
        assertFalse(lower.contains("identity_access."), "audit owner must not access the producer schema");
    }

    @Test
    void story14ForwardExtensionKeepsIndefiniteIdentityRelayAttemptsFenced() throws Exception {
        String migration = Files.readString(MIGRATIONS.resolve(
                "identity-access/V000004__identity-access__audit_delivery_attempts_bigint.sql"));
        String lower = migration.toLowerCase();

        assertTrue(lower.contains("alter table identity_access.ia_local_audit_outbox"));
        assertTrue(lower.contains("alter column attempts type bigint"));
        assertFalse(lower.contains("audit_operations."));
    }

    @Test
    void story15MigrationCreatesRebuildableSearchRetentionStateAndKeepsLedgerImmutable()
            throws Exception {
        String migration = Files.readString(MIGRATIONS.resolve(
                "audit-operations/V000005__audit-operations__authorized_search_retention_v1.sql"));
        String lower = migration.toLowerCase();

        for (String table : Set.of(
                "ao_audit_search_projection", "ao_local_audit_fact", "ao_local_audit_outbox",
                "ao_audit_search_csrf_proof",
                "ao_archive_manifest", "ao_legal_hold", "ao_retention_execution",
                "ao_retention_execution_step")) {
            assertTrue(lower.contains("audit_operations." + table), table);
        }
        assertTrue(lower.contains("create role scholarsense_audit_search nologin"));
        assertTrue(lower.contains("create role scholarsense_audit_retention_executor nologin"));
        assertTrue(lower.contains("grant select on audit_operations.ao_audit_search_projection"));
        assertTrue(lower.contains(
                "primary key (browser_session_digest, proof_digest)"));
        assertFalse(lower.contains("grant update on audit_operations.ao_audit_ledger"));
        assertFalse(lower.contains("grant delete on audit_operations.ao_audit_ledger"));
        assertFalse(lower.contains("grant truncate on audit_operations.ao_audit_ledger"));
        assertFalse(lower.contains("delete from audit_operations.ao_audit_ledger"));
        assertFalse(lower.contains("identity_access."));
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
        assertRejected("privilege-cross-owner", "PRIVILEGE_OBJECT_CROSS_OWNER");
        assertRejected("privilege-cross-owner", "PRIVILEGE_GRANTEE_CROSS_OWNER");
    }

    @Test
    void validTableAliasQualifiedColumnsAreAccepted() throws Exception {
        Path root = Path.of("src/test/resources/migration/fixtures/alias-column-valid");
        MigrationRules.Result result = MigrationRules.validate(
                root.resolve("module-ownership.csv"), root.resolve("migration"));
        assertTrue(result.violations().isEmpty(), () -> String.join("\n", result.violations()));
    }

    @Test
    void privilegeInspectionCoversEveryListedObjectAndAllTablesInSchema() throws Exception {
        Path root = Path.of("src/test/resources/migration/fixtures/privilege-cross-owner");
        MigrationRules.Result result = MigrationRules.validate(
                root.resolve("module-ownership.csv"), root.resolve("migration"));

        assertTrue(result.violations().stream().anyMatch(value -> value.endsWith("reporting.rp_report")),
                () -> String.join("\n", result.violations()));
        assertTrue(result.violations().stream().anyMatch(value -> value.endsWith("-> reporting")),
                () -> String.join("\n", result.violations()));
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

    private static String sha256(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
