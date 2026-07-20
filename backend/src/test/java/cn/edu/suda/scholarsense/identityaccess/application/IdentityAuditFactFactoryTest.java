package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HmacIdentityAuditTokenAdapter;
import org.junit.jupiter.api.Test;

class IdentityAuditFactFactoryTest {
    private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");

    @Test
    void createsCompleteFactAndOutboxWithoutRawActorObjectIpOrIdempotencyKey() {
        var factory = new IdentityAuditFactFactory(
                () -> new TrustedTime(NOW, profile()), IdentityAuditFactFactoryTest::tokenize);
        var request = new IdentityAuditRequest(
                ActorType.USER,
                "student-account-raw",
                List.of(),
                new IdentityAuditAuthorizationContext(
                        "allow", "ISP-1.0.0", List.of("CURRENT_SESSION"), List.of(), null),
                IdentityAuditAction.SESSION_VIEW,
                "accepted",
                "AUTHORIZATION_ALLOWED",
                "identity-session",
                "session-cookie-raw",
                "SESSION_CONTINUITY",
                "CURRENT_SESSION",
                "192.0.2.10",
                "0123456789abcdef0123456789abcdef",
                "identity-session",
                "session-cookie-raw",
                2L,
                "idempotency-raw-key",
                Map.of("identitySessionPolicy", "ISP-1.0.0"));

        IdentityAuditRecord record = factory.create(request);
        String rendered = record.toString();

        assertEquals(record.fact().auditId(), record.outbox().auditId());
        assertEquals("identity.session.view", record.fact().action());
        assertEquals("LOCAL-AUDIT-FACT-1.0.0", record.fact().schemaVersion());
        assertEquals("AUDIT-TOKENIZATION-1.0.0", record.fact().tokenizationProfileVersion());
        assertEquals("k1", record.fact().keyVersion());
        assertFalse(rendered.contains("student-account-raw"));
        assertFalse(rendered.contains("session-cookie-raw"));
        assertFalse(rendered.contains("192.0.2.10"));
        assertFalse(rendered.contains("idempotency-raw-key"));
    }

    @Test
    void stableTokensSupportAuthorizedLookupButRemainDomainSeparated() {
        AuditSearchToken actorFirst = tokenize(AuditTokenDomain.ACTOR, "normalized-user");
        AuditSearchToken actorRetry = tokenize(AuditTokenDomain.ACTOR, "normalized-user");
        AuditSearchToken object = tokenize(AuditTokenDomain.OBJECT, "normalized-user");

        assertEquals(actorFirst, actorRetry);
        assertNotEquals(actorFirst.value(), object.value());
        assertEquals("k1", actorFirst.keyVersion());
    }

    @Test
    void zeroTokenAnonymousFactStillCarriesExplicitTokenMetadataAndSeparateRecordedTime() {
        AtomicInteger calls = new AtomicInteger();
        var factory = new IdentityAuditFactFactory(
                () -> new TrustedTime(NOW.plusMillis(calls.getAndIncrement()), profile()),
                new HmacIdentityAuditTokenAdapter(
                        new SecretKeySpec(new byte[32], "HmacSHA256"), "k9"));
        var request = new IdentityAuditRequest(
                ActorType.ANONYMOUS, null, List.of(),
                new IdentityAuditAuthorizationContext(
                        "not-applicable", null, List.of(), List.of(), "PRE_AUTHENTICATION"),
                IdentityAuditAction.SESSION_LOGOUT, "rejected", "IDENTITY_SESSION_REQUIRED",
                null, null, "SESSION_CONTROL", null, null,
                "0123456789abcdef0123456789abcdef", null, null, null,
                "idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ",
                Map.of("identitySessionPolicy", "ISP-1.0.0"));

        IdentityAuditRecord record = factory.create(request);

        assertEquals("AUDIT-TOKENIZATION-1.0.0", record.fact().tokenizationProfileVersion());
        assertEquals("k9", record.fact().keyVersion());
        assertTrue(record.fact().recordedAt().isAfter(record.fact().occurredAt()));
    }

    @Test
    void occurredAtRetainsTheClockProfileThatActuallyProvedItsFreshness() {
        AtomicInteger calls = new AtomicInteger();
        TimeSourceProfile occurredProfile = new TimeSourceProfile(
                "campus-ntp-a", "AUDIT-CLOCK-BINDING-1.0.0", 12,
                NOW.minusSeconds(30), NOW.plusSeconds(30),
                "evidence://signed/clock/observation-a");
        TimeSourceProfile recordedProfile = new TimeSourceProfile(
                "campus-ntp-b", "AUDIT-CLOCK-BINDING-1.0.0", 8,
                NOW.plusMillis(1), NOW.plusSeconds(31),
                "evidence://signed/clock/observation-b");
        var factory = new IdentityAuditFactFactory(
                () -> calls.getAndIncrement() == 0
                        ? new TrustedTime(NOW, occurredProfile)
                        : new TrustedTime(NOW.plusMillis(1), recordedProfile),
                IdentityAuditFactFactoryTest::tokenize);
        var request = new IdentityAuditRequest(
                ActorType.ANONYMOUS, null, List.of(),
                new IdentityAuditAuthorizationContext(
                        "not-applicable", null, List.of(), List.of(), "PRE_AUTHENTICATION"),
                IdentityAuditAction.SESSION_LOGOUT, "rejected", "IDENTITY_SESSION_REQUIRED",
                null, null, "SESSION_CONTROL", null, null,
                "0123456789abcdef0123456789abcdef", null, null, null, null,
                Map.of("identitySessionPolicy", "ISP-1.0.0"));

        IdentityAuditRecord record = factory.create(request);

        assertEquals(occurredProfile, record.fact().timeSourceProfile());
        assertEquals(NOW.plusMillis(1), record.fact().recordedAt());
    }

    private static AuditSearchToken tokenize(AuditTokenDomain domain, String normalized) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (domain.wireName() + "\0" + normalized).getBytes(StandardCharsets.UTF_8));
            return new AuditSearchToken(
                    domain.prefix() + "_v1_k1_" + HexFormat.of().formatHex(digest),
                    "AUDIT-TOKENIZATION-1.0.0",
                    "k1");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static TimeSourceProfile profile() {
        return new TimeSourceProfile(
                "campus-ntp-a", "AUDIT-CLOCK-BINDING-1.0.0", 12,
                NOW.minusSeconds(30), NOW.plusSeconds(30),
                "evidence://signed/clock/observation-1");
    }
}
