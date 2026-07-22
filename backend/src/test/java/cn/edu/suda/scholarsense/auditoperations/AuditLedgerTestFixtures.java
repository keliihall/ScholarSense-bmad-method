package cn.edu.suda.scholarsense.auditoperations;

import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuditLedgerTestFixtures {
    public static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");
    public static final UUID AUDIT_ID = UUID.fromString("019bf18e-6c00-7000-8000-000000000001");
    public static final UUID EVENT_ID = UUID.fromString("019bf18e-6c00-7000-8000-000000000002");
    public static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    private AuditLedgerTestFixtures() {}

    public static LocalAuditFact fact() {
        return fact(2L, Map.of("identitySessionPolicy", "ISP-1.0.0"));
    }

    public static LocalAuditFact fact(Long aggregateVersion, Map<String, String> policyVersions) {
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("decision", "allow");
        authorization.put("policyVersion", "ISP-1.0.0");
        authorization.put("scopeCodes", List.of("CURRENT_SESSION"));
        authorization.put("grantSearchTokens", List.of());
        authorization.put("notApplicableReason", null);
        return new LocalAuditFact(
                AUDIT_ID,
                "LOCAL-AUDIT-FACT-1.0.0",
                "identity-access",
                ActorType.USER,
                "ast_v1_k1_" + "a".repeat(64),
                List.of(),
                authorization,
                "identity.session.view",
                "identity-session",
                "ost_v1_k1_" + "b".repeat(64),
                "accepted",
                "AUTHORIZATION_ALLOWED",
                "SESSION_CONTINUITY",
                "CURRENT_SESSION",
                NOW,
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        12,
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(30),
                        "evidence://signed/clock/observation-1"),
                "ipt_v1_k1_" + "c".repeat(64),
                "AUDIT-TOKENIZATION-1.0.0",
                "k1",
                TRACE_ID,
                "identity-session",
                "agt_v1_k1_" + "d".repeat(64),
                aggregateVersion,
                null,
                policyVersions,
                "RS-1.0.0");
    }

    public static LocalAuditOutboxRecord outbox() {
        return LocalAuditOutboxRecord.forFact(EVENT_ID, fact(), NOW);
    }
}
