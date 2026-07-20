package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAuditStorageConformanceTest {
    @TempDir
    Path temporary;

    @Test
    void templateConformsForEveryRegisteredOwnerWithoutCreatingEightEmptyProductionTables() throws Exception {
        Path registry = Path.of("src/main/resources/db/module-ownership.csv");
        String template = Files.readString(
                Path.of("src/test/resources/audit/fixtures/module-local-audit-storage.sql.template"));
        for (String required : List.of(
                "actor_search_token", "authorization_context", "object_search_token",
                "time_source_profile", "tokenization_profile_version", "aggregate_id_search_token",
                "idempotency_key_digest", "policy_versions", "retention_schedule_version",
                "producer_module = '${module}'", "actor_type in", "aggregate_version >= 1",
                "authorization_context -> 'policyversion' = 'null'::jsonb",
                "substring(audit_id::text", "attempts integer", "next_attempt_at",
                "last_error_code", "claimed_at", "local-audit-outbox-1.0.0",
                "jsonb_typeof(envelope)", "substring(event_id::text", "grant usage on schema",
                "grant select, insert", "revoke all privileges")) {
            assertTrue(template.toLowerCase().contains(required), () -> "template missing " + required);
        }
        List<String> registryLines = Files.readAllLines(registry);
        Path generatedRegistry = temporary.resolve("module-ownership.csv");
        Files.copy(registry, generatedRegistry);
        Path migrations = temporary.resolve("migration");
        List<String> generatedTables = new ArrayList<>();
        int version = 101;
        for (String line : registryLines.subList(1, registryLines.size())) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(",", -1);
            String module = fields[0];
            String schema = fields[1];
            String prefix = fields[2];
            String sql = template
                    .replace("${module}", module)
                    .replace("${schema}", schema)
                    .replace("${prefix}", prefix);
            Path ownerDirectory = migrations.resolve(module);
            Files.createDirectories(ownerDirectory);
            Path migration = ownerDirectory.resolve(
                    "V%06d__%s__local_audit_conformance.sql".formatted(version++, module));
            Files.writeString(migration, sql);
            generatedTables.add(schema + "." + prefix + "local_audit_fact");
            assertFalse(sql.contains("${"));
        }

        MigrationRules.Result result = MigrationRules.validate(generatedRegistry, migrations);

        assertTrue(result.violations().isEmpty(), () -> String.join("\n", result.violations()));
        assertTrue(generatedTables.contains("identity_access.ia_local_audit_fact"));
        assertTrue(generatedTables.contains("audit_operations.ao_local_audit_fact"));
        assertFalse(Files.readString(registry).contains("LocalAuditFact"),
                "module-local technical facts must not become AD-2 domain fact owners");
        try (var walk = Files.walk(Path.of("src/main/resources/db/migration"))) {
            assertTrue(walk.filter(path -> path.toString().endsWith(".sql"))
                    .allMatch(path -> path.toString().contains("identity-access")));
        }
    }
}
