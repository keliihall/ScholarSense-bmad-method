package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentityReconciliationPort {
    void append(IdentityReconciliationResult result);
}
