package cn.edu.suda.scholarsense.identityaccess.adapters;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import cn.edu.suda.scholarsense.identityaccess.adapters.inbound.IdentitySyncScheduler;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeEncryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.EnvelopeDecryptionPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
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
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class IdentitySyncConfigurationTest {

    @Test
    void syncWorkerAssemblesOnlyWithExplicitSecurityAndTrustedTimeBindings() {
        var values = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        values.put("SCHOLARSENSE_IDENTITY_SYNC_ENABLED", "true");
        values.put("SCHOLARSENSE_CLOCK_SOURCE_REF", "config://test/campus-ntp-a");
        values.put(
                "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
                "config://test/identity-authority-profile-1-0-0");
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "identity-sync-test",
                    Map.of(
                            "scholarsense.identity-sync.enabled", "true",
                            "scholarsense.identity-sync.poll-interval", "86400000")));
            context.registerBean(RuntimeConfiguration.class, () -> runtime);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));
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
        RuntimeConfiguration runtime = RuntimeConfiguration.from(values);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "identity-sync-fail-closed-test",
                    Map.of(
                            "scholarsense.identity-sync.enabled", "true",
                            "scholarsense.identity-sync.poll-interval", "86400000")));
            context.registerBean(RuntimeConfiguration.class, () -> runtime);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));
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
}
