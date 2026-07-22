package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditAlertWorkRepository;
import cn.edu.suda.scholarsense.auditoperations.application.ClaimedAuditAlert;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims and fences alert delivery metadata without changing immutable findings or ledger rows. */
public final class JdbcAuditAlertWorkRepository implements AuditAlertWorkRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcAuditAlertWorkRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<ClaimedAuditAlert> claimDue(int batchSize, Instant now, Duration lease) {
        if (batchSize != 100 || !Duration.ofSeconds(60).equals(lease)) {
            throw new IllegalArgumentException("AUDIT_ALERT_RELAY_POLICY_INVALID");
        }
        List<ClaimedAuditAlert> result = transactions.execute(status -> claim(now, lease, batchSize));
        return result == null ? List.of() : result;
    }

    private List<ClaimedAuditAlert> claim(Instant now, Duration lease, int batchSize) {
        List<Candidate> candidates = jdbc.query("""
                select alert_id, attempts, safe_payload::text
                from audit_operations.ao_alert_outbox
                where delivery_status in ('pending', 'retrying')
                  and next_attempt_at <= ?
                  and (claimed_at is null or claimed_at < ?)
                order by created_at, alert_id
                for update skip locked
                limit ?
                """,
                (result, ignored) -> new Candidate(
                        result.getObject("alert_id", UUID.class),
                        result.getInt("attempts"),
                        result.getString("safe_payload")),
                Timestamp.from(now), Timestamp.from(now.minus(lease)), batchSize);
        List<ClaimedAuditAlert> claimed = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int attempts = Math.addExact(candidate.attempts(), 1);
            int changed = jdbc.update("""
                    update audit_operations.ao_alert_outbox
                    set delivery_status='retrying', attempts=?, claimed_at=?, last_error_code=null
                    where alert_id=? and attempts=?
                    """, attempts, Timestamp.from(now), candidate.alertId(), candidate.attempts());
            if (changed != 1) {
                throw new ConcurrencyFailureException("AUDIT_ALERT_CLAIM_FENCED");
            }
            claimed.add(new ClaimedAuditAlert(candidate.alertId(), attempts, candidate.safePayload()));
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean confirm(UUID alertId, int expectedAttempts, Instant confirmedAt) {
        return transactions.execute(status -> jdbc.update("""
                update audit_operations.ao_alert_outbox
                set delivery_status='confirmed', claimed_at=null, next_attempt_at=?, last_error_code=null
                where alert_id=? and attempts=? and delivery_status='retrying'
                """, Timestamp.from(confirmedAt), alertId, expectedAttempts)) == 1;
    }

    @Override
    public boolean retry(UUID alertId, int expectedAttempts, Instant retryAt, String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("AUDIT_ALERT_ERROR_CODE_INVALID");
        }
        return transactions.execute(status -> jdbc.update("""
                update audit_operations.ao_alert_outbox
                set delivery_status='retrying', claimed_at=null, next_attempt_at=?, last_error_code=?
                where alert_id=? and attempts=? and delivery_status='retrying'
                """, Timestamp.from(retryAt), errorCode, alertId, expectedAttempts)) == 1;
    }

    private record Candidate(UUID alertId, int attempts, String safePayload) {}
}
