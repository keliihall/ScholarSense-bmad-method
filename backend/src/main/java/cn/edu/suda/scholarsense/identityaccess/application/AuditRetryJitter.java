package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface AuditRetryJitter {
    double next();
}
