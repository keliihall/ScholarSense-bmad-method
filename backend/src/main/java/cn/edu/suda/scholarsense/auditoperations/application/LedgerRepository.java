package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.time.Instant;

public interface LedgerRepository {
    final class ReadCorruptionException extends RuntimeException {
        private final long ledgerSequence;

        public ReadCorruptionException(long ledgerSequence, String safeCode) {
            this(ledgerSequence, safeCode, null);
        }

        public ReadCorruptionException(long ledgerSequence, String safeCode, Throwable cause) {
            super(safeCode, cause);
            if (ledgerSequence < 1) {
                throw new IllegalArgumentException("AUDIT_LEDGER_SEQUENCE_INVALID");
            }
            this.ledgerSequence = ledgerSequence;
        }

        public long ledgerSequence() {
            return ledgerSequence;
        }
    }

    Optional<LedgerEntry> findByAuditId(UUID auditId);

    Optional<LedgerEntry> findBySourceEventId(UUID sourceEventId);

    LedgerHead lockHead();

    LedgerHead readHead();

    List<LedgerEntry> readFrom(long startInclusive, int limit);

    void insert(LedgerEntry entry);

    void updateHead(LedgerHead expected, LedgerHead replacement);

    default void recordAppended(
            UUID receiptId,
            LocalAuditOutboxRecord source,
            LedgerHash payloadFingerprint,
            LedgerEntry entry,
            Instant observedAt) {}

    default void recordExactDuplicate(
            UUID auditId, UUID sourceEventId, Instant observedAt, String traceId) {}
}
