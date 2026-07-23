package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuditOwnershipBoundaryTest {

    @Test
    void auditOperationsOwnsLedgerWithoutReadingIdentitySchemaAndIdentityUsesOnlyPublicAuditApi()
            throws IOException {
        Path root = Path.of("src/main/java/cn/edu/suda/scholarsense");
        String auditSources = sources(root.resolve("auditoperations"));
        String identitySources = sources(root.resolve("identityaccess"));

        assertFalse(auditSources.contains("identity_access."));
        assertTrue(auditSources.contains("audit_operations.ao_audit_ledger"));
        assertFalse(identitySources.contains("audit_operations.ao_audit_ledger"));
        assertFalse(identitySources.contains("auditoperations.application"));
        assertFalse(identitySources.contains("auditoperations.domain"));
    }

    @Test
    void story15CannotDeleteProductionLedgerOrIssueCrossDomainDeletionReceipts() throws IOException {
        Path root = Path.of("src/main/java/cn/edu/suda/scholarsense");
        String auditSources = sources(root.resolve("auditoperations"));
        String runtimeSources = sources(root.resolve("runtime"));

        assertFalse(auditSources.toLowerCase(java.util.Locale.ROOT)
                .contains("delete from audit_operations.ao_audit_ledger"));
        assertFalse(auditSources.contains("DeletionReceiptIssuer"));
        assertFalse(auditSources.contains("class DeletionReceipt"));
        assertFalse(runtimeSources.contains("DeletionReceiptIssuer"));
        assertTrue(auditSources.contains("SyntheticFixtureDestroyPort"));
        assertTrue(auditSources.contains("nonProductionEvidence"));
    }

    private static String sources(Path root) throws IOException {
        StringBuilder result = new StringBuilder();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                result.append(Files.readString(file));
            }
        }
        return result.toString();
    }
}
