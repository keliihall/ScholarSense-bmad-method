package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.function.Supplier;

public interface IdentitySyncTransactionPort {
    <T> T execute(Supplier<T> work);
}
