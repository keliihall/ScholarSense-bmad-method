package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.sql.ResultSet;
import cn.edu.suda.scholarsense.identityaccess.application.RejectedLocalAudit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class JdbcIdentityAuditRelayRepositoryTest {

    @Test
    void claimUsesDueOrderingSkipLockedLeaseAndIncrementsAttemptsBeforeReturning() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenReturn(List.of());
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        JdbcIdentityAuditRelayRepository repository = new JdbcIdentityAuditRelayRepository(
                jdbc, transactions, new ObjectMapper());

        repository.claimDue(100, Instant.parse("2026-07-20T02:00:00Z"), Duration.ofSeconds(60));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        String statement = sql.getValue().toLowerCase();
        assertTrue(statement.contains("for update skip locked"));
        assertTrue(statement.contains("order by o.created_at, o.event_id"));
        assertTrue(statement.contains("o.claimed_at is null or o.claimed_at < ?"));
        assertTrue(statement.contains("limit ?"));
    }

    @Test
    void invalidEnvelopeIsClaimedAsAQuarantineCandidateWithoutRollingBackTheBatch() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        UUID eventId = UUID.fromString("019bf18e-6c00-7000-8000-000000000002");
        UUID auditId = UUID.fromString("019bf18e-6c00-7000-8000-000000000001");
        Instant createdAt = Instant.parse("2026-07-20T02:00:00Z");
        when(row.getObject("event_id", UUID.class)).thenReturn(eventId);
        when(row.getObject("audit_id", UUID.class)).thenReturn(auditId);
        when(row.getString("event_type"))
                .thenReturn("identity-access.local-audit-fact.recorded.v1");
        when(row.getString("schema_version")).thenReturn("LOCAL-AUDIT-OUTBOX-1.0.0");
        when(row.getTimestamp("created_at")).thenReturn(java.sql.Timestamp.from(createdAt));
        when(row.getString("envelope")).thenReturn("{}");
        when(row.getLong("attempts")).thenReturn(0L);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        JdbcIdentityAuditRelayRepository repository = new JdbcIdentityAuditRelayRepository(
                jdbc, transactions, new ObjectMapper());

        var claims = repository.claimDue(100, createdAt.plusSeconds(1), Duration.ofSeconds(60));

        assertEquals(1, claims.size());
        assertTrue(claims.getFirst() instanceof RejectedLocalAudit);
        assertEquals(eventId, claims.getFirst().eventId());
        assertEquals(1L, claims.getFirst().attempts());
    }

    @Test
    void claimFencingContinuesBeyondTheFormerIntegerAttemptsLimit() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        UUID eventId = UUID.fromString("019bf18e-6c00-7000-8000-000000000002");
        UUID auditId = UUID.fromString("019bf18e-6c00-7000-8000-000000000001");
        Instant createdAt = Instant.parse("2026-07-20T02:00:00Z");
        when(row.getObject("event_id", UUID.class)).thenReturn(eventId);
        when(row.getObject("audit_id", UUID.class)).thenReturn(auditId);
        when(row.getString("event_type"))
                .thenReturn("identity-access.local-audit-fact.recorded.v1");
        when(row.getString("schema_version")).thenReturn("LOCAL-AUDIT-OUTBOX-1.0.0");
        when(row.getTimestamp("created_at")).thenReturn(java.sql.Timestamp.from(createdAt));
        when(row.getString("envelope")).thenReturn("{}");
        when(row.getLong("attempts")).thenReturn((long) Integer.MAX_VALUE);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        JdbcIdentityAuditRelayRepository repository = new JdbcIdentityAuditRelayRepository(
                jdbc, transactions, new ObjectMapper());

        var claims = repository.claimDue(100, createdAt.plusSeconds(1), Duration.ofSeconds(60));

        assertEquals(2_147_483_648L, claims.getFirst().attempts());
    }
}
