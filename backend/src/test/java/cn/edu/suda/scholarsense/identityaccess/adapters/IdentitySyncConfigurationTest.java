package cn.edu.suda.scholarsense.identityaccess.adapters;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.IdentitySyncScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.ResponsibilityReconciliationScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.ResponsibilitySyncScheduler;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationConsumerDatabase;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationDatabaseRoleVerifier;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.ResponsibilityV2CutoverDatabase;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeEncryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeDecryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverCommandSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.WorkloadIdentityAuthenticationPort;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.RuntimeConfigurationTest;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class IdentitySyncConfigurationTest {

    @Test
    void consumerDatabaseRejectsMismatchedTransactionDataSource() {
        DataSource jdbcDataSource = mock(DataSource.class);
        DataSource transactionDataSource = mock(DataSource.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessInvalidationConsumerDatabase(
                        new JdbcTemplate(jdbcDataSource),
                        new TransactionTemplate(
                                new DataSourceTransactionManager(
                                        transactionDataSource))));
    }

    @Test
    void cutoverDatabaseRejectsMismatchedTransactionDataSource() {
        DataSource jdbcDataSource = mock(DataSource.class);
        DataSource transactionDataSource = mock(DataSource.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ResponsibilityV2CutoverDatabase(
                        new JdbcTemplate(jdbcDataSource),
                        new TransactionTemplate(
                                new DataSourceTransactionManager(
                                        transactionDataSource))));
    }

    @Test
    void producerDatabaseRejectsMismatchedTransactionDataSource() {
        DataSource jdbcDataSource = mock(DataSource.class);
        DataSource transactionDataSource = mock(DataSource.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> AccessInvalidationDatabaseRoleVerifier
                        .verifyProducerTransactionBoundary(
                                new JdbcTemplate(jdbcDataSource),
                                new TransactionTemplate(
                                        new DataSourceTransactionManager(
                                                transactionDataSource))));
    }

    @Test
    void consumerDatabaseRejectsBlankPassword() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessInvalidationConsumerDatabase.connect(
                        "jdbc:postgresql://localhost/scholarsense",
                        "consumer-login",
                        " "));
    }

    @Test
    void cutoverDatabaseRejectsBlankPassword() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ResponsibilityV2CutoverDatabase.connect(
                        "jdbc:postgresql://localhost/scholarsense",
                        "cutover-login",
                        " "));
    }

    @Test
    void roleIsolationVerificationCannotBeDisabledOutsideTest() {
        var values = new HashMap<>(
                RuntimeConfigurationTest.validEnvironment("prod", "worker"));
        values.put("SCHOLARSENSE_IDENTITY_SYNC_ENABLED", "true");
        values.put(
                "SCHOLARSENSE_CLOCK_SOURCE_REF",
                "config://prod/campus-ntp-a");
        values.put(
                "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
                "config://prod/identity-authority-profile-1-0-0");
        values.put(
                "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
                "config://prod/responsibility-authority-profile-2-0-0");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);
        DatabaseResources producer = databaseResources();

        var verification = new IdentitySyncConfiguration()
                .accessInvalidationDatabaseRoleIsolation(
                        producer.jdbc(),
                        producer.transactions(),
                        consumerDatabase(),
                        runtime,
                        false);

        assertThrows(
                IllegalStateException.class,
                verification::afterSingletonsInstantiated);
    }

    @Test
    void cutoverRoleIsolationVerificationCannotBeDisabledOutsideTest() {
        var values = new HashMap<>(
                RuntimeConfigurationTest.validEnvironment("prod", "worker"));
        values.put("SCHOLARSENSE_IDENTITY_SYNC_ENABLED", "true");
        values.put(
                "SCHOLARSENSE_CLOCK_SOURCE_REF",
                "config://prod/campus-ntp-a");
        values.put(
                "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
                "config://prod/identity-authority-profile-1-0-0");
        values.put(
                "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
                "config://prod/responsibility-authority-profile-2-0-0");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);
        DatabaseResources producer = databaseResources();

        var verification = new IdentitySyncConfiguration()
                .responsibilityV2CutoverDatabaseRoleIsolation(
                        producer.jdbc(),
                        producer.transactions(),
                        consumerDatabase(),
                        cutoverDatabase(),
                        runtime,
                        false);

        assertThrows(
                IllegalStateException.class,
                verification::afterSingletonsInstantiated);
    }

    @Test
    void syncWorkerAssemblesOnlyWithExplicitSecurityAndTrustedTimeBindings() {
        var values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_IDENTITY_SYNC_ENABLED", "true");
        values.put("SCHOLARSENSE_CLOCK_SOURCE_REF", "config://test/campus-ntp-a");
        values.put(
                "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
                "config://test/identity-authority-profile-1-0-0");
        values.put(
                "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
                "config://test/responsibility-authority-profile-2-0-0");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);

        try (var context = new AnnotationConfigApplicationContext()) {
            DatabaseResources producer = databaseResources();
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "identity-sync-test",
                    Map.of(
                            "scholarsense.identity-sync.enabled", "true",
                            "scholarsense.identity-sync.poll-interval", "86400000",
                            "scholarsense.identity-sync.access-invalidation-consumer.verify-role-isolation",
                            "false",
                            "scholarsense.identity-sync.responsibility-v2-cutover.verify-role-isolation",
                            "false")));
            context.registerBean(RuntimeConfiguration.class, () -> runtime);
            context.registerBean(
                    JdbcTemplate.class, producer::jdbc);
            context.registerBean(
                    PlatformTransactionManager.class,
                    producer::transactions);
            context.registerBean(
                    AccessInvalidationConsumerDatabase.class,
                    IdentitySyncConfigurationTest::consumerDatabase);
            context.registerBean(
                    ResponsibilityV2CutoverDatabase.class,
                    IdentitySyncConfigurationTest::cutoverDatabase);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(
                    SimpleMeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(
                    TrustedTimeSource.class,
                    () -> IdentitySyncConfigurationTest::trustedTime);
            context.registerBean(
                    WorkloadIdentityAuthenticationPort.class,
                    () -> ignored -> "Bearer controlled-workload");
            context.registerBean(
                    IdentitySourceSignaturePort.class,
                    () -> (payload, signature, keyReference) -> true);
            context.registerBean(
                    ResponsibilityV2CutoverCommandSignaturePort.class,
                    () -> ignored -> "d".repeat(64));
            context.registerBean(
                    EnvelopeEncryptionPort.class,
                    () -> (plaintext, purpose) -> new EncryptedSecret(
                            "cipher".getBytes(StandardCharsets.UTF_8),
                            "wrapped".getBytes(StandardCharsets.UTF_8),
                            "config://test/identity-authority-inbox",
                            "k1",
                            "nonce".getBytes(StandardCharsets.UTF_8)));
            context.registerBean(
                    EnvelopeDecryptionPort.class,
                    () -> (encrypted, purpose) -> "{}".toCharArray());
            context.registerBean(
                    PseudonymizationPort.class,
                    () -> (purpose, value) ->
                            "actor_v1_k1_" + "a".repeat(64));
            context.registerBean(
                    IdentityAuditTokenPort.class,
                    () -> mock(IdentityAuditTokenPort.class));
            context.register(IdentitySyncConfiguration.class);

            context.refresh();

            assertNotNull(context.getBean(IdentitySyncScheduler.class));
            assertNotNull(context.getBean(
                    cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncWorker.class));
            assertNotNull(context.getBean(
                    cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile.class));
            assertNotNull(context.getBean(
                    cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile.class));
            assertNotNull(context.getBean(ResponsibilitySyncScheduler.class));
            assertNotNull(context.getBean(
                    ResponsibilityReconciliationScheduler.class));
            assertNotNull(context.getBean(
                    cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncWorker.class));
            assertNotNull(context.getBean(
                    cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverService.class));
        }
    }

    @Test
    void syncWorkerFailsAtBootstrapWhenControlledSecurityBindingsAreAbsent() {
        var values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_IDENTITY_SYNC_ENABLED", "true");
        values.put("SCHOLARSENSE_CLOCK_SOURCE_REF", "config://test/campus-ntp-a");
        values.put(
                "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
                "config://test/identity-authority-profile-1-0-0");
        values.put(
                "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
                "config://test/responsibility-authority-profile-2-0-0");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);

        try (var context = new AnnotationConfigApplicationContext()) {
            DatabaseResources producer = databaseResources();
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "identity-sync-fail-closed-test",
                    Map.of(
                            "scholarsense.identity-sync.enabled", "true",
                            "scholarsense.identity-sync.poll-interval", "86400000",
                            "scholarsense.identity-sync.access-invalidation-consumer.verify-role-isolation",
                            "false",
                            "scholarsense.identity-sync.responsibility-v2-cutover.verify-role-isolation",
                            "false")));
            context.registerBean(RuntimeConfiguration.class, () -> runtime);
            context.registerBean(
                    JdbcTemplate.class, producer::jdbc);
            context.registerBean(
                    PlatformTransactionManager.class,
                    producer::transactions);
            context.registerBean(
                    AccessInvalidationConsumerDatabase.class,
                    IdentitySyncConfigurationTest::consumerDatabase);
            context.registerBean(
                    ResponsibilityV2CutoverDatabase.class,
                    IdentitySyncConfigurationTest::cutoverDatabase);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(
                    TrustedTimeSource.class,
                    () -> IdentitySyncConfigurationTest::trustedTime);
            context.register(IdentitySyncConfiguration.class);

            assertThrows(
                    RuntimeException.class,
                    context::refresh);
        }
    }

    private static TrustedTime trustedTime() {
        Instant now = Instant.parse("2026-07-24T00:01:00Z");
        return new TrustedTime(
                now,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        now.minusSeconds(10),
                        now.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static AccessInvalidationConsumerDatabase consumerDatabase() {
        DataSource dataSource = mock(DataSource.class);
        return new AccessInvalidationConsumerDatabase(
                new JdbcTemplate(dataSource),
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)));
    }

    private static ResponsibilityV2CutoverDatabase cutoverDatabase() {
        DataSource dataSource = mock(DataSource.class);
        return new ResponsibilityV2CutoverDatabase(
                new JdbcTemplate(dataSource),
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)));
    }

    private static DatabaseResources databaseResources() {
        DataSource dataSource = mock(DataSource.class);
        return new DatabaseResources(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
    }

    private record DatabaseResources(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions) {}
}
