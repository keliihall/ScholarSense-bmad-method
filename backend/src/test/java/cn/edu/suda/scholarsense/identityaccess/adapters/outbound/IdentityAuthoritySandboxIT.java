package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationEffectiveContext;
import cn.edu.suda.scholarsense.identityaccess.application.AuthorizationFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityLease;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceHealth;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJob;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJobStatus;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncWorker;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.TargetRole;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class IdentityAuthoritySandboxIT {
    private static final String DIGEST =
            "sha256:f09768f88cd6a595791ec6591e65758b85fd8402585c8ffaa053446214895e29";
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "identity-authority",
            "sandbox-0",
            "identity-org");
    private static final Instant NOW = Instant.now();

    @Test
    void consumesControlledProviderMatrixAndFailsClosed() {
        URI endpoint = URI.create(required("identity.sandbox.endpoint"));
        String token = required("identity.sandbox.token");
        String signatureKey = required("identity.sandbox.signatureKey");
        var adapter = adapter(endpoint, token, signatureKey);

        var added = fetch(adapter, 0, trace(1));
        assertEquals(AuthoritativeStatus.ACTIVE, added.accounts().getFirst().status());

        var changed = fetch(adapter, 1, trace(2));
        assertNotEquals(
                added.accounts().getFirst().subjectBindingToken(),
                changed.accounts().getFirst().subjectBindingToken());

        var deactivated = fetch(adapter, 2, trace(3));
        assertEquals(
                AuthoritativeStatus.INACTIVE,
                deactivated.accounts().getFirst().status());

        var multiRole = fetch(adapter, 3, trace(4));
        assertEquals(
                Set.of(TargetRole.R1_COUNSELOR, TargetRole.R6_DATA_OWNER),
                multiRole.roleBindings().stream()
                        .map(value -> value.targetRole())
                        .collect(java.util.stream.Collectors.toSet()));

        var removed = fetch(adapter, 4, trace(5));
        assertTrue(removed.roleBindings().stream().anyMatch(value ->
                value.status() == AuthoritativeStatus.INACTIVE
                        && value.effectiveInterval().effectiveTo() != null));

        var organizationAdded = fetch(adapter, 5, trace(6));
        assertEquals(3, organizationAdded.organizations().size());

        var renamed = fetch(adapter, 6, trace(7));
        assertTrue(renamed.organizations().stream()
                .anyMatch(value -> value.displayName().contains("更名")));

        var reparented = fetch(adapter, 7, trace(8));
        String schoolDigest = digest("external", KEY.sourceId() + "\0ORG-SCHOOL");
        assertTrue(reparented.organizations().stream().anyMatch(value ->
                value.displayName().startsWith("计算机科学与技术系")
                        && schoolDigest.equals(value.parentExternalRefDigest())));

        var organizationDeactivated = fetch(adapter, 8, trace(9));
        assertTrue(organizationDeactivated.organizations().stream().anyMatch(value ->
                "苏州大学".equals(value.displayName())
                        && value.status() == AuthoritativeStatus.INACTIVE));

        var employmentEnded = fetch(adapter, 9, trace(10));
        assertNotNull(
                employmentEnded.roleBindings().getFirst().effectiveInterval().effectiveTo());

        IdentitySyncException unknownRole = assertThrows(
                IdentitySyncException.class,
                () -> adapter.fetch(KEY, 10, trace(11)));
        assertEquals("IDENTITY_ROLE_UNKNOWN", unknownRole.code());

        IdentitySyncException conflictingSubject = assertThrows(
                IdentitySyncException.class,
                () -> adapter.fetch(KEY, 11, trace(12)));
        assertEquals("IDENTITY_SUBJECT_BINDING_CONFLICT", conflictingSubject.code());

        IdentitySyncException authentication = assertThrows(
                IdentitySyncException.class,
                () -> adapter(endpoint, "invalid-token", signatureKey)
                        .fetch(KEY, 0, trace(13)));
        assertEquals("IDENTITY_SOURCE_AUTHENTICATION_FAILED", authentication.code());

        var invalidSignature = adapter.fetch(KEY, 12, trace(14));
        assertFalse(invalidSignature.signatureVerified());
        System.out.println(
                "IDENTITY_AUTHORITY_SANDBOX_CONSUMER: PASS (14 requests, 14 scenarios)");
    }

    @Test
    void sameTraceRunsThroughWorkerPostgreSqlAndCurrentAuthorizationReadBack() {
        String pgUrl = System.getProperty("scholarsense.audit.pg.url");
        Assumptions.assumeTrue(
                pgUrl != null && !pgUrl.isBlank(),
                "combined sandbox/PostgreSQL evidence is run by the controlled runner");
        URI endpoint = URI.create(required("identity.sandbox.endpoint"));
        String token = required("identity.sandbox.token");
        String signatureKey = required("identity.sandbox.signatureKey");
        String traceId = "fedcba9876543210fedcba9876543210";
        Instant now = Instant.now();
        DataSource dataSource = dataSource(pgUrl);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                truncate table
                  identity_access.ia_identity_slo_compensation,
                  identity_access.ia_identity_slo_evidence,
                  identity_access.ia_identity_reconciliation_sample,
                  identity_access.ia_identity_replay_request,
                  identity_access.ia_identity_rejected_record,
                  identity_access.ia_authoritative_role_current,
                  identity_access.ia_authoritative_organization_current,
                  identity_access.ia_authoritative_subject_binding_history,
                  identity_access.ia_authoritative_account_current,
                  identity_access.ia_identity_source_fact,
                  identity_access.ia_identity_source_archive,
                  identity_access.ia_identity_source_inbox,
                  identity_access.ia_identity_sync_lease,
                  identity_access.ia_identity_sync_attempt,
                  identity_access.ia_identity_sync_job,
                  identity_access.ia_identity_sync_checkpoint,
                  identity_access.ia_local_audit_outbox,
                  identity_access.ia_local_audit_fact
                cascade
                """);
        var manager = new DataSourceTransactionManager(dataSource);
        var transactionTemplate = new TransactionTemplate(manager);
        var transactions = new JdbcIdentitySyncTransactionAdapter(transactionTemplate);
        var jobs = new JdbcIdentitySyncJobAdapter(jdbc, transactionTemplate);
        var repository = new JdbcIdentitySyncRepository(jdbc);
        var replay = new JdbcIdentityReplayAdapter(jdbc, IdentityAuthoritySandboxIT::trustedNow);
        var localAudit = new JdbcIdentityAuditAdapter(
                jdbc, transactionTemplate, new ObjectMapper());
        var audit = new IdentitySyncAuditAdapter(
                new IdentityAuditFactFactory(
                        IdentityAuthoritySandboxIT::trustedNow,
                        new HmacIdentityAuditTokenAdapter(
                                new SecretKeySpec(new byte[32], "HmacSHA256"), "k1")),
                localAudit);
        var contexts = new JdbcAuthoritativeIdentityContextAdapter(
                jdbc, Clock.systemUTC(), Duration.ofMinutes(15));
        var sync = new IdentitySyncService(
                repository,
                replay,
                transactions,
                audit,
                ignored -> {},
                subject -> contexts.findCurrent(subject).map(context ->
                        new AuthorizationEffectiveContext(
                                context.accountId(),
                                context.sourceVersion(),
                                context.sourceWatermark(),
                                AuthorizationFreshness.FRESH)),
                repository,
                IdentityAuthoritySandboxIT::trustedNow);
        var source = adapter(endpoint, token, signatureKey);
        var worker = new IdentitySyncWorker(
                jobs,
                source,
                sync::process,
                audit,
                ignored -> {},
                transactions,
                IdentityAuthoritySandboxIT::trustedNow,
                replay,
                repository);
        var job = new IdentitySyncJob(
                UUID.fromString(UuidV7.generate(now)),
                KEY,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                now,
                null,
                0,
                now,
                3,
                0,
                null,
                traceId);
        jobs.enqueue(job);

        var run = worker.runNext("sandbox-worker").orElseThrow();

        assertEquals(IdentitySyncJobStatus.SUCCEEDED, run.status());
        String actor = jdbc.queryForObject(
                "select subject_binding_token "
                        + "from identity_access.ia_authoritative_account_current",
                String.class);
        var current = contexts.findCurrent(actor).orElseThrow();
        assertEquals(1, current.sourceVersion());
        assertEquals(1, current.sourceWatermark());
        for (String table : Set.of(
                "ia_identity_sync_job",
                "ia_identity_sync_checkpoint",
                "ia_identity_source_inbox",
                "ia_local_audit_fact")) {
            assertEquals(traceId, jdbc.queryForObject(
                    "select trace_id from identity_access." + table + " limit 1",
                    String.class));
        }
        assertEquals(4L, jdbc.queryForObject(
                "select count(*) from identity_access.ia_identity_slo_evidence",
                Long.class));
        System.out.println(
                "IDENTITY_AUTHORITY_SANDBOX_E2E: PASS "
                        + "(worker + PostgreSQL 18.4 + current read-back, one trace)");
    }

    private static cn.edu.suda.scholarsense.identityaccess.application.NormalizedIdentityBatch
            fetch(
                    HttpIdentityAuthoritySourceAdapter adapter,
                    long afterWatermark,
                    String traceId) {
        var batch = adapter.fetch(KEY, afterWatermark, traceId);
        assertWithinSlo(batch.sourceVisibleAt());
        return batch;
    }

    private static HttpIdentityAuthoritySourceAdapter adapter(
            URI endpoint, String token, String signatureKey) {
        var profile = new IdentityAuthorityRuntimeProfile(
                "IDENTITY-AUTHORITY-PROFILE-1.0.0",
                KEY.sourceId(),
                KEY.feedId(),
                KEY.partitionId(),
                KEY.consumerProjection(),
                endpoint,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                "account://test/identity-sync-worker",
                "secret://test/identity-authority-signature",
                "config://test/identity-authority-inbox",
                "config://test/identity-role-mapping-1-0-0",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                DIGEST,
                Duration.ofSeconds(30),
                5,
                false);
        return new HttpIdentityAuthoritySourceAdapter(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                profile,
                ignored -> "Bearer " + token,
                (payload, signature, ignored) ->
                        constantTimeEquals(
                                hmac(signatureKey, unsigned(payload)), signature),
                (plaintext, ignored) -> new EncryptedSecret(
                        digestBytes(new String(plaintext).getBytes(StandardCharsets.UTF_8)),
                        digestBytes("sandbox-wrapped-key".getBytes(StandardCharsets.UTF_8)),
                        profile.inboxEncryptionKeyReference(),
                        "k1",
                        digestBytes("sandbox-nonce".getBytes(StandardCharsets.UTF_8))),
                (purpose, raw) -> {
                    String prefix = "identity-actor".equals(purpose) ? "actor" : "external";
                    return prefix + "_v1_k1_" + digest(prefix, raw);
                },
                IdentityAuthoritySandboxIT::trustedNow,
                true);
    }

    private static byte[] unsigned(byte[] payload) {
        String value = new String(payload, StandardCharsets.UTF_8);
        return value.replaceFirst(
                        "\"signatureDigest\":\"sha256:[0-9a-f]{64}\"",
                        "\"signatureDigest\":\"sha256:" + "0".repeat(64) + "\"")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String hmac(String key, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digestBytes(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String digest(String prefix, String value) {
        return HexFormat.of().formatHex(digestBytes(
                value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertWithinSlo(Instant sourceVisibleAt) {
        assertTrue(!NOW.isAfter(sourceVisibleAt.plus(Duration.ofMinutes(15))));
    }

    private static String trace(int value) {
        return "%032x".formatted(value);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required");
        }
        return value;
    }

    private static TrustedTime trustedNow() {
        Instant current = Instant.now();
        return new TrustedTime(
                current,
                new TimeSourceProfile(
                        "controlled-sandbox-clock",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        current.minusSeconds(10),
                        current.plusSeconds(50),
                        "evidence://signed/clock/controlled-sandbox.json"));
    }

    private static DataSource dataSource(String url) {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(url);
        source.setUsername(required("scholarsense.audit.pg.user"));
        return source;
    }
}
