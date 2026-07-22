package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import java.util.Set;

public record LedgerVerificationResult(
        boolean healthy,
        long verifiedHeadSequence,
        String verifiedHeadHash,
        Set<FindingCode> findingCodes) {
    public LedgerVerificationResult {
        findingCodes = Set.copyOf(findingCodes);
    }
}
