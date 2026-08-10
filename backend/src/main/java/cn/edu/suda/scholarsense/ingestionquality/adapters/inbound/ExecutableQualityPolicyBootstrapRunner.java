package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.ExecutableQualityPolicyBootstrap;
import java.util.Objects;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Fails application startup unless the exact executable QMDP/QSHM chain can be loaded. */
public final class ExecutableQualityPolicyBootstrapRunner implements SmartInitializingSingleton {
    private final ExecutableQualityPolicyBootstrap bootstrap;

    public ExecutableQualityPolicyBootstrapRunner(ExecutableQualityPolicyBootstrap bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap);
    }

    @Override
    public void afterSingletonsInstantiated() {
        bootstrap.start();
    }
}
