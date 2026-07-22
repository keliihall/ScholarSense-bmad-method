package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.api.AuditContractRejection;
import cn.edu.suda.scholarsense.identityaccess.application.AuditRelayClaim;
import cn.edu.suda.scholarsense.identityaccess.application.ClaimedLocalAudit;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditRelayWorkRepository;
import cn.edu.suda.scholarsense.identityaccess.application.RejectedLocalAudit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HexFormat;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Producer-owned claim and fencing adapter. It never reads or writes the audit_operations schema. */
public final class JdbcIdentityAuditRelayRepository implements IdentityAuditRelayWorkRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IdentityAuditOutboxJsonCodec codec;

    public JdbcIdentityAuditRelayRepository(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.codec = new IdentityAuditOutboxJsonCodec(json);
    }

    @Override
    public List<AuditRelayClaim> claimDue(int batchSize, Instant now, Duration lease) {
        if (batchSize != 100 || !Duration.ofSeconds(60).equals(lease)) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_RELAY_POLICY_INVALID");
        }
        List<AuditRelayClaim> claimed = transactions.execute(
                status -> claimInTransaction(batchSize, now, lease));
        return claimed == null ? List.of() : claimed;
    }

    private List<AuditRelayClaim> claimInTransaction(int batchSize, Instant now, Duration lease) {
        List<ClaimCandidate> candidates = jdbc.query("""
                select o.event_id, o.audit_id, o.event_type, o.schema_version,
                       o.created_at, o.attempts, o.envelope
                from identity_access.ia_local_audit_outbox o
                where o.delivery_status in ('pending', 'retrying')
                  and o.next_attempt_at <= ?
                  and (o.claimed_at is null or o.claimed_at < ?)
                order by o.created_at, o.event_id
                for update skip locked
                limit ?
                """,
                this::candidate,
                Timestamp.from(now), Timestamp.from(now.minus(lease)), batchSize);
        List<AuditRelayClaim> results = new ArrayList<>();
        for (ClaimCandidate candidate : candidates) {
            long attempts = Math.addExact(candidate.attempts(), 1L);
            int updated = jdbc.update("""
                    update identity_access.ia_local_audit_outbox
                    set delivery_status='retrying', attempts=?, claimed_at=?, last_error_code=null
                    where event_id=? and attempts=?
                    """,
                    attempts, Timestamp.from(now), candidate.eventId(), candidate.attempts());
            if (updated != 1) {
                throw new ConcurrencyFailureException("IDENTITY_AUDIT_CLAIM_FENCED");
            }
            try {
                results.add(new ClaimedLocalAudit(codec.read(
                        candidate.eventId(), candidate.auditId(), candidate.eventType(),
                        candidate.schemaVersion(), candidate.createdAt(), candidate.envelope()), attempts));
            } catch (IllegalArgumentException invalidEnvelope) {
                String digest = sha256(candidate.eventId() + "\0" + candidate.auditId()
                        + "\0" + candidate.envelope());
                results.add(new RejectedLocalAudit(
                        candidate.eventId(), attempts,
                        new AuditContractRejection(
                                "identity-access", digest, digest.substring(0, 32),
                                candidate.createdAt())));
            }
        }
        return List.copyOf(results);
    }

    @Override
    public boolean confirm(UUID eventId, long expectedAttempts, Instant confirmedAt) {
        return transactions.execute(status -> jdbc.update("""
                update identity_access.ia_local_audit_outbox
                set delivery_status='confirmed', claimed_at=null, last_error_code=null,
                    next_attempt_at=?
                where event_id=? and attempts=? and delivery_status='retrying'
                """, Timestamp.from(confirmedAt), eventId, expectedAttempts)) == 1;
    }

    @Override
    public boolean retry(
            UUID eventId, long expectedAttempts, Instant nextAttemptAt, String errorCode) {
        requireErrorCode(errorCode);
        return transactions.execute(status -> jdbc.update("""
                update identity_access.ia_local_audit_outbox
                set delivery_status='retrying', claimed_at=null, next_attempt_at=?, last_error_code=?
                where event_id=? and attempts=? and delivery_status='retrying'
                """, Timestamp.from(nextAttemptAt), errorCode, eventId, expectedAttempts)) == 1;
    }

    @Override
    public boolean fail(UUID eventId, long expectedAttempts, String errorCode, Instant failedAt) {
        requireErrorCode(errorCode);
        return transactions.execute(status -> jdbc.update("""
                update identity_access.ia_local_audit_outbox
                set delivery_status='failed', claimed_at=null, next_attempt_at=?, last_error_code=?
                where event_id=? and attempts=? and delivery_status='retrying'
                """, Timestamp.from(failedAt), errorCode, eventId, expectedAttempts)) == 1;
    }

    private ClaimCandidate candidate(ResultSet result, int ignored) throws SQLException {
        UUID eventId = result.getObject("event_id", UUID.class);
        UUID auditId = result.getObject("audit_id", UUID.class);
        String eventType = result.getString("event_type");
        String schemaVersion = result.getString("schema_version");
        Instant createdAt = result.getTimestamp("created_at").toInstant();
        return new ClaimCandidate(
                eventId, auditId, eventType, schemaVersion, createdAt,
                result.getString("envelope"), result.getLong("attempts"));
    }

    private static void requireErrorCode(String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_RELAY_ERROR_CODE_INVALID");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ClaimCandidate(
            UUID eventId,
            UUID auditId,
            String eventType,
            String schemaVersion,
            Instant createdAt,
            String envelope,
            long attempts) {}
}
