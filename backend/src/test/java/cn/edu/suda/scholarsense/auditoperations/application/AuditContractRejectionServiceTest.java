package cn.edu.suda.scholarsense.auditoperations.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AuditContractRejectionServiceTest {

    @Test
    void replayOfTheSameRejectedEnvelopeDoesNotCreateAnotherFindingOrAlert() {
        RecordingFindings findings = new RecordingFindings();
        List<IntegrityFinding> alerts = new ArrayList<>();
        AuditContractRejectionService service = new AuditContractRejectionService(
                findings,
                alerts::add,
                new AuditTransactionPort() {
                    @Override
                    public <T> T required(Supplier<T> work) {
                        return work.get();
                    }
                },
                () -> NOW,
                policy(),
                AuditUuidV7::generate);
        AuditContractRejectionCommand rejection = new AuditContractRejectionCommand(
                "identity-access", "a".repeat(64), "b".repeat(32), NOW.minusSeconds(1));

        service.reject(rejection);
        service.reject(rejection);

        assertEquals(1, findings.values.size());
        assertEquals(1, alerts.size());
        assertEquals(findings.values.getFirst().findingId(), alerts.getFirst().findingId());
    }

    private static AuditPolicyPort policy() {
        return new AuditPolicyPort() {
            @Override
            public String ingestionPolicyVersion() {
                return "AUDIT-INGESTION-POLICY-1.0.0";
            }

            @Override
            public String hashProfileVersion() {
                return "AUDIT-LEDGER-HASH-1.0.0";
            }
        };
    }

    private static final class RecordingFindings implements FindingRepository {
        private final List<IntegrityFinding> values = new ArrayList<>();

        @Override
        public void save(IntegrityFinding finding) {
            values.add(finding);
        }

        @Override
        public boolean saveIfAbsent(IntegrityFinding finding) {
            if (values.stream().anyMatch(existing ->
                    existing.code() == finding.code()
                            && existing.sourceRange().equals(finding.sourceRange())
                            && existing.safeDigest().equals(finding.safeDigest()))) {
                return false;
            }
            values.add(finding);
            return true;
        }

        @Override
        public boolean hasActivePermanentFinding() {
            return !values.isEmpty();
        }

        @Override
        public List<IntegrityFinding> activeFindings() {
            return List.copyOf(values);
        }
    }
}
