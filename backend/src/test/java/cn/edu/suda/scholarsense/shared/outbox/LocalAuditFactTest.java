package cn.edu.suda.scholarsense.shared.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalAuditFactTest {
    @Test
    void freezesCollectionsAndRejectsDeliveryStateOrFreeTextFromTheFactModel() {
        var roles = new java.util.ArrayList<>(List.of("R1"));
        var policies = new java.util.HashMap<>(Map.of("identitySessionPolicy", "ISP-1.0.0"));

        LocalAuditFact fact = fact(roles, policies);
        roles.add("R2");
        policies.put("freeText", "must-not-leak");

        assertEquals(List.of("R1"), fact.roleIds());
        assertEquals(Map.of("identitySessionPolicy", "ISP-1.0.0"), fact.policyVersions());
        assertThrows(UnsupportedOperationException.class, () -> fact.roleIds().add("R3"));
        assertEquals(27, LocalAuditFact.class.getRecordComponents().length);
    }

    @Test
    void outboxIdentityMustBindExactlyOneFact() {
        LocalAuditFact fact = fact(List.of(), Map.of());
        UUID eventId = UUID.fromString("019bf18e-6c00-7000-8000-000000000002");
        LocalAuditOutboxRecord outbox = LocalAuditOutboxRecord.forFact(eventId, fact, fact.recordedAt());

        assertEquals(fact.auditId(), outbox.auditId());
        assertEquals("identity-access.local-audit-fact.recorded.v1", outbox.eventType());
        assertEquals("LOCAL-AUDIT-OUTBOX-1.0.0", outbox.schemaVersion());
        assertEquals(fact, outbox.fact());
    }

    @Test
    void rejectsNonUuidV7FactAndOutboxIdentifiers() {
        LocalAuditFact valid = fact(List.of(), Map.of());
        UUID uuidV4 = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        assertThrows(IllegalArgumentException.class, () -> new LocalAuditFact(
                uuidV4, valid.schemaVersion(), valid.producerModule(), valid.actorType(),
                valid.actorSearchToken(), valid.roleIds(), valid.authorizationContext(), valid.action(),
                valid.objectType(), valid.objectSearchToken(), valid.outcome(), valid.reasonCode(),
                valid.purpose(), valid.projectionScope(), valid.occurredAt(), valid.recordedAt(),
                valid.timeSourceProfile(), valid.sourceIpSearchToken(), valid.tokenizationProfileVersion(),
                valid.keyVersion(), valid.traceId(), valid.aggregateType(), valid.aggregateIdSearchToken(),
                valid.aggregateVersion(), valid.idempotencyKeyDigest(), valid.policyVersions(),
                valid.retentionScheduleVersion()));
        assertThrows(IllegalArgumentException.class, () -> new LocalAuditOutboxRecord(
                uuidV4, valid.auditId(), "identity-access.local-audit-fact.recorded.v1",
                "LOCAL-AUDIT-OUTBOX-1.0.0", "identity-access", valid.recordedAt(), valid));
    }

    @Test
    void commonFactRejectsIncompleteAuthorizationFieldsWithoutOwningBusinessTypes() {
        LocalAuditFact valid = fact(List.of(), Map.of());
        Map<String, Object> incomplete = new java.util.LinkedHashMap<>(valid.authorizationContext());
        incomplete.remove("decision");

        assertThrows(IllegalArgumentException.class, () -> new LocalAuditFact(
                valid.auditId(), valid.schemaVersion(), valid.producerModule(), valid.actorType(),
                valid.actorSearchToken(), valid.roleIds(), incomplete, valid.action(), valid.objectType(),
                valid.objectSearchToken(), valid.outcome(), valid.reasonCode(), valid.purpose(),
                valid.projectionScope(), valid.occurredAt(), valid.recordedAt(), valid.timeSourceProfile(),
                valid.sourceIpSearchToken(), valid.tokenizationProfileVersion(), valid.keyVersion(),
                valid.traceId(), valid.aggregateType(), valid.aggregateIdSearchToken(),
                valid.aggregateVersion(), valid.idempotencyKeyDigest(), valid.policyVersions(),
                valid.retentionScheduleVersion()));
    }

    @Test
    void notApplicableAuthorizationCannotCarryPolicyScopesOrGrants() {
        assertThrows(IllegalArgumentException.class, () -> new
                cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditAuthorizationContext(
                        "not-applicable", "ISP-1.0.0", List.of(), List.of(), "PRE_AUTHENTICATION"));
        assertThrows(IllegalArgumentException.class, () -> new
                cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditAuthorizationContext(
                        "not-applicable", null, List.of("CURRENT_SESSION"), List.of(),
                        "PRE_AUTHENTICATION"));
        assertThrows(IllegalArgumentException.class, () -> new
                cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditAuthorizationContext(
                        "not-applicable", null, List.of(),
                        List.of("gst_v1_k1_" + "a".repeat(64)), "PRE_AUTHENTICATION"));
    }

    private static LocalAuditFact fact(List<String> roles, Map<String, String> policies) {
        Instant time = Instant.parse("2026-07-20T02:00:00Z");
        return new LocalAuditFact(
                UUID.fromString("019bf18e-6c00-7000-8000-000000000001"),
                "LOCAL-AUDIT-FACT-1.0.0",
                "identity-access",
                ActorType.USER,
                "ast_v1_k1_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                roles,
                authorization(),
                "identity.session.view",
                "identity-session",
                "ost_v1_k1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "accepted",
                "AUTHORIZATION_ALLOWED",
                "SESSION_CONTINUITY",
                "CURRENT_SESSION",
                time,
                time,
                new TimeSourceProfile(
                        "campus-ntp-a", "AUDIT-CLOCK-BINDING-1.0.0", 12,
                        time.minusSeconds(30), time.plusSeconds(30),
                        "evidence://signed/clock/observation-1"),
                "ipt_v1_k1_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "AUDIT-TOKENIZATION-1.0.0",
                "k1",
                "0123456789abcdef0123456789abcdef",
                "identity-session",
                "agt_v1_k1_dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                2L,
                null,
                policies,
                "RS-1.0.0");
    }

    private static Map<String, Object> authorization() {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("decision", "allow");
        value.put("policyVersion", "ISP-1.0.0");
        value.put("scopeCodes", List.of("CURRENT_SESSION"));
        value.put("grantSearchTokens", List.of());
        value.put("notApplicableReason", null);
        return value;
    }
}
