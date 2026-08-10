package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.function.Supplier;

@FunctionalInterface
public interface DataBatchTransactionPort {
    DataBatchView execute(Supplier<DataBatchView> work);
}
