package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.CatalogAuditRelayScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.CatalogRetentionScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.FrozenDataCatalogBootstrapRunner;
import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.QualitySnapshotRetentionScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogRetentionCleanup;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotRetentionAuthorityIngest;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotRetentionCandidateFinder;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualitySnapshotRetentionExecutor;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.TrustedTimeQualitySnapshotRetentionIds;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayProcessor;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogRetentionCleanupPort;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogBootstrap;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionOrchestrator;
import cn.edu.suda.scholarsense.shared.outbox.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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

        assertFalse(Arrays.stream(IngestionQualityAuditRelayConfiguration.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType() == JdbcCatalogRetentionCleanup.class
                        || method.getReturnType() == CatalogRetentionScheduler.class));
    }

    @Test
    void retentionSchedulerKeepsItsScheduleButMovesBehindItsOwnStartupGate() throws Exception {
        var cleanupMethod = CatalogRetentionScheduler.class.getDeclaredMethod("cleanupExpired");
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

        Class<?> retention = Class.forName(
                "cn.edu.suda.scholarsense.ingestionquality.adapters."
                        + "IngestionQualityRetentionConfiguration");
        assertConfigurationCondition(
                retention, "scholarsense.ingestion-quality.retention-enabled");
        assertNotNull(retention.getAnnotation(EnableScheduling.class));
        assertExactlyOneReturnType(retention, PostgreSqlConnectionProfile.class);
        assertExactlyOneReturnType(retention, JdbcCatalogRetentionCleanup.class);
        assertExactlyOneReturnType(retention, CatalogRetentionScheduler.class);
        assertExactlyOneReturnType(
                retention, JdbcQualitySnapshotRetentionCandidateFinder.class);
        assertExactlyOneReturnType(retention, JdbcQualitySnapshotRetentionExecutor.class);
        assertExactlyOneDeclaredReturnType(
                retention, QualitySnapshotRetentionOrchestrator.class);
        assertExactlyOneReturnType(retention, QualitySnapshotRetentionScheduler.class);
        assertExactlyOneDeclaredReturnType(
                retention, TrustedTimeQualitySnapshotRetentionIds.class);

        QualitySnapshotRetentionOrchestrator orchestrator =
                mock(QualitySnapshotRetentionOrchestrator.class);
        new QualitySnapshotRetentionScheduler(orchestrator).executeOne();
        verify(orchestrator).runOne(argThat(trace ->
                trace != null && trace.matches("(?!0{32})[0-9a-f]{32}")));

        W3cTraceContext scheduledContext = new W3cTraceContext(
                "1234567890abcdef1234567890abcdef", "1234567890abcdef", true);
        QualitySnapshotRetentionOrchestrator tracedOrchestrator =
                mock(QualitySnapshotRetentionOrchestrator.class);
        new QualitySnapshotRetentionScheduler(
                tracedOrchestrator, () -> java.util.Optional.of(scheduledContext),
                new W3cTraceContextCodec()).executeOne();
        verify(tracedOrchestrator).runOne(scheduledContext.traceId());
    }

    @Test
    void fiveRuntimeEntrypointsOwnDedicatedStartupProfileBeans() throws Exception {
        Class<?> qualityWorker = Class.forName(
                "cn.edu.suda.scholarsense.ingestionquality.adapters."
                        + "IngestionQualityQualityWorkerConfiguration");
        Class<?> retention = Class.forName(
                "cn.edu.suda.scholarsense.ingestionquality.adapters."
                        + "IngestionQualityRetentionConfiguration");
        Class<?> authority = Class.forName(
                "cn.edu.suda.scholarsense.ingestionquality.adapters."
                        + "IngestionQualityConsumerRegistryAuthorityConfiguration");

        assertExactlyOneReturnType(
                IngestionQualityConfiguration.class, PostgreSqlConnectionProfile.class);
        assertConfigurationCondition(
                qualityWorker, "scholarsense.ingestion-quality.quality-worker-enabled");
        assertExactlyOneReturnType(qualityWorker, PostgreSqlConnectionProfile.class);
        assertExactlyOneReturnType(
                IngestionQualityAuditRelayConfiguration.class,
                PostgreSqlConnectionProfile.class);
        assertExactlyOneReturnType(retention, PostgreSqlConnectionProfile.class);
        assertConfigurationCondition(
                authority,
                "scholarsense.ingestion-quality.consumer-registry-authority-enabled");
        assertExactlyOneReturnType(authority, PostgreSqlConnectionProfile.class);
        assertExactlyOneReturnType(
                authority, JdbcQualitySnapshotRetentionAuthorityIngest.class);
        assertFalse(Arrays.stream(authority.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType() == QualitySnapshotRetentionScheduler.class
                        || method.getReturnType() == JdbcQualitySnapshotRetentionExecutor.class));
    }

    @Test
    void executableQualityPolicyIsSharedWithoutBindingWorkerToWebIdentity() {
        ConditionalOnExpression condition =
                IngestionQualityExecutableQualityPolicyConfiguration.class
                        .getAnnotation(ConditionalOnExpression.class);
        assertTrue(condition.value().contains("scholarsense.identity.enabled"));
        assertTrue(condition.value().contains(
                "scholarsense.ingestion-quality.quality-worker-enabled"));
        assertEquals(
                1,
                Arrays.stream(
                                IngestionQualityExecutableQualityPolicyConfiguration.class
                                        .getDeclaredMethods())
                        .filter(method -> method.getReturnType()
                                == cn.edu.suda.scholarsense.ingestionquality.application
                                        .ExecutableQualityPolicyGuard.class)
                        .count());
        assertFalse(Arrays.stream(IngestionQualityConfiguration.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType()
                        == cn.edu.suda.scholarsense.ingestionquality.application
                                .ExecutableQualityPolicyGuard.class));
    }

    @Test
    void webConfigurationDoesNotOwnAnUnreachableRelayProcessor() {
        assertFalse(Arrays.stream(IngestionQualityConfiguration.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType() == CatalogAuditRelayProcessor.class));
        assertEquals(
                AuthorizationObjectEvidenceProvider.class,
                Arrays.stream(IngestionQualityConfiguration.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals(
                                "qualitySnapshotOwnerEvidenceProvider"))
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
        assertProfileDependency(
                IngestionQualityConsumerRegistryAuthorityConfiguration.class,
                "ingestionQualityQualitySnapshotRetentionAuthorityIngest");
        assertProfileDependency(
                IngestionQualityRetentionConfiguration.class,
                "ingestionQualityQualitySnapshotRetentionCandidateFinder");
        assertProfileDependency(
                IngestionQualityRetentionConfiguration.class,
                "ingestionQualityQualitySnapshotRetentionExecutor");
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

    private static void assertConfigurationCondition(Class<?> configuration, String property) {
        ConditionalOnProperty condition = configuration.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals(property, condition.name()[0]);
        assertEquals("true", condition.havingValue());
    }

    private static void assertExactlyOneReturnType(Class<?> configuration, Class<?> returnType) {
        assertEquals(
                1,
                Arrays.stream(configuration.getDeclaredMethods())
                        .filter(method -> method.getReturnType() == returnType)
                        .count(),
                configuration.getSimpleName() + " -> " + returnType.getSimpleName());
        assertTrue(Arrays.stream(configuration.getDeclaredMethods())
                .filter(method -> method.getReturnType() == returnType)
                .allMatch(method -> Arrays.asList(method.getParameterTypes())
                        .contains(PostgreSqlConnectionProfile.class)
                        || returnType == PostgreSqlConnectionProfile.class));
    }

    private static void assertExactlyOneDeclaredReturnType(
            Class<?> configuration, Class<?> returnType) {
        assertEquals(
                1,
                Arrays.stream(configuration.getDeclaredMethods())
                        .filter(method -> method.getReturnType() == returnType)
                        .count(),
                configuration.getSimpleName() + " -> " + returnType.getSimpleName());
    }
}
