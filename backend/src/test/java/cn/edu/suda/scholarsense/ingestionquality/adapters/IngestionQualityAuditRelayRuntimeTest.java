package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.CatalogAuditRelayScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.CatalogRetentionScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.FrozenDataCatalogBootstrapRunner;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayProcessor;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogRetentionCleanupPort;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogBootstrap;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.shared.outbox.AuditProducerBacklogPort;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class IngestionQualityAuditRelayRuntimeTest {
    @Test
    void auditWorkerOwnsTheIngestionQualityRelayScheduler() throws Exception {
        assertNotNull(IngestionQualityAuditRelayConfiguration.class.getAnnotation(
                EnableScheduling.class));
        ConditionalOnProperty condition = IngestionQualityAuditRelayConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);
        assertEquals("scholarsense.audit-ledger.enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());

        var relay = CatalogAuditRelayScheduler.class.getDeclaredMethod("relay");
        Scheduled scheduled = relay.getAnnotation(Scheduled.class);
        assertEquals(
                "${scholarsense.audit.collector.initial-delay}",
                scheduled.initialDelayString());
        assertEquals(
                "${scholarsense.audit.collector.interval}",
                scheduled.fixedDelayString());

        CatalogAuditRelayProcessor processor = mock(CatalogAuditRelayProcessor.class);
        new CatalogAuditRelayScheduler(processor).relay();
        verify(processor).runBatch();

        var cleanupMethod = CatalogRetentionScheduler.class
                .getDeclaredMethod("cleanupExpired");
        Scheduled cleanupSchedule = cleanupMethod.getAnnotation(Scheduled.class);
        assertEquals(
                "${scholarsense.ingestion-quality.retention.initial-delay:PT1M}",
                cleanupSchedule.initialDelayString());
        assertEquals(
                "${scholarsense.ingestion-quality.retention.interval:PT24H}",
                cleanupSchedule.fixedDelayString());
        CatalogRetentionCleanupPort cleanup = mock(CatalogRetentionCleanupPort.class);
        new CatalogRetentionScheduler(cleanup).cleanupExpired();
        verify(cleanup).cleanupExpired();
    }

    @Test
    void webConfigurationDoesNotOwnAnUnreachableRelayProcessor() {
        assertFalse(Arrays.stream(IngestionQualityConfiguration.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType() == CatalogAuditRelayProcessor.class));
        assertEquals(
                AuthorizationObjectEvidenceProvider.class,
                Arrays.stream(IngestionQualityConfiguration.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals(
                                "dataSourceCatalogOwnerEvidenceProvider"))
                        .findFirst()
                        .orElseThrow()
                        .getReturnType());
    }

    @Test
    void databaseAdaptersDependOnTheVerifiedConnectionProfile() {
        assertProfileDependency(
                IngestionQualityConfiguration.class,
                "jdbcDataSourceCatalogStore");
        assertProfileDependency(
                IngestionQualityAuditRelayConfiguration.class,
                "dataSourceCatalogAuditRelayWork");
    }

    @Test
    void frozenCatalogBootstrapHasAStartupCallerWithoutAnHttpSurface() {
        FrozenDataCatalogBootstrap bootstrap = mock(FrozenDataCatalogBootstrap.class);

        new FrozenDataCatalogBootstrapRunner(bootstrap).afterSingletonsInstantiated();

        verify(bootstrap).run();
        assertEquals(
                FrozenDataCatalogBootstrapRunner.class,
                Arrays.stream(IngestionQualityConfiguration.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals(
                                "frozenDataCatalogBootstrapRunner"))
                        .findFirst()
                        .orElseThrow()
                        .getReturnType());
    }

    @Test
    void frozenCatalogLoaderRequiresTheProtectedTargetSigningKeyMount() {
        var method = Arrays.stream(IngestionQualityConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("frozenDataCatalogLoader"))
                .findFirst()
                .orElseThrow();

        assertEquals(10, method.getParameterCount());
        Value runtimeFloor = method.getParameters()[6].getAnnotation(Value.class);
        Value releaseFloor = method.getParameters()[7].getAnnotation(Value.class);
        assertNotNull(runtimeFloor);
        assertNotNull(releaseFloor);
        assertEquals(
                "${scholarsense.ingestion-quality.minimum-handoff-revision}",
                runtimeFloor.value());
        assertEquals(
                "${DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION}",
                releaseFloor.value());
        Value signingKeyPath = method.getParameters()[8].getAnnotation(Value.class);
        assertNotNull(signingKeyPath);
        assertEquals(
                "${scholarsense.ingestion-quality.target-signing-key-path}",
                signingKeyPath.value());
    }

    @Test
    void centralBacklogMeasurementConsumesTheSharedProducerPort() throws Exception {
        var method = Arrays.stream(
                        cn.edu.suda.scholarsense.auditoperations.adapters.AuditWorkerConfiguration.class
                                .getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("auditBacklogMeasurements"))
                .findFirst()
                .orElseThrow();
        ParameterizedType producers = (ParameterizedType) method.getGenericParameterTypes()[0];
        assertEquals(AuditProducerBacklogPort.class, producers.getActualTypeArguments()[0]);
    }

    private static void assertProfileDependency(Class<?> configuration, String methodName) {
        var method = Arrays.stream(configuration.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        assertEquals(
                1,
                Arrays.stream(method.getParameterTypes())
                        .filter(type -> type == PostgreSqlConnectionProfile.class)
                        .count());
    }
}
