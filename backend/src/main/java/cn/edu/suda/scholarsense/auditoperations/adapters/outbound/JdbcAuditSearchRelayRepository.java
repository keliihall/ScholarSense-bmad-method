package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchRelayClaim;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchRelayWorkPort;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Audit-operations-owned claim/lease/fencing state for search read-audit delivery. */
public final class JdbcAuditSearchRelayRepository implements AuditSearchRelayWorkPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AuditLedgerJsonCodec factCodec;

    public JdbcAuditSearchRelayRepository(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.factCodec = new AuditLedgerJsonCodec(json);
    }

    @Override
    public List<AuditSearchRelayClaim> claimDue(int batchSize, Instant now, Duration lease) {
        if (batchSize != 100 || !Duration.ofSeconds(60).equals(lease)) {
            throw new IllegalArgumentException("AUDIT_SEARCH_RELAY_POLICY_INVALID");
        }
        List<AuditSearchRelayClaim> result = transactions.execute(
                status -> claimInTransaction(batchSize, now, lease));
        return result == null ? List.of() : result;
    }

    private List<AuditSearchRelayClaim> claimInTransaction(int batchSize, Instant now, Duration lease) {
        List<Candidate> candidates = jdbc.query("""
                select event_id, audit_id, event_type, schema_version, envelope, attempts, created_at
                from audit_operations.ao_local_audit_outbox
                where delivery_status in ('pending', 'retrying')
                  and next_attempt_at<=?
                  and (claimed_at is null or claimed_at<?)
                order by created_at, event_id
                for update skip locked
                limit ?
                """, this::candidate, Timestamp.from(now), Timestamp.from(now.minus(lease)), batchSize);
        List<AuditSearchRelayClaim> claims = new ArrayList<>();
        for (Candidate candidate : candidates) {
            long attempts = Math.addExact(candidate.attempts(), 1);
            int updated = jdbc.update("""
                    update audit_operations.ao_local_audit_outbox
                    set delivery_status='retrying', attempts=?, claimed_at=?, last_error_code=null
                    where event_id=? and attempts=?
                    """, attempts, Timestamp.from(now), candidate.eventId(), candidate.attempts());
            if (updated != 1) throw new ConcurrencyFailureException("AUDIT_SEARCH_RELAY_CLAIM_FENCED");
            claims.add(new AuditSearchRelayClaim(decode(candidate), attempts));
        }
        return List.copyOf(claims);
    }

    @Override
    public boolean confirm(UUID eventId, long attempts, Instant at) {
        return transition(eventId, attempts, at, null, "confirmed");
    }

    @Override
    public boolean retry(UUID eventId, long attempts, Instant at, String errorCode) {
        requireCode(errorCode);
        return transition(eventId, attempts, at, errorCode, "retrying");
    }

    @Override
    public boolean fail(UUID eventId, long attempts, Instant at, String errorCode) {
        requireCode(errorCode);
        return transition(eventId, attempts, at, errorCode, "failed");
    }

    private boolean transition(UUID eventId, long attempts, Instant at, String code, String state) {
        return transactions.execute(status -> jdbc.update("""
                update audit_operations.ao_local_audit_outbox
                set delivery_status=?, claimed_at=null, next_attempt_at=?,
                    confirmed_at=case when ?='confirmed' then ? else confirmed_at end,
                    last_error_code=?
                where event_id=? and attempts=? and delivery_status='retrying'
                """, state, Timestamp.from(at), state, Timestamp.from(at), code, eventId, attempts)) == 1;
    }

    private Candidate candidate(ResultSet result, int ignored) throws SQLException {
        return new Candidate(
                result.getObject("event_id", UUID.class),
                result.getObject("audit_id", UUID.class),
                result.getString("event_type"),
                result.getString("schema_version"),
                result.getString("envelope"),
                result.getLong("attempts"),
                result.getTimestamp("created_at").toInstant());
    }

    private LocalAuditOutboxRecord decode(Candidate candidate) {
        try {
            var envelope = json.readTree(candidate.envelope());
            if (!candidate.eventId().toString().equals(envelope.required("id").asText())
                    || !candidate.eventType().equals(envelope.required("type").asText())
                    || !("audit/" + candidate.auditId()).equals(envelope.required("subject").asText())) {
                throw new IllegalArgumentException("AUDIT_SEARCH_RELAY_ENVELOPE_MISMATCH");
            }
            var fact = factCodec.readFact(json.writeValueAsString(envelope.required("data")));
            return new LocalAuditOutboxRecord(
                    candidate.eventId(), candidate.auditId(), candidate.eventType(),
                    candidate.schemaVersion(), "audit-operations", candidate.createdAt(), fact);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("AUDIT_SEARCH_RELAY_ENVELOPE_INVALID", failure);
        }
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_RELAY_ERROR_CODE_INVALID");
        }
    }

    private record Candidate(
            UUID eventId, UUID auditId, String eventType, String schemaVersion,
            String envelope, long attempts, Instant createdAt) {}
}
