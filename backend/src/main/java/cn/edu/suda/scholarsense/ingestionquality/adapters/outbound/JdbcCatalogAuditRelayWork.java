package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayClaim;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayWorkPort;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
        List<CatalogAuditRelayClaim> result = transactions.execute(
                status -> claimInTransaction(batchSize, now, lease));
        return result == null ? List.of() : List.copyOf(result);
    }

    private List<CatalogAuditRelayClaim> claimInTransaction(
            int batchSize, Instant now, Duration lease) {
        List<ClaimCandidate> candidates = jdbc.query("""
                select event_id,audit_id,event_type,schema_version,producer,
                       payload::text payload,payload_digest,created_at,attempts
                  from ingestion_quality.iq_local_audit_outbox
                 where status in ('pending','retrying') and available_at<=?
                   and (claimed_until is null or claimed_until<?)
                 order by created_at,event_id for update skip locked limit ?
                """, JdbcCatalogAuditRelayWork::candidate,
                Timestamp.from(now), Timestamp.from(now), batchSize);
        List<CatalogAuditRelayClaim> claims = new ArrayList<>();
        for (ClaimCandidate candidate : candidates) {
            long attempts = Math.addExact(candidate.priorAttempts(), 1);
            LocalAuditOutboxRecord record = verified(candidate);
            if (record == null) {
                failIntegrity(candidate, attempts, now);
                continue;
            }
            claim(candidate, attempts, now, lease);
            claims.add(new CatalogAuditRelayClaim(record, attempts));
        }
        return List.copyOf(claims);
    }

    private void claim(
            ClaimCandidate candidate, long attempts, Instant now, Duration lease) {
        int changed = jdbc.update("""
                update ingestion_quality.iq_local_audit_outbox
                   set status='retrying',attempts=?,claimed_until=?,last_error_code=null
                 where event_id=? and attempts=? and status in ('pending','retrying')
                """, attempts, Timestamp.from(now.plus(lease)),
                candidate.eventId(), candidate.priorAttempts());
        if (changed != 1) throw new IllegalStateException("INGESTION_QUALITY_AUDIT_CLAIM_FENCED");
    }

    private void failIntegrity(ClaimCandidate candidate, long attempts, Instant now) {
        int changed = jdbc.update("""
                update ingestion_quality.iq_local_audit_outbox
                   set status='failed',attempts=?,available_at=?,claimed_until=null,
                       delivered_at=null,last_error_code='AUDIT_PAYLOAD_INTEGRITY_INVALID'
                 where event_id=? and attempts=? and status in ('pending','retrying')
                """, attempts, Timestamp.from(now), candidate.eventId(), candidate.priorAttempts());
        if (changed != 1) throw new IllegalStateException("INGESTION_QUALITY_AUDIT_CLAIM_FENCED");
    }

    private LocalAuditOutboxRecord verified(ClaimCandidate candidate) {
        try {
            var payload = json.readTree(candidate.payload());
            String canonicalDigest = CanonicalCatalogJson.digest(json, payload)
                    .substring("sha256:".length());
            if (!sameDigest(candidate.payloadDigest(), canonicalDigest)) {
                return null;
            }
            LocalAuditOutboxRecord record =
                    json.readValue(candidate.payload(), LocalAuditOutboxRecord.class);
            if (!record.eventId().equals(candidate.eventId())
                    || !record.auditId().equals(candidate.auditId())
                    || !record.eventType().equals(candidate.eventType())
                    || !record.schemaVersion().equals(candidate.schemaVersion())
                    || !record.producer().equals(candidate.producer())
                    || !record.createdAt().truncatedTo(ChronoUnit.MICROS).equals(
                            candidate.createdAt().truncatedTo(ChronoUnit.MICROS))) {
                return null;
            }
            return record;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean sameDigest(String stored, String actual) {
        return stored != null && stored.matches("[0-9a-f]{64}")
                && MessageDigest.isEqual(
                        stored.getBytes(StandardCharsets.US_ASCII),
                        actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static ClaimCandidate candidate(ResultSet row, int ignored) throws SQLException {
        return new ClaimCandidate(
                row.getObject("event_id", UUID.class),
                row.getObject("audit_id", UUID.class),
                row.getString("event_type"), row.getString("schema_version"),
                row.getString("producer"), row.getString("payload"),
                row.getString("payload_digest"), row.getTimestamp("created_at").toInstant(),
                row.getLong("attempts"));
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

    private record ClaimCandidate(
            UUID eventId,
            UUID auditId,
            String eventType,
            String schemaVersion,
            String producer,
            String payload,
            String payloadDigest,
            Instant createdAt,
            long priorAttempts) {}
}
