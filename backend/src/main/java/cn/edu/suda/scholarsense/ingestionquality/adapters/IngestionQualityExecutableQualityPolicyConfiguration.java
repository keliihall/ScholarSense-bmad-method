package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.ExecutableQualityPolicyBootstrapRunner;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.application.ExecutableQualityPolicyBootstrap;
import cn.edu.suda.scholarsense.ingestionquality.application.ExecutableQualityPolicyGuard;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fail-closed executable policy shared by the web role and the isolated quality worker. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "'${scholarsense.identity.enabled:false}' == 'true' || "
                + "'${scholarsense.ingestion-quality.quality-worker-enabled:false}' == 'true'")
public class IngestionQualityExecutableQualityPolicyConfiguration {
    @Bean
    FrozenExecutableQualityPolicyLoader frozenExecutableQualityPolicyLoader(
            @Value("${scholarsense.ingestion-quality.contract-root}") String contractRoot) {
        Path dataCatalogRoot = requiredAbsolutePath(contractRoot);
        Path contractsRoot = dataCatalogRoot.getParent();
        if (contractsRoot == null
                || !"data-catalog".equals(String.valueOf(dataCatalogRoot.getFileName()))
                || !"contracts".equals(String.valueOf(contractsRoot.getFileName()))
                || contractsRoot.getParent() == null) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_CONTROLLED_CONTRACT_ROOT_INVALID");
        }
        return new FrozenExecutableQualityPolicyLoader(contractsRoot.getParent());
    }

    @Bean
    ExecutableQualityPolicyGuard executableQualityPolicyGuard(
            FrozenExecutableQualityPolicyLoader policies) {
        return new ExecutableQualityPolicyGuard(policies);
    }

    @Bean
    ExecutableQualityPolicyBootstrap executableQualityPolicyBootstrap(
            FrozenExecutableQualityPolicyLoader policies) {
        return new ExecutableQualityPolicyBootstrap(policies);
    }

    @Bean
    ExecutableQualityPolicyBootstrapRunner executableQualityPolicyBootstrapRunner(
            ExecutableQualityPolicyBootstrap bootstrap) {
        return new ExecutableQualityPolicyBootstrapRunner(bootstrap);
    }

    private static Path requiredAbsolutePath(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_CONTROLLED_PATH_ABSOLUTE_REQUIRED");
        }
        return path.normalize();
    }
}
