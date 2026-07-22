package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AlertOutboxPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditTransactionPort;
import cn.edu.suda.scholarsense.auditoperations.application.FindingIdPort;
import cn.edu.suda.scholarsense.auditoperations.application.FindingRepository;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingSeverity;
import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Persists immutable privacy-safe findings and an independently retried alert projection. */
public final class JdbcAuditEvidenceRepository implements FindingRepository, AlertOutboxPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FindingIdPort identifiers;
    private final AuditTransactionPort transactions;

    public JdbcAuditEvidenceRepository(
            JdbcTemplate jdbc, ObjectMapper json, FindingIdPort identifiers) {
        this(jdbc, json, identifiers, new AuditTransactionPort() {
            @Override
            public <T> T required(java.util.function.Supplier<T> work) {
                return work.get();
            }
        });
    }

    public JdbcAuditEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            FindingIdPort identifiers,
            AuditTransactionPort transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.identifiers = identifiers;
        this.transactions = transactions;
    }

    @Override
    public void save(IntegrityFinding finding) {
        jdbc.update("""
                insert into audit_operations.ao_integrity_finding (
                  finding_id, code, severity, policy_version, hash_profile_version,
                  sequence_start, sequence_end, source_range, safe_digest, trace_id,
                  occurred_at, detected_at, runbook_ref)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                finding.findingId(), finding.code().name(), finding.severity().name().toLowerCase(),
                finding.policyVersion(), finding.hashProfileVersion(), finding.sequenceStart(),
                finding.sequenceEnd(), finding.sourceRange(), finding.safeDigest().value(),
                finding.traceId(), Timestamp.from(finding.occurredAt()),
                Timestamp.from(finding.detectedAt()), finding.runbookRef());
    }

    @Override
    public boolean saveIfAbsent(IntegrityFinding finding) {
        int inserted = jdbc.update("""
                insert into audit_operations.ao_integrity_finding (
                  finding_id, code, severity, policy_version, hash_profile_version,
                  sequence_start, sequence_end, source_range, safe_digest, trace_id,
                  occurred_at, detected_at, runbook_ref)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (code, source_range, safe_digest)
                  where code='AUDIT_INGESTION_CONTRACT_REJECTED'
                do nothing
                """,
                finding.findingId(), finding.code().name(), finding.severity().name().toLowerCase(),
                finding.policyVersion(), finding.hashProfileVersion(), finding.sequenceStart(),
                finding.sequenceEnd(), finding.sourceRange(), finding.safeDigest().value(),
                finding.traceId(), Timestamp.from(finding.occurredAt()),
                Timestamp.from(finding.detectedAt()), finding.runbookRef());
        return inserted == 1;
    }

    @Override
    public boolean hasActivePermanentFinding() {
        Boolean result = jdbc.queryForObject("""
                select exists (
                  select 1
                  from audit_operations.ao_integrity_finding f
                  where f.code in (
                    'AUDIT_INGESTION_DUPLICATE_CONFLICT',
                    'AUDIT_INGESTION_CONTRACT_REJECTED',
                    'AUDIT_LEDGER_SEQUENCE_GAP',
                    'AUDIT_LEDGER_PREVIOUS_HASH_MISMATCH',
                    'AUDIT_LEDGER_ENTRY_HASH_MISMATCH',
                    'AUDIT_LEDGER_HEAD_MISMATCH')
                  and not exists (
                    select 1 from audit_operations.ao_finding_disposition d
                    where d.finding_id=f.finding_id and d.disposition='resolved'))
                """, Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public List<IntegrityFinding> activeFindings() {
        return jdbc.query("""
                select f.finding_id, f.code, f.severity, f.policy_version,
                       f.hash_profile_version, f.sequence_start, f.sequence_end,
                       f.source_range, f.safe_digest, f.trace_id, f.occurred_at,
                       f.detected_at, f.runbook_ref
                from audit_operations.ao_integrity_finding f
                where not exists (
                  select 1 from audit_operations.ao_finding_disposition d
                  where d.finding_id=f.finding_id and d.disposition='resolved')
                  and not (
                    f.code='AUDIT_INGESTION_BACKLOG' and exists (
                      select 1 from audit_operations.ao_alert_outbox a
                      where a.finding_id=f.finding_id and a.event_type='resolved'))
                order by f.detected_at, f.finding_id
                """, this::finding);
    }

    @Override
    public void enqueueActive(IntegrityFinding finding) {
        enqueue(finding, "active", finding.detectedAt());
    }

    @Override
    public void enqueueResolved(IntegrityFinding finding, java.time.Instant resolvedAt) {
        enqueue(finding, "resolved", resolvedAt);
    }

    private void enqueue(IntegrityFinding finding, String eventType, java.time.Instant createdAt) {
        transactions.required(() -> {
            enqueueInTransaction(finding, eventType, createdAt);
            return null;
        });
    }

    private void enqueueInTransaction(
            IntegrityFinding finding, String eventType, java.time.Instant createdAt) {
        String deduplicationKey = sha256(finding.code().name() + "\0" + finding.sourceRange());
        long lockKey = Long.parseUnsignedLong(deduplicationKey.substring(0, 16), 16);
        jdbc.execute("select pg_advisory_xact_lock(" + lockKey + ")");
        java.util.UUID alertId = identifiers.newId(createdAt);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "AUDIT-INTEGRITY-ALERT-1.0.0");
        payload.put("alertId", alertId.toString());
        payload.put("findingId", finding.findingId().toString());
        payload.put("event", eventType);
        payload.put("code", finding.code().name());
        payload.put("severity", finding.severity().name().toLowerCase());
        payload.put("policyVersion", finding.policyVersion());
        payload.put("hashProfileVersion", finding.hashProfileVersion());
        payload.put("sequenceRange", sequenceRange(finding));
        payload.put("sourceRange", finding.sourceRange());
        payload.put("safeDigest", finding.safeDigest().value());
        payload.put("traceId", finding.traceId());
        payload.put("occurredAt", finding.occurredAt().toString());
        payload.put("detectedAt", finding.detectedAt().toString());
        payload.put("runbookRef", finding.runbookRef());
        jdbc.update("""
                insert into audit_operations.ao_alert_outbox (
                  alert_id, finding_id, event_type, deduplication_key, safe_payload,
                  delivery_status, attempts, next_attempt_at, created_at)
                select ?, ?, ?, ?, cast(? as jsonb), 'pending', 0, ?, ?
                where not exists (
                  select 1 from audit_operations.ao_alert_outbox existing
                  where existing.event_type=? and existing.deduplication_key=?
                    and existing.created_at >= ? and existing.created_at <= ?)
                """,
                alertId, finding.findingId(), eventType, deduplicationKey,
                json(payload), Timestamp.from(createdAt), Timestamp.from(createdAt),
                eventType, deduplicationKey,
                Timestamp.from(createdAt.minus(Duration.ofSeconds(300))),
                Timestamp.from(createdAt.plus(Duration.ofSeconds(300))));
    }

    private IntegrityFinding finding(ResultSet result, int ignored) throws SQLException {
        Long start = result.getObject("sequence_start", Long.class);
        Long end = result.getObject("sequence_end", Long.class);
        return new IntegrityFinding(
                result.getObject("finding_id", java.util.UUID.class),
                FindingCode.valueOf(result.getString("code")),
                FindingSeverity.valueOf(result.getString("severity").toUpperCase()),
                result.getString("policy_version"),
                result.getString("hash_profile_version"),
                start,
                end,
                result.getString("source_range"),
                new LedgerHash(result.getString("safe_digest")),
                result.getString("trace_id"),
                result.getTimestamp("occurred_at").toInstant(),
                result.getTimestamp("detected_at").toInstant(),
                result.getString("runbook_ref"));
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("AUDIT_ALERT_PAYLOAD_INVALID", failure);
        }
    }

    private static String sequenceRange(IntegrityFinding finding) {
        if (finding.sequenceStart() == null) {
            return null;
        }
        return finding.sequenceStart().equals(finding.sequenceEnd())
                ? finding.sequenceStart().toString()
                : finding.sequenceStart() + "-" + finding.sequenceEnd();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
