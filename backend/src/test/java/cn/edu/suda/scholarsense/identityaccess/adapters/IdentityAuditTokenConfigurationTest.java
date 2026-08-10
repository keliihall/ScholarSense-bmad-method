package cn.edu.suda.scholarsense.identityaccess.adapters;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HmacIdentityAuditTokenAdapter;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class IdentityAuditTokenConfigurationTest {
    @TempDir
    Path temporary;

    @Test
    void identityEnabledWebAssemblyProvidesCallableMountedHmacTokenization()
            throws Exception {
        Path key = protectedKey(
                "identity-audit-hmac.key",
                "controlled-identity-audit-key-32".getBytes(StandardCharsets.US_ASCII));

        try (var context = context(Map.of(
                "scholarsense.identity.enabled", "true",
                "scholarsense.identity-sync.enabled", "false",
                "scholarsense.identity.audit-token-key-path", key.toString(),
                "scholarsense.identity.audit-token-key-version", "k7"))) {
            IdentityAuditTokenPort tokens = context.getBean(IdentityAuditTokenPort.class);
            AuditTokenizationPort internal = context.getBean(AuditTokenizationPort.class);

            assertInstanceOf(HmacIdentityAuditTokenAdapter.class, tokens);
            assertTrue(tokens.tokenize(AuditTokenDomain.ACTOR, "actor-123")
                    .value()
                    .startsWith("ast_v1_k7_"));
            assertTrue(internal.tokenize(AuditTokenizationDomain.OBJECT, "snapshot-123")
                    .value().startsWith("ost_v1_k7_"));
        }
    }

    @Test
    void qualityWorkerOnlyAssemblyProvidesTheSameTokenizationBoundary() throws Exception {
        Path key = protectedKey(
                "quality-worker-audit-hmac.key",
                "controlled-quality-worker-key-32".getBytes(StandardCharsets.US_ASCII));

        try (var context = context(Map.of(
                "scholarsense.identity.enabled", "false",
                "scholarsense.ingestion-quality.quality-worker-enabled", "true",
                "scholarsense.identity.audit-token-key-path", key.toString(),
                "scholarsense.identity.audit-token-key-version", "k9"))) {
            assertTrue(context.getBean(AuditTokenizationPort.class)
                    .tokenize(AuditTokenizationDomain.AGGREGATE, "snapshot-123")
                    .value().startsWith("agt_v1_k9_"));
        }
    }

    @Test
    void missingMountedKeyFailsSpringBootstrapClosed() {
        RuntimeException failure = assertBootstrapFailure(Map.of(
                "scholarsense.identity.enabled", "true",
                "scholarsense.identity-sync.enabled", "false",
                "scholarsense.identity.audit-token-key-version", "k1"));

        assertTrue(hasMessage(failure, "IDENTITY_AUDIT_TOKEN_KEY_INVALID"));
    }

    @Test
    void malformedMountedKeyFailsSpringBootstrapClosed() throws Exception {
        Path shortKey = protectedKey(
                "short-identity-audit-hmac.key",
                "too-short".getBytes(StandardCharsets.US_ASCII));
        RuntimeException failure = assertBootstrapFailure(Map.of(
                "scholarsense.identity.enabled", "true",
                "scholarsense.identity-sync.enabled", "false",
                "scholarsense.identity.audit-token-key-path", shortKey.toString(),
                "scholarsense.identity.audit-token-key-version", "k1"));

        assertTrue(hasMessage(failure, "IDENTITY_AUDIT_TOKEN_KEY_INVALID"));
    }

    private AnnotationConfigApplicationContext context(Map<String, String> properties) {
        var context = unrefreshedContext(properties);
        context.refresh();
        return context;
    }

    private RuntimeException assertBootstrapFailure(Map<String, String> properties) {
        try (var context = unrefreshedContext(properties)) {
            return assertThrows(RuntimeException.class, context::refresh);
        }
    }

    private AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, String> properties) {
        var context = new AnnotationConfigApplicationContext();
        Map<String, Object> controlled = new LinkedHashMap<>(properties);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("identity-audit-token-test", controlled));
        context.register(IdentityAuditTokenConfiguration.class);
        return context;
    }

    private Path protectedKey(String name, byte[] value) throws Exception {
        Path key = Files.write(temporary.resolve(name), value);
        try {
            Files.setPosixFilePermissions(
                    key, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignoredNonPosix) {
            // Production still enforces absolute, regular-file and no-follow checks.
        }
        return key;
    }

    private static boolean hasMessage(Throwable failure, String expected) {
        Throwable current = failure;
        while (current != null) {
            if (expected.equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
