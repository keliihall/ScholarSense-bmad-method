package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.RefreshRotation;
import java.util.function.Supplier;

@FunctionalInterface
public interface RefreshTransactionPort {
    RefreshRotation execute(Supplier<RefreshRotation> work);

    default void executeRecovery(Runnable work) {
        execute(() -> {
            work.run();
            return null;
        });
    }
}
