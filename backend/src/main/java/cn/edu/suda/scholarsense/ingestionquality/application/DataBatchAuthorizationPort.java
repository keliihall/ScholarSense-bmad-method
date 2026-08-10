package cn.edu.suda.scholarsense.ingestionquality.application;

@FunctionalInterface
public interface DataBatchAuthorizationPort {
    DataBatchAuthorizationDecision authorize(DataBatchAuthorizationRequest request);
}
