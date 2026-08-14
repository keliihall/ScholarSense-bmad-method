package cn.edu.suda.scholarsense.ingestionquality.application;

/** External work boundary; the recovery processor invokes it outside owner DB transactions. */
@FunctionalInterface
public interface RecoveryFullReconciliationPort {
    RecoveryFullReconciliationResult reconcile(RecoveryFullReconciliationRequest request);
}
