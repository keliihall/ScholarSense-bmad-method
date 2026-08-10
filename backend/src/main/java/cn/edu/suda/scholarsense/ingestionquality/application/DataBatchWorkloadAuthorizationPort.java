package cn.edu.suda.scholarsense.ingestionquality.application;

/** Authoritative workload identity, capability and revocation boundary. */
public interface DataBatchWorkloadAuthorizationPort {
    DataBatchWorkloadAuthorizationResult capture(
            DataBatchWorkloadAuthorizationRequest request);

    DataBatchWorkloadAuthorizationResult revalidate(
            DataBatchWorkloadAuthorizationEvidence captured,
            DataBatchWorkloadAuthorizationRequest request);
}
