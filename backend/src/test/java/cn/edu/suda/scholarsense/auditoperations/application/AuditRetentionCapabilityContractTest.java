package cn.edu.suda.scholarsense.auditoperations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AuditRetentionCapabilityContractTest {
    @Test
    void deliveredCapabilityIsConformanceOnlyAndCannotIssueProductionDeletionEvidence() throws Exception {
        Properties capability = new Properties();
        try (InputStream input = getClass().getResourceAsStream(
                "/audit-runtime/audit-retention-capability-1-0-0.properties")) {
            capability.load(input);
        }

        assertEquals("true", capability.getProperty("conformanceVerified"));
        assertEquals("false", capability.getProperty("productionAuthorizationEnabled"));
        assertEquals("false", capability.getProperty("productionArchiveEnabled"));
        assertEquals("unbound", capability.getProperty("archiveAdapter"));
        assertEquals("false", capability.getProperty("deletionReceiptRuntimeIssuable"));
        assertEquals("audit-domain", capability.getProperty("evidenceScope"));
        assertEquals("true", capability.getProperty("nonProductionEvidence"));
    }
}
