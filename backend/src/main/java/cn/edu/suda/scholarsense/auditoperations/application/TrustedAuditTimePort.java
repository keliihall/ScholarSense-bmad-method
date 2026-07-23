package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface TrustedAuditTimePort {
    TrustedAuditTime current();
}
