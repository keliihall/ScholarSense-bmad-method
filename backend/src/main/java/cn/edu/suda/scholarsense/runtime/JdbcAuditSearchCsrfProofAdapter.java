package cn.edu.suda.scholarsense.runtime;

import cn.edu.suda.scholarsense.auditoperations.application.AuditClock;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchCsrfProofPort;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Database uniqueness makes CSRF-proof consumption atomic across application nodes. */
public final class JdbcAuditSearchCsrfProofAdapter implements AuditSearchCsrfProofPort {
    private static final Duration ABSOLUTE_SESSION_WINDOW = Duration.ofHours(8);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AuditClock clock;

    public JdbcAuditSearchCsrfProofAdapter(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            AuditClock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean consume(String browserSessionDigest, String proofDigest) {
        requireDigest(browserSessionDigest);
        requireDigest(proofDigest);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var now = clock.now();
            jdbc.update("""
                    delete from audit_operations.ao_audit_search_csrf_proof
                    where expires_at <= ?
                    """, Timestamp.from(now));
            return jdbc.update("""
                    insert into audit_operations.ao_audit_search_csrf_proof (
                      browser_session_digest, proof_digest, consumed_at, expires_at)
                    values (?, ?, ?, ?)
                    on conflict (browser_session_digest, proof_digest) do nothing
                    """,
                    browserSessionDigest,
                    proofDigest,
                    Timestamp.from(now),
                    Timestamp.from(now.plus(ABSOLUTE_SESSION_WINDOW))) == 1;
        }));
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_CSRF_DIGEST_INVALID");
        }
    }
}
