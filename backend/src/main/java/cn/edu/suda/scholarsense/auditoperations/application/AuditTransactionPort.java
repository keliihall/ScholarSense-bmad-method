package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.function.Supplier;

@FunctionalInterface
public interface AuditTransactionPort {
    <T> T required(Supplier<T> work);
}
