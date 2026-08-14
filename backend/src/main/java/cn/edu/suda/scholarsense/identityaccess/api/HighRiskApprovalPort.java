package cn.edu.suda.scholarsense.identityaccess.api;

public interface HighRiskApprovalPort {
    HighRiskApprovalView request(HighRiskApprovalRequest request);

    HighRiskApprovalView decide(HighRiskApprovalDecision decision);
}
