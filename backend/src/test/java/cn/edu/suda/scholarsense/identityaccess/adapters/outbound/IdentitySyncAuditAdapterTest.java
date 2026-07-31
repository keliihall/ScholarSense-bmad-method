package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditRecord;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncAuditEvent;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class IdentitySyncAuditAdapterTest {
    @Test
    void emitsServiceActorAuditAndNormalizesRetryToPublishedRejectedOutcome() {
        Instant now = Instant.parse("2026-07-24T00:02:00Z");
        var records = new ArrayList<IdentityAuditRecord>();
        Instant recordedAt = now.plusSeconds(30);
        var facts = new IdentityAuditFactFactory(
                () -> new TrustedTime(recordedAt, new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        now.minusSeconds(10),
                        recordedAt.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json")),
                new HmacIdentityAuditTokenAdapter(
                        new SecretKeySpec(new byte[32], "HmacSHA256"), "k1"));
        var adapter = new IdentitySyncAuditAdapter(facts, records::add);

        adapter.append(new IdentitySyncAuditEvent(
                "identity.sync.failed",
                "retry_scheduled",
                "IDENTITY_SOURCE_DEPENDENCY_UNAVAILABLE",
                UUID.fromString("019c1234-0000-7000-8000-000000000401"),
                1,
                10,
                0,
                41,
                0,
                "0123456789abcdef0123456789abcdef",
                now,
                Map.of(
                        "identitySessionPolicy", "ISP-1.0.0",
                        "roleFieldPolicy", "RFP-1.0.0",
                        "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
                        "retentionSchedule", "RS-1.0.0")));

        var fact = records.getFirst().fact();
        assertEquals(ActorType.SERVICE, fact.actorType());
        assertEquals("identity.sync.failed", fact.action());
        assertEquals("rejected", fact.outcome());
        assertEquals("IDENTITY_ORG", fact.projectionScope());
        assertEquals(now, fact.occurredAt());
        assertEquals(recordedAt, fact.recordedAt());
        assertNotEquals(fact.objectSearchToken(), fact.aggregateIdSearchToken());
        assertNotNull(fact.idempotencyKeyDigest());
        assertFalse(records.getFirst().toString().contains("identity-sync-worker"));
    }

    @Test
    void mapsResponsibilityExceptionToTheVersionedVocabularyAndObject() {
        Instant now = Instant.parse("2026-07-24T00:02:00Z");
        UUID jobId = UUID.fromString(
                "019c1234-0000-7000-8000-000000000402");
        UUID exceptionId = UUID.fromString(
                "019c1234-0000-7000-8000-000000000403");
        var records = new ArrayList<IdentityAuditRecord>();
        var facts = new IdentityAuditFactFactory(
                () -> new TrustedTime(now, new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        now.minusSeconds(10),
                        now.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json")),
                new HmacIdentityAuditTokenAdapter(
                        new SecretKeySpec(
                                new byte[32], "HmacSHA256"),
                        "k1"));
        var adapter = new IdentitySyncAuditAdapter(
                facts, records::add);

        adapter.append(new IdentitySyncAuditEvent(
                "responsibility.exception.opened",
                "accepted",
                "RESPONSIBILITY_ZERO_RECIPIENT",
                jobId,
                2,
                19,
                7,
                42,
                3,
                "0123456789abcdef0123456789abcdef",
                now,
                Map.of(
                        "identitySessionPolicy", "ISP-1.0.0",
                        "roleFieldPolicy", "RFP-1.0.0",
                        "responsibilityContract",
                        "RESPONSIBILITY-AUTHORITY-1.0.0",
                        "retentionSchedule", "RS-1.0.0"),
                exceptionId));

        var fact = records.getFirst().fact();
        assertEquals(
                "responsibility.exception.opened",
                fact.action());
        assertEquals(
                "RESPONSIBILITY_EXCEPTION_MANAGEMENT",
                fact.purpose());
        assertEquals("RESPONSIBILITY", fact.projectionScope());
        assertEquals(
                "responsibility-exception",
                fact.objectType());
        assertEquals(
                "responsibility-exception",
                fact.aggregateType());
        assertEquals(3L, fact.aggregateVersion());
        assertNotEquals(
                fact.objectSearchToken(),
                fact.aggregateIdSearchToken());
    }
}
