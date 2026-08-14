package cn.edu.suda.scholarsense.ingestionquality.application;

/** Executes external backfill/reconciliation/sample work outside owner DB transactions. */
@FunctionalInterface
public interface RecoveryValidationExternalWorkPort {
    RecoveryValidationExecution execute(RecoveryValidationExecutionRequest request);
}
