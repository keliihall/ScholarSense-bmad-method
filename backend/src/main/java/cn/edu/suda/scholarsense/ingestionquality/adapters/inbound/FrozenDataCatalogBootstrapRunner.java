package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogBootstrap;
import java.util.Objects;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Runs the controlled, idempotent frozen-catalog bootstrap before startup completes. */
public final class FrozenDataCatalogBootstrapRunner implements SmartInitializingSingleton {
    private final FrozenDataCatalogBootstrap bootstrap;

    public FrozenDataCatalogBootstrapRunner(FrozenDataCatalogBootstrap bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap);
    }

    @Override
    public void afterSingletonsInstantiated() {
        bootstrap.run();
    }
}
