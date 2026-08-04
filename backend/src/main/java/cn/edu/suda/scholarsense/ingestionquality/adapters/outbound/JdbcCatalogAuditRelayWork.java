package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayClaim;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayWorkPort;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Claims only ingestion-quality-owned rows; it never queries the audit_operations schema. */
public final class JdbcCatalogAuditRelayWork implements CatalogAuditRelayWorkPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcCatalogAuditRelayWork(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.transactions = java.util.Objects.requireNonNull(transactions);
        this.json = java.util.Objects.requireNonNull(json);
    }

    @Override
    public List<CatalogAuditRelayClaim> claimDue(int batchSize, Instant now, Duration lease) {
        if (batchSize != 100 || !Duration.ofSeconds(60).equals(lease)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_AUDIT_RELAY_POLICY_INVALID");
        }
        List<CatalogAuditRelayClaim> result = transactions.execute(status -> jdbc.query("""
                select event_id,payload::text,attempts
                  from ingestion_quality.iq_local_audit_outbox
                 where status in ('pending','retrying') and available_at<=?
                   and (claimed_until is null or claimed_until<?)
                 order by created_at,event_id for update skip locked limit ?
                """, (row, ignored) -> claim(row.getObject("event_id", UUID.class),
                        row.getString("payload"), row.getLong("attempts"), now, lease),
                Timestamp.from(now), Timestamp.from(now), batchSize));
        return result == null ? List.of() : List.copyOf(result);
    }

    private CatalogAuditRelayClaim claim(
            UUID eventId, String payload, long priorAttempts, Instant now, Duration lease) {
        long attempts = Math.addExact(priorAttempts, 1);
        int changed = jdbc.update("""
                update ingestion_quality.iq_local_audit_outbox
                   set status='retrying',attempts=?,claimed_until=?,last_error_code=null
                 where event_id=? and attempts=?
                """, attempts, Timestamp.from(now.plus(lease)), eventId, priorAttempts);
        if (changed != 1) throw new IllegalStateException("INGESTION_QUALITY_AUDIT_CLAIM_FENCED");
        try {
            return new CatalogAuditRelayClaim(json.readValue(payload, LocalAuditOutboxRecord.class), attempts);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalArgumentException("INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID", invalid);
        }
    }

    @Override public boolean confirm(UUID id, long attempts, Instant at) {
        return mutate("status='delivered',delivered_at=?,claimed_until=null,last_error_code=null",
                id, attempts, Timestamp.from(at), null);
    }

    @Override public boolean retry(UUID id, long attempts, Instant at, String code) {
        requireCode(code);
        return mutate("status='retrying',available_at=?,claimed_until=null,last_error_code=?",
                id, attempts, Timestamp.from(at), code);
    }

    @Override public boolean fail(UUID id, long attempts, Instant at, String code) {
        requireCode(code);
        return mutate("status='failed',available_at=?,claimed_until=null,last_error_code=?",
                id, attempts, Timestamp.from(at), code);
    }

    private boolean mutate(String assignment, UUID id, long attempts, Timestamp at, String code) {
        Integer result = transactions.execute(status -> code == null
                ? jdbc.update("update ingestion_quality.iq_local_audit_outbox set " + assignment
                        + " where event_id=? and attempts=? and status='retrying'", at, id, attempts)
                : jdbc.update("update ingestion_quality.iq_local_audit_outbox set " + assignment
                        + " where event_id=? and attempts=? and status='retrying'", at, code, id, attempts));
        return result != null && result == 1;
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("AUDIT_[A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_AUDIT_ERROR_CODE_INVALID");
        }
    }
}
