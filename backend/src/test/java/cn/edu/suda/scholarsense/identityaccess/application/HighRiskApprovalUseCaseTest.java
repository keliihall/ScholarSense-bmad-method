package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApproval;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalBinding;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HighRiskApprovalUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00.123456Z");

    @Test
    void decisionReplayReturnsOriginalResultAndSameKeyDifferentBodyConflicts() {
        MemoryRepository repository = new MemoryRepository(pending());
        HighRiskApprovalUseCase useCase = useCase(repository);
        HighRiskApprovalDecisionCommand first = decision("decision-one", "APPROVE", 1);

        HighRiskApprovalResult decided = useCase.decide(first);
        HighRiskApprovalResult replay = useCase.decide(first);

        assertEquals(decided, replay);
        assertEquals(2, decided.approvalVersion());
        assertEquals("pending", decided.status());
        assertEquals(1, repository.decisionWrites);
        assertThrows(IllegalStateException.class, () -> useCase.decide(
                decision("decision-one", "REJECT", 1)));
    }

    @Test
    void terminalDecisionReplayPreservesImmutableReceiptDigest() {
        MemoryRepository repository = new MemoryRepository(pending());
        HighRiskApprovalUseCase useCase = useCase(repository);
        useCase.decide(decision("checker-one", "APPROVE", 1));

        HighRiskApprovalDecisionCommand terminal = decision("checker-two", "APPROVE", 2);
        HighRiskApprovalResult approved = useCase.decide(terminal);
        HighRiskApprovalResult replay = useCase.decide(terminal);

        assertEquals("approved", approved.status());
        assertNotNull(approved.receiptDigest());
        assertEquals(approved, replay);
        assertEquals(2, repository.decisionWrites);
    }

    private static HighRiskApprovalUseCase useCase(MemoryRepository repository) {
        return new HighRiskApprovalUseCase(repository,
                () -> uuid("019ff5a0-6000-7000-8000-000000000110"),
                canonical -> new HighRiskEvidenceSignaturePort.SignedValue(
                        "hrap-k1", "A".repeat(43), digest('f')));
    }

    private static HighRiskApprovalDecisionCommand decision(
            String key, String decision, long expectedVersion) {
        return new HighRiskApprovalDecisionCommand(
                uuid("019ff5a0-6000-7000-8000-000000000101"), expectedVersion,
                HighRiskApprovalDecisionCommand.Decision.valueOf(decision),
                expectedVersion == 1 ? digest('b') : digest('c'), digest('e'), 11,
                NOW.plusSeconds(expectedVersion), "00112233445566778899aabbccddeeff", key);
    }

    private static HighRiskApproval pending() {
        return HighRiskApproval.pending(
                uuid("019ff5a0-6000-7000-8000-000000000101"), new HighRiskApprovalBinding(
                        uuid("019ff5a0-6000-7000-8000-000000000102"), digest('1'),
                        "quality-fuse.recover", digest('a'), digest('2'), digest('3'),
                        "RECOVERY_TASK", digest('4'), 7, digest('5'), digest('6'),
                        HighRiskApprovalBinding.DataSensitivity.HIGHLY_SENSITIVE_DEIDENTIFIED,
                        "fused", "recovering", "QUALITY_RECOVERY", "HRAM-1.0.0", digest('7'),
                        "HRAP-1.0.0", digest('8'), "RFP-1.0.0", digest('9'), digest('d'),
                        digest('e'), List.of(digest('b'), digest('c')), 11,
                        "00112233445566778899aabbccddeeff"), NOW);
    }

    private static final class MemoryRepository implements HighRiskApprovalRepositoryPort {
        private HighRiskApproval approval;
        private final Map<String, DecisionReplay> decisions = new HashMap<>();
        private int decisionWrites;

        private MemoryRepository(HighRiskApproval approval) { this.approval = approval; }
        @Override public Optional<HighRiskApproval> findByIdempotencyKeyDigest(String digest) {
            return Optional.empty();
        }
        @Override public Optional<HighRiskApproval> findById(UUID id) {
            return approval.approvalId().equals(id) ? Optional.of(approval) : Optional.empty();
        }
        @Override public HighRiskApproval insertIfAbsent(String digest, HighRiskApproval value) {
            return value;
        }
        @Override public HighRiskApproval save(
                long expected, HighRiskApproval value, HighRiskApprovalReceipt receipt) {
            approval = value;
            return value;
        }
        @Override public Optional<DecisionReplay> findDecisionByIdempotencyKeyDigest(String key) {
            return Optional.ofNullable(decisions.get(key));
        }
        @Override public DecisionReplay saveDecisionIfAbsent(
                String key, String input, long expected, HighRiskApproval value,
                HighRiskApprovalReceipt receipt) {
            DecisionReplay result = decisions.computeIfAbsent(key, ignored -> {
                decisionWrites++;
                approval = value;
                return new DecisionReplay(input, value,
                        receipt == null ? null : receipt.receiptDigest());
            });
            return result;
        }
        @Override public Optional<HighRiskApprovalReceipt> findReceipt(UUID id) {
            return Optional.empty();
        }
        @Override public List<HighRiskApproval> findExpirable(int limit, Instant now) {
            return List.of();
        }
    }

    private static String digest(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
