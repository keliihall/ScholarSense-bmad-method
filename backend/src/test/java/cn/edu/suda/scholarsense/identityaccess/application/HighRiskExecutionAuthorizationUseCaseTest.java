package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApproval;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalBinding;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionAuthorizationLease;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HighRiskExecutionAuthorizationUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00.123456Z");

    @Test
    void sameApprovalAlwaysReturnsOneExecutionJtiAndSameKeyDifferentBodyConflicts() {
        EvidenceFixture evidence = approved();
        MemoryLeases leases = new MemoryLeases();
        var useCase = useCase(evidence, leases);
        HighRiskExecutionAuthorizationResult first = useCase.issueOrReplay(command("1", 'a'));
        HighRiskExecutionAuthorizationResult replay = useCase.issueOrReplay(command("1", 'a'));
        HighRiskExecutionAuthorizationResult differentKey =
                useCase.issueOrReplay(command("2", 'b'));

        assertEquals(first.executionJti(), replay.executionJti());
        assertEquals(first.executionJti(), differentKey.executionJti());
        assertThrows(IllegalStateException.class,
                () -> useCase.issueOrReplay(command("1", 'b')));
    }

    @Test
    void objectPreviewCheckerOrAuthorizationDriftRejectsIssuance() {
        EvidenceFixture evidence = approved();
        var useCase = useCase(evidence, new MemoryLeases());
        HighRiskExecutionAuthorizationCommand base = command("1", 'a');
        assertThrows(IllegalStateException.class, () -> useCase.issueOrReplay(
                new HighRiskExecutionAuthorizationCommand(
                        base.approvalId(), base.approvalVersion(), base.approvalReceiptDigest(),
                        base.requestDigest(), base.actionType(), base.objectType(), digest('e'),
                        base.objectVersion(), base.scopeDigest(), base.impactScopeDigest(),
                        base.previewDigest(), base.authorizationContextDigest(),
                        base.authenticationStateDigest(), base.checkerSetDigest(),
                        base.authorizationGeneration(), base.audience(),
                        base.idempotencyKeyDigest(), base.issuanceRequestDigest(),
                        base.trustedNow(), base.traceId())));
    }

    private static HighRiskExecutionAuthorizationUseCase useCase(
            EvidenceFixture evidence, MemoryLeases leases) {
        AtomicInteger ids = new AtomicInteger(3);
        return new HighRiskExecutionAuthorizationUseCase(
                ignored -> Optional.of(new ApprovedHighRiskEvidenceQueryPort.ApprovedEvidence(
                        evidence.approval(), evidence.receipt())), leases,
                () -> uuid("019ff5a0-5000-7000-8000-00000000010" + ids.getAndIncrement()),
                canonical -> new HighRiskEvidenceSignaturePort.SignedValue(
                        "hrap-k1", "A".repeat(43), digest('f')),
                () -> NOW.plusSeconds(1000));
    }

    private static EvidenceFixture approved() {
        HighRiskApproval approval = HighRiskApproval.pending(
                uuid("019ff5a0-5000-7000-8000-000000000101"), binding(), NOW);
        approval.approve(digest('b'), NOW.plusSeconds(1));
        approval.approve(digest('c'), NOW.plusSeconds(2));
        HighRiskApprovalReceipt receipt = new HighRiskApprovalReceipt(
                approval.approvalId(), approval.approvalVersion(), binding().requestId(),
                binding().requestDigest(), approval.status(), digest('c'), NOW.plusSeconds(2),
                approval.expiresAt(), digest('f'), "hrap-k1", "A".repeat(43), binding().traceId());
        return new EvidenceFixture(approval, receipt);
    }

    private static HighRiskExecutionAuthorizationCommand command(String key, char body) {
        HighRiskApprovalBinding value = binding();
        return new HighRiskExecutionAuthorizationCommand(
                uuid("019ff5a0-5000-7000-8000-000000000101"), 3, digest('f'),
                value.requestDigest(), value.actionType(), value.objectType(),
                value.objectRefDigest(), value.objectVersion(), value.scopeDigest(),
                value.impactScopeDigest(), value.previewDigest(),
                value.authorizationContextDigest(), value.authenticationStateDigest(),
                value.checkerSetDigest(), value.authorizationGeneration(),
                "ingestion-quality-recovery-worker", digest(key.charAt(0)), digest(body),
                NOW.plusSeconds(3), value.traceId());
    }

    private static HighRiskApprovalBinding binding() {
        return new HighRiskApprovalBinding(
                uuid("019ff5a0-5000-7000-8000-000000000102"), digest('1'),
                "quality-fuse.recover", digest('a'), digest('2'), digest('3'),
                "RECOVERY_TASK", digest('4'), 7, digest('5'), digest('6'),
                HighRiskApprovalBinding.DataSensitivity.HIGHLY_SENSITIVE_DEIDENTIFIED,
                "fused", "recovering", "QUALITY_RECOVERY", "HRAM-1.0.0", digest('7'),
                "HRAP-1.0.0", digest('8'), "RFP-1.0.0", digest('9'), digest('d'),
                digest('e'), List.of(digest('b'), digest('c')), 11,
                "00112233445566778899aabbccddeeff");
    }

    private record EvidenceFixture(HighRiskApproval approval, HighRiskApprovalReceipt receipt) {}

    private static final class MemoryLeases implements HighRiskExecutionLeaseRepositoryPort {
        private final Map<String, LeaseReplay> byKey = new HashMap<>();
        private final Map<UUID, HighRiskExecutionAuthorizationLease> byApproval = new HashMap<>();
        @Override public Optional<LeaseReplay> findByIdempotencyKeyDigest(String digest) {
            return Optional.ofNullable(byKey.get(digest));
        }
        @Override public Optional<HighRiskExecutionAuthorizationLease> findById(UUID id) {
            return byApproval.values().stream().filter(v -> v.leaseId().equals(id)).findFirst();
        }
        @Override public Optional<HighRiskExecutionAuthorizationLease> findByApprovalId(UUID id) {
            return Optional.ofNullable(byApproval.get(id));
        }
        @Override public LeaseReplay issueIfAbsent(
                String key, String body, HighRiskExecutionAuthorizationLease requested) {
            LeaseReplay replay = byKey.computeIfAbsent(key, ignored -> {
                HighRiskExecutionAuthorizationLease winner = byApproval.computeIfAbsent(
                        requested.token().approvalId(), ignoredApproval -> requested);
                return new LeaseReplay(body, winner);
            });
            return replay;
        }
        @Override public HighRiskExecutionAuthorizationLease save(
                long expected, HighRiskExecutionAuthorizationLease updated) {
            assertEquals(expected + 1, updated.leaseVersion());
            byApproval.put(updated.token().approvalId(), updated);
            return updated;
        }
    }

    private static String digest(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
