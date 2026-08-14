package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApproval;
import java.util.Optional;
import java.util.UUID;

/** Same-owner approved aggregate plus its immutable receipt. */
@FunctionalInterface
public interface ApprovedHighRiskEvidenceQueryPort {
    Optional<ApprovedEvidence> findApproved(UUID approvalId);

    record ApprovedEvidence(HighRiskApproval approval, HighRiskApprovalReceipt receipt) {
        public ApprovedEvidence {
            if (approval == null || receipt == null
                    || !approval.approvalId().equals(receipt.approvalId())
                    || approval.approvalVersion() != receipt.approvalVersion()) {
                throw new IllegalArgumentException("HIGH_RISK_APPROVED_EVIDENCE_INVALID");
            }
        }
    }
}
