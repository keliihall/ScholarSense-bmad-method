package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.EVENT_ID;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.TRACE_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingSeverity;
import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class JdbcAuditEvidenceRepositoryTest {

    @Test
    void findingAndAlertPersistOnlyThePrivacySafeTypedProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcAuditEvidenceRepository repository = new JdbcAuditEvidenceRepository(
                jdbc,
                new ObjectMapper(),
                ignored -> UUID.fromString("019bf18e-6c00-7000-8000-000000000011"));
        IntegrityFinding finding = new IntegrityFinding(
                EVENT_ID,
                FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH,
                FindingSeverity.CRITICAL,
                "AUDIT-INGESTION-POLICY-1.0.0",
                "AUDIT-LEDGER-HASH-1.0.0",
                41L,
                41L,
                "identity-access:0c97b5e3a80f1ec2",
                new LedgerHash("a".repeat(64)),
                TRACE_ID,
                NOW,
                NOW.plusSeconds(15),
                "runbook://audit/ledger-entry-hash-mismatch");

        repository.save(finding);
        repository.enqueueActive(finding);

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> statementArguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(statements.capture(), statementArguments.capture());
        String sql = String.join("\n", statements.getAllValues()).toLowerCase();
        assertTrue(sql.contains("ao_integrity_finding"));
        assertTrue(sql.contains("ao_alert_outbox"));
        assertTrue(sql.contains("safe_payload"));
        assertTrue(sql.contains("not exists"));
        assertTrue(sql.contains("deduplication_key"));
        assertFalse(sql.contains("student_id"));
        assertFalse(sql.contains("raw_payload"));
        assertFalse(sql.contains("actor_id"));
        String safePayload = (String) statementArguments.getAllValues().getLast()[4];
        assertTrue(safePayload.contains("\"schemaVersion\":\"AUDIT-INTEGRITY-ALERT-1.0.0\""));
        assertTrue(safePayload.contains("\"alertId\":"));
        assertTrue(safePayload.contains("\"findingId\":\"" + EVENT_ID + "\""));
        assertTrue(safePayload.contains("\"event\":\"active\""));
        ArgumentCaptor<String> lockSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(lockSql.capture());
        assertTrue(lockSql.getValue().toLowerCase().contains("pg_advisory_xact_lock"));
        Object[] alertArguments = statementArguments.getAllValues().getLast();
        assertEquals(Timestamp.from(NOW.plusSeconds(15).minus(Duration.ofSeconds(300))),
                alertArguments[9]);
        assertEquals(Timestamp.from(NOW.plusSeconds(15).plus(Duration.ofSeconds(300))),
                alertArguments[10]);
    }
}
