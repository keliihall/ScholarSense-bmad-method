package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface AuditAlertRetryJitter {
    /** Returns a value from 0.5 through 1.0 inclusive. */
    double next();
}
