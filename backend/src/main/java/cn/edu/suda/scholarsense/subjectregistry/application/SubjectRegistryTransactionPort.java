package cn.edu.suda.scholarsense.subjectregistry.application;

import java.util.function.Supplier;

@FunctionalInterface
public interface SubjectRegistryTransactionPort {
    Object execute(Supplier<?> work);
}
