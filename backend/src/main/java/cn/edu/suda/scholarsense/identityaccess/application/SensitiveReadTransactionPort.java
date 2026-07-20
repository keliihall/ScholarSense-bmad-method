package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.function.Supplier;

/** The supplied value is not released to an inbound adapter until the local transaction commits. */
public interface SensitiveReadTransactionPort {
    <T> T execute(Supplier<T> work);
}
