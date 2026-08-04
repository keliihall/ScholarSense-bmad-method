package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.function.Supplier;

@FunctionalInterface
public interface CatalogTransactionPort {
    Object execute(Supplier<?> work);
}
