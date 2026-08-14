package cn.edu.suda.scholarsense.identityaccess.api;

@FunctionalInterface
public interface HighRiskApprovalEvidenceQueryPort {
    HighRiskApprovalEvidence query(HighRiskApprovalEvidenceQuery query);

    static HighRiskApprovalEvidenceQueryPort notInstalled() {
        return ignored -> HighRiskApprovalEvidence.notInstalled();
    }
}
