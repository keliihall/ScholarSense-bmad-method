package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.outbox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.auditoperations.application.CanonicalAuditHasher;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class JdbcAuditLedgerRepositoryTest {

    @Test
    void headAllocationUsesTheSingletonRowLockAndCompareAndSetUpdate() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<LedgerHead>>any()))
                .thenReturn(LedgerHead.genesis());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcAuditLedgerRepository repository = new JdbcAuditLedgerRepository(jdbc, new ObjectMapper());

        assertEquals(LedgerHead.genesis(), repository.lockHead());
        repository.updateHead(LedgerHead.genesis(), new LedgerHead(1, new LedgerHash("a".repeat(64))));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(query.capture(), org.mockito.ArgumentMatchers.<RowMapper<LedgerHead>>any());
        assertTrue(query.getValue().toLowerCase().contains("for update"));
        ArgumentCaptor<String> update = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(update.capture(), any(Object[].class));
        assertTrue(update.getValue().contains("where singleton_id=1 and ledger_sequence=? and entry_hash=?"));
    }

    @Test
    void ledgerInsertWritesEveryFrozenHashMaterialFieldWithoutUpdatingTheLedger() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcAuditLedgerRepository repository = new JdbcAuditLedgerRepository(jdbc, new ObjectMapper());
        CanonicalAuditHasher hasher = new CanonicalAuditHasher();
        LedgerHash payload = hasher.payloadFingerprint(outbox().fact());
        LedgerHash entryHash = hasher.entryHash(1, LedgerHash.genesis(), outbox(), NOW.plusSeconds(1), payload);
        LedgerEntry entry = new LedgerEntry(
                1, LedgerHash.genesis(), entryHash, "AUDIT-LEDGER-HASH-1.0.0",
                outbox().auditId(), outbox().eventId(), outbox().producer(), outbox().eventType(),
                outbox().schemaVersion(), outbox().fact().schemaVersion(), outbox().createdAt(),
                NOW.plusSeconds(1), outbox().fact().traceId(), outbox().fact().aggregateVersion(),
                payload, outbox().fact());

        repository.insert(entry);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        String statement = sql.getValue().toLowerCase();
        assertTrue(statement.startsWith("insert into audit_operations.ao_audit_ledger"));
        assertTrue(statement.contains("payload_fingerprint"));
        assertTrue(statement.contains("aggregate_version"));
        assertTrue(statement.contains("retention_schedule_version"));
    }
}
