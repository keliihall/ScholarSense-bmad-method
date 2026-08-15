package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.IdentityAccessConfiguration;
import cn.edu.suda.scholarsense.identityaccess.adapters.IdentitySyncConfiguration;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpIdentityAuthoritySourceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpRemoteIdentityProviderClient;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpResponsibilityAuthoritySourceAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HttpResponsibilityFullSnapshotSourceAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.IngestionQualityQualityWorkerConfiguration;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.QualityWorkerProviderAdapters;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClient;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClientFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomHttpClientObservabilityTest {
    private static final List<Class<?>> ADAPTERS = List.of(
            HttpIdentityAuthoritySourceAdapter.class,
            HttpResponsibilityAuthoritySourceAdapter.class,
            HttpResponsibilityFullSnapshotSourceAdapter.class,
            HttpRemoteIdentityProviderClient.class,
            QualityWorkerProviderAdapters.class);

    @Test
    void everyCustomJavaHttpAdapterHasNoRawClientFieldAndExposesGovernedWiring() {
        for (Class<?> adapter : ADAPTERS) {
            assertFalse(List.of(adapter.getDeclaredFields()).stream()
                    .map(Field::getType)
                    .anyMatch(HttpClient.class::equals), adapter.getName());
            assertTrue(List.of(adapter.getConstructors()).stream()
                    .flatMap(constructor -> List.of(constructor.getParameterTypes()).stream())
                    .anyMatch(TrustedHttpClient.class::equals), adapter.getName());
        }
    }

    @Test
    void productionConfigurationsRequireTheGovernedClientFactory() {
        assertFactoryParameter(IdentitySyncConfiguration.class, "identityAuthoritySource");
        assertFactoryParameter(IdentitySyncConfiguration.class, "responsibilityAuthoritySource");
        assertFactoryParameter(IdentitySyncConfiguration.class, "responsibilityFullSnapshotSource");
        assertFactoryParameter(IdentityAccessConfiguration.class, "remoteIdentityProviderClient");
        assertFactoryParameter(
                IngestionQualityQualityWorkerConfiguration.class,
                "ingestionQualityQualityWorkerProviderAdapters");
    }

    private static void assertFactoryParameter(Class<?> configuration, String methodName) {
        Method method = List.of(configuration.getDeclaredMethods()).stream()
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        assertTrue(List.of(method.getParameterTypes()).contains(TrustedHttpClientFactory.class),
                configuration.getName() + "#" + methodName);
    }
}
