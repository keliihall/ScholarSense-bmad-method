package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckRequest;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompositeAuthorizationRecheckAdapterTest {
    @Test
    void anyMutableDecisionVersionChangeMakesThePreCommitDecisionStale() {
        var prior = token(3, 8, 5, 2, 1, 7);
        var request = new CompositeAuthorizationRecheckRequest(request(), prior);
        AtomicInteger auditCalls = new AtomicInteger();
        Map<String, CompositeAuthorizationDecisionToken> changes = Map.of(
                "identityVersion", token(4, 8, 5, 2, 1, 7),
                "relationVersion", token(3, 9, 5, 2, 1, 7),
                "grantVersion", token(3, 8, 6, 2, 1, 7),
                "invalidationVersion", token(3, 8, 5, 3, 1, 7),
                "policySequence", token(3, 8, 5, 2, 2, 7),
                "objectVersion (including task/purpose/allowlist owner bump)",
                        token(3, 8, 5, 2, 1, 8));

        for (var change : changes.entrySet()) {
            var guard = new CompositeAuthorizationRecheckAdapter(
                    ignored -> decision(change.getValue()),
                    ignored -> auditCalls.incrementAndGet());

            var result = guard.recheck(request);

            assertEquals(CompositeAuthorizationRecheckOutcome.STALE,
                    result.outcome(), change.getKey());
            assertEquals("IDENTITY_AUTHORIZATION_DECISION_STALE",
                    result.reasonCode(), change.getKey());
        }
        assertEquals(changes.size(), auditCalls.get());
    }

    @Test
    void nonApprovedPolicyVersionIsRejectedBeforeItCanBecomeACommitPermit() {
        assertThrows(IllegalArgumentException.class, () -> new CompositeAuthorizationDecisionToken(
                3, 8, 5, 2, 1, 7, "RFP-2.0.0"));
    }

    @Test
    void unavailableCurrentEvidenceNeverBecomesAStaleOrCurrentCommitPermit() {
        var prior = token(3, 8, 5, 2, 1, 7);
        var guard = new CompositeAuthorizationRecheckAdapter(ignored -> new CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                "OBJECT_EVIDENCE_UNAVAILABLE",
                Set.of(), Set.of(), Map.of(), Set.of(),
                "RFP-1.0.0", 7, Instant.parse("2026-08-01T00:00:00Z"), prior), ignored -> {});

        assertEquals(CompositeAuthorizationRecheckOutcome.DEPENDENCY_UNAVAILABLE,
                guard.recheck(new CompositeAuthorizationRecheckRequest(request(), prior)).outcome());
    }

    @Test
    void recheckAuditFailurePreventsACommitPermit() {
        var prior = token(3, 8, 5, 2, 1, 7);
        var guard = new CompositeAuthorizationRecheckAdapter(
                ignored -> decision(prior),
                ignored -> { throw new IllegalStateException("injected audit failure"); });

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                guard.recheck(new CompositeAuthorizationRecheckRequest(request(), prior)));
    }

    private static CompositeAuthorizationRequest request() {
        return new CompositeAuthorizationRequest(
                "actor-pseudonym", "CANDIDATE", "care.read", "a".repeat(64), 7,
                Optional.empty(), Optional.empty(), "0".repeat(32));
    }

    private static CompositeAuthorizationDecision decision(
            CompositeAuthorizationDecisionToken token) {
        return new CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.ALLOW,
                "ROLE_SCOPE_MATCHED",
                Set.of("R1-COUNSELOR"),
                Set.of("CURRENT_RESPONSIBILITY"),
                Map.of(),
                Set.of(),
                "RFP-1.0.0",
                token.objectVersion(),
                Instant.parse("2026-08-01T00:00:00Z"),
                token);
    }

    private static CompositeAuthorizationDecisionToken token(
            long identity,
            long relation,
            long grant,
            long invalidation,
            long policy,
            long object) {
        return new CompositeAuthorizationDecisionToken(
                identity, relation, grant, invalidation, policy, object, "RFP-1.0.0");
    }
}
