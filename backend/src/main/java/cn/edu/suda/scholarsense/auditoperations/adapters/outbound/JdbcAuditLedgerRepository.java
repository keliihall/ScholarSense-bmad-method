package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.LedgerRepository;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL writer/verifier repository. Head locking, ledger insert and head update share the caller transaction. */
public final class JdbcAuditLedgerRepository implements LedgerRepository {
    private static final String ENTRY_COLUMNS = """
            ledger_sequence, previous_hash, entry_hash, hash_profile_version, audit_id,
            source_event_id, producer_module, event_type, event_schema_version,
            fact_schema_version, source_created_at, collected_at, trace_id,
            aggregate_version, payload_fingerprint, payload
            """;

    private final JdbcTemplate jdbc;
    private final AuditLedgerJsonCodec codec;
    private final RowMapper<LedgerEntry> entryMapper = this::entry;

    public JdbcAuditLedgerRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.codec = new AuditLedgerJsonCodec(json);
    }

    @Override
    public Optional<LedgerEntry> findByAuditId(UUID auditId) {
        return jdbc.query(
                "select " + ENTRY_COLUMNS + " from audit_operations.ao_audit_ledger where audit_id=?",
                entryMapper,
                auditId).stream().findFirst();
    }

    @Override
    public Optional<LedgerEntry> findBySourceEventId(UUID sourceEventId) {
        return jdbc.query(
                "select " + ENTRY_COLUMNS
                        + " from audit_operations.ao_audit_ledger where source_event_id=?",
                entryMapper,
                sourceEventId).stream().findFirst();
    }

    @Override
    public LedgerHead lockHead() {
        return jdbc.queryForObject("""
                select ledger_sequence, entry_hash
                from audit_operations.ao_audit_ledger_head
                where singleton_id=1
                for update
                """, (result, row) -> head(result));
    }

    @Override
    public LedgerHead readHead() {
        return jdbc.queryForObject("""
                select ledger_sequence, entry_hash
                from audit_operations.ao_audit_ledger_head
                where singleton_id=1
                """, (result, row) -> head(result));
    }

    @Override
    public List<LedgerEntry> readFrom(long startInclusive, int limit) {
        if (startInclusive < 1 || limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("AUDIT_LEDGER_READ_RANGE_INVALID");
        }
        return jdbc.query(
                "select " + ENTRY_COLUMNS
                        + " from audit_operations.ao_audit_ledger"
                        + " where ledger_sequence>=? order by ledger_sequence limit ?",
                entryMapper,
                startInclusive,
                limit);
    }

    @Override
    public void insert(LedgerEntry entry) {
        jdbc.update("""
                insert into audit_operations.ao_audit_ledger (
                  ledger_sequence, audit_id, source_event_id, previous_hash, entry_hash,
                  hash_profile_version, producer_module, event_type, event_schema_version,
                  fact_schema_version, source_created_at, collected_at, trace_id,
                  aggregate_version, payload_fingerprint, payload, retention_schedule_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                entry.ledgerSequence(), entry.auditId(), entry.sourceEventId(),
                entry.previousHash().value(), entry.entryHash().value(), entry.hashProfileVersion(),
                entry.producerModule(), entry.eventType(), entry.eventSchemaVersion(),
                entry.factSchemaVersion(), Timestamp.from(entry.sourceCreatedAt()),
                Timestamp.from(entry.collectedAt()), entry.traceId(), entry.aggregateVersion(),
                entry.payloadFingerprint().value(), codec.writeFact(entry.payload()),
                entry.payload().retentionScheduleVersion());
    }

    @Override
    public void updateHead(LedgerHead expected, LedgerHead replacement) {
        int updated = jdbc.update("""
                update audit_operations.ao_audit_ledger_head
                set ledger_sequence=?, entry_hash=?, updated_at=clock_timestamp()
                where singleton_id=1 and ledger_sequence=? and entry_hash=?
                """,
                replacement.ledgerSequence(), replacement.entryHash().value(),
                expected.ledgerSequence(), expected.entryHash().value());
        if (updated != 1) {
            throw new ConcurrencyFailureException("AUDIT_LEDGER_HEAD_CHANGED");
        }
    }

    @Override
    public void recordAppended(
            UUID receiptId,
            LocalAuditOutboxRecord source,
            LedgerHash payloadFingerprint,
            LedgerEntry entry,
            Instant observedAt) {
        jdbc.update("""
                insert into audit_operations.ao_ingestion_receipt (
                  receipt_id, audit_id, source_event_id, payload_fingerprint, outcome,
                  ledger_sequence, entry_hash, duplicate_observed_count,
                  first_observed_at, last_observed_at, trace_id)
                values (?, ?, ?, ?, 'appended', ?, ?, 0, ?, ?, ?)
                """,
                receiptId, source.auditId(), source.eventId(), payloadFingerprint.value(),
                entry.ledgerSequence(), entry.entryHash().value(), Timestamp.from(observedAt),
                Timestamp.from(observedAt), source.fact().traceId());
    }

    @Override
    public void recordExactDuplicate(
            UUID auditId, UUID sourceEventId, Instant observedAt, String traceId) {
        int updated = jdbc.update("""
                update audit_operations.ao_ingestion_receipt
                set outcome='exact-duplicate',
                    duplicate_observed_count=duplicate_observed_count+1,
                    last_observed_at=?, trace_id=?
                where audit_id=? and source_event_id=?
                """, Timestamp.from(observedAt), traceId, auditId, sourceEventId);
        if (updated != 1) {
            throw new ConcurrencyFailureException("AUDIT_INGESTION_RECEIPT_MISSING");
        }
    }

    private LedgerEntry entry(ResultSet result, int ignored) throws SQLException {
        long ledgerSequence = result.getLong("ledger_sequence");
        try {
            return new LedgerEntry(
                    ledgerSequence,
                    new LedgerHash(result.getString("previous_hash")),
                    new LedgerHash(result.getString("entry_hash")),
                    result.getString("hash_profile_version"),
                    result.getObject("audit_id", UUID.class),
                    result.getObject("source_event_id", UUID.class),
                    result.getString("producer_module"),
                    result.getString("event_type"),
                    result.getString("event_schema_version"),
                    result.getString("fact_schema_version"),
                    result.getTimestamp("source_created_at").toInstant(),
                    result.getTimestamp("collected_at").toInstant(),
                    result.getString("trace_id"),
                    result.getObject("aggregate_version", Long.class),
                    new LedgerHash(result.getString("payload_fingerprint")),
                    codec.readFact(result.getString("payload")));
        } catch (RuntimeException corruption) {
            throw new LedgerRepository.ReadCorruptionException(
                    ledgerSequence, "AUDIT_LEDGER_STORED_ROW_INVALID", corruption);
        }
    }

    private static LedgerHead head(ResultSet result) throws SQLException {
        return new LedgerHead(
                result.getLong("ledger_sequence"),
                new LedgerHash(result.getString("entry_hash")));
    }
}
