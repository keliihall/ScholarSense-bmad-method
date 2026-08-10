package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeFreshness;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeValidity;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeView;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationFenceQueryPort;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CurrentEvidenceCompositeAuthorizationAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final UUID ACCOUNT =
            UUID.fromString("019c1234-0000-7000-8000-000000000701");
    private static final UUID OTHER_ACCOUNT =
            UUID.fromString("019c1234-0000-7000-8000-000000000703");
    private static final UUID COLLEGE =
            UUID.fromString("019c1234-0000-7000-8000-000000000702");

    @Test
    void matchingCurrentCounselorAndCurrentResponsibilityAllowR1CandidateRead() {
        var service = service(
                identity("R1-COUNSELOR"),
                responsibility(ACCOUNT),
                availableEvidence(Set.of()),
                false);

        var decision = service.authorize(request());

        assertEquals(CompositeAuthorizationOutcome.ALLOW, decision.outcome());
        assertEquals(Set.of("R1-COUNSELOR"), decision.applicableRolePackages());
        assertEquals(Set.of("CURRENT_RESPONSIBILITY"), decision.scopeAnchorSummary());
        assertEquals(7, decision.objectVersion());
    }

    @Test
    void responsibilityDtoNeverAllowsWhenCounselorDoesNotMatchActor() {
        var service = service(
                identity("R1-COUNSELOR"),
                responsibility(OTHER_ACCOUNT),
                availableEvidence(Set.of()),
                false);

        var decision = service.authorize(request());

        assertEquals(CompositeAuthorizationOutcome.DENY, decision.outcome());
        assertEquals("SCOPE_NOT_PROVEN", decision.reasonCode());
    }

    @Test
    void responsibilityDtoAloneCannotGrantR7BusinessAccess() {
        var service = service(
                identity("R7-PLATFORM-OPS"),
                responsibility(ACCOUNT),
                availableEvidence(Set.of()),
                false);

        var decision = service.authorize(request());

        assertEquals(CompositeAuthorizationOutcome.DENY, decision.outcome());
        assertEquals("EXPLICIT_BUSINESS_OBJECT_DENIED", decision.reasonCode());
    }

    @Test
    void story23R6MatrixAllowsOnlyOwnedSnapshotReadAndOwnedSourceReconcile() {
        AuthorizationScopeEvidence ownedSource = new AuthorizationScopeEvidence(
                AuthorizationScopeAnchor.OWNED_SOURCE, ACCOUNT, null);
        var owned = service(
                identity("R6-DATA-OWNER"), null, availableEvidence(Set.of(ownedSource)), false);

        var snapshot = owned.authorize(request(
                "QUALITY_SNAPSHOT", "data-quality.read", 7));
        assertEquals(CompositeAuthorizationOutcome.ALLOW, snapshot.outcome());
        assertEquals(Set.of("OWNED_SOURCE"), snapshot.scopeAnchorSummary());
        assertEquals(7, snapshot.objectVersion());
        assertEquals(Map.of(
                "B", FieldVisibility.CLEAR,
                "I", FieldVisibility.HIDDEN,
                "C", FieldVisibility.HIDDEN,
                "S", FieldVisibility.HIDDEN,
                "E", FieldVisibility.CLEAR,
                "N", FieldVisibility.HIDDEN,
                "G", FieldVisibility.CLEAR,
                "T", FieldVisibility.CLEAR), snapshot.fieldProjectionSummary());

        var reconcile = owned.authorize(request(
                "SOURCE", "data-quality.reconcile", 7));
        assertEquals(CompositeAuthorizationOutcome.ALLOW, reconcile.outcome());

        var unowned = service(
                identity("R6-DATA-OWNER"), null, availableEvidence(Set.of()), false)
                .authorize(request("QUALITY_SNAPSHOT", "data-quality.read", 7));
        assertEquals(CompositeAuthorizationOutcome.DENY, unowned.outcome());
        assertEquals("SCOPE_NOT_PROVEN", unowned.reasonCode());

        var dataBatch = owned.authorize(request("DATA_BATCH", "data-quality.read", 7));
        assertEquals(CompositeAuthorizationOutcome.DENY, dataBatch.outcome());
        assertEquals("OBJECT_CLASS_UNKNOWN", dataBatch.reasonCode());

        var stale = owned.authorize(request("QUALITY_SNAPSHOT", "data-quality.read", 8));
        assertEquals(CompositeAuthorizationOutcome.DENY, stale.outcome());
        assertEquals("OBJECT_VERSION_STALE", stale.reasonCode());
    }

    @Test
    void story23R7CannotReadBusinessSnapshotsButCanRetryTechnicalJobs() {
        AuthorizationScopeEvidence ownedSource = new AuthorizationScopeEvidence(
                AuthorizationScopeAnchor.OWNED_SOURCE, ACCOUNT, null);
        var business = service(
                identity("R7-PLATFORM-OPS"), null,
                availableEvidence(Set.of(ownedSource)), false)
                .authorize(request("QUALITY_SNAPSHOT", "data-quality.read", 7));
        assertEquals(CompositeAuthorizationOutcome.DENY, business.outcome());
        assertEquals("EXPLICIT_BUSINESS_OBJECT_DENIED", business.reasonCode());

        AuthorizationScopeEvidence technical = new AuthorizationScopeEvidence(
                AuthorizationScopeAnchor.TECHNICAL_OBJECT, null, null);
        var retry = service(
                identity("R7-PLATFORM-OPS"), null,
                availableEvidence(Set.of(technical)), false)
                .authorize(request("JOB", "platform.retry", 7));
        assertEquals(CompositeAuthorizationOutcome.ALLOW, retry.outcome());
        assertEquals(Set.of("TECHNICAL_OBJECT"), retry.scopeAnchorSummary());
    }

    @Test
    void currentInvalidationFenceRemovesResponsibilityAnchorOnNextRequest() {
        var service = service(
                identity("R1-COUNSELOR"),
                responsibility(ACCOUNT),
                availableEvidence(Set.of()),
                true);

        var decision = service.authorize(request());

        assertEquals(CompositeAuthorizationOutcome.DENY, decision.outcome());
        assertEquals("ACCESS_LINEAGE_INVALIDATED", decision.reasonCode());
    }

    @Test
    void currentInvalidationFenceAlsoBlocksAnOwnerAnchorWithoutAStudentReference() {
        var ownerAnchor = new AuthorizationScopeEvidence(
                AuthorizationScopeAnchor.CURRENT_TRANSFER_ASSIGNMENT,
                ACCOUNT,
                null);
        var service = service(
                identity("R5-COLLABORATOR"),
                null,
                availableEvidence(Set.of(ownerAnchor)),
                true);
        var request = new CompositeAuthorizationRequest(
                "actor-pseudonym",
                "TRANSFER_ORDER",
                "transfer.read",
                "a".repeat(64),
                7,
                Optional.empty(),
                Optional.of("lin_transfer_case_a_000000000000000000"),
                "0".repeat(32));

        var decision = service.authorize(request);

        assertEquals(CompositeAuthorizationOutcome.DENY, decision.outcome());
        assertEquals("ACCESS_LINEAGE_INVALIDATED", decision.reasonCode());
    }

    @Test
    void unavailableOrNotInstalledOwnerEvidenceFailsClosed() {
        var service = service(
                identity("R1-COUNSELOR"),
                responsibility(ACCOUNT),
                new AuthorizationObjectEvidence(
                        AuthorizationEvidenceAvailability.UNAVAILABLE,
                        Set.of(),
                        null,
                        Set.of(),
                        null,
                        null,
                        Set.of(),
                        false,
                        0,
                        0,
                        0,
                        0,
                        Optional.empty()),
                false);

        var decision = service.authorize(request());

        assertEquals(CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE, decision.outcome());
        assertEquals("OBJECT_EVIDENCE_UNAVAILABLE", decision.reasonCode());
        assertTrue(decision.applicableRolePackages().isEmpty());
    }

    @Test
    void configuredSessionLookupMustFindAnActiveSessionBeforeAnyAuthorityLookup() {
        AtomicInteger identityLookups = new AtomicInteger();
        var service = new CurrentEvidenceCompositeAuthorizationAdapter(
                actor -> {
                    identityLookups.incrementAndGet();
                    return Optional.of(identity("R1-COUNSELOR"));
                },
                query -> responsibility(ACCOUNT),
                query -> availableEvidence(Set.of()),
                lineage -> false,
                CurrentEvidenceCompositeAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(),
                ignored -> {},
                sessionPseudonym -> Optional.empty());

        var decision = service.authorize(requestWithActor("sp_" + "a".repeat(24)));

        assertEquals(CompositeAuthorizationOutcome.DENY, decision.outcome());
        assertEquals("IDENTITY_SESSION_NOT_FOUND", decision.reasonCode());
        assertEquals(0, identityLookups.get());
    }

    @Test
    void configuredSessionLookupRebindsTheOpaqueSessionToItsCurrentActor() {
        String sessionPseudonym = "sp_" + "b".repeat(24);
        IdentitySession session = IdentitySession.authenticate(
                "session-1", sessionPseudonym, "actor-pseudonym", "binding", "origin",
                "family", "refresh", NOW.minusSeconds(30));
        var service = new CurrentEvidenceCompositeAuthorizationAdapter(
                actor -> Optional.of(identity("R1-COUNSELOR")),
                query -> responsibility(ACCOUNT),
                query -> availableEvidence(Set.of()),
                lineage -> false,
                CurrentEvidenceCompositeAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(),
                ignored -> {},
                ignored -> Optional.of(session));

        var decision = service.authorize(requestWithActor(sessionPseudonym));

        assertEquals(CompositeAuthorizationOutcome.ALLOW, decision.outcome());
    }

    @Test
    void trustedClockIamRfpAndOwnerEvidenceFailuresAreAuditedAndFailClosed() {
        AtomicInteger auditCalls = new AtomicInteger();

        var clockUnavailable = new CurrentEvidenceCompositeAuthorizationAdapter(
                actor -> Optional.of(identity("R1-COUNSELOR")),
                query -> responsibility(ACCOUNT),
                query -> availableEvidence(Set.of()),
                lineage -> false,
                () -> { throw new IllegalStateException("injected clock failure"); },
                RoleFieldPolicyCatalog.approved(),
                ignored -> auditCalls.incrementAndGet());
        assertDependencyUnavailable(clockUnavailable, "TRUSTED_CLOCK_UNAVAILABLE");

        var iamUnavailable = new CurrentEvidenceCompositeAuthorizationAdapter(
                actor -> { throw new IllegalStateException("injected IAM failure"); },
                query -> responsibility(ACCOUNT),
                query -> availableEvidence(Set.of()),
                lineage -> false,
                CurrentEvidenceCompositeAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(),
                ignored -> auditCalls.incrementAndGet());
        assertDependencyUnavailable(iamUnavailable, "IDENTITY_AUTHORITY_UNAVAILABLE");

        var rfpUnavailable = new CurrentEvidenceCompositeAuthorizationAdapter(
                actor -> Optional.of(identity("R1-COUNSELOR", "RFP-2.0.0")),
                query -> responsibility(ACCOUNT),
                query -> availableEvidence(Set.of()),
                lineage -> false,
                CurrentEvidenceCompositeAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(),
                ignored -> auditCalls.incrementAndGet());
        assertDependencyUnavailable(rfpUnavailable, "IDENTITY_AUTHORIZATION_POLICY_UNAVAILABLE");

        var ownerUnavailable = new CurrentEvidenceCompositeAuthorizationAdapter(
                actor -> Optional.of(identity("R1-COUNSELOR")),
                query -> responsibility(ACCOUNT),
                query -> { throw new IllegalStateException("injected owner failure"); },
                lineage -> false,
                CurrentEvidenceCompositeAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(),
                ignored -> auditCalls.incrementAndGet());
        assertDependencyUnavailable(ownerUnavailable, "OBJECT_EVIDENCE_UNAVAILABLE");

        assertEquals(4, auditCalls.get());
    }

    @Test
    void ownerAnchorMustBeBoundToCurrentActorOrOrganization() {
        var mismatched = new AuthorizationScopeEvidence(
                AuthorizationScopeAnchor.CURRENT_TRANSFER_ASSIGNMENT,
                OTHER_ACCOUNT,
                null);
        var service = service(
                identity("R5-COLLABORATOR"),
                null,
                availableEvidence(Set.of(mismatched)),
                false);
        var transferRequest = new CompositeAuthorizationRequest(
                "actor-pseudonym",
                "TRANSFER_ORDER",
                "transfer.read",
                "a".repeat(64),
                7,
                Optional.empty(),
                Optional.empty(),
                "0".repeat(32));

        var decision = service.authorize(transferRequest);

        assertEquals(CompositeAuthorizationOutcome.DENY, decision.outcome());
        assertEquals("SCOPE_NOT_PROVEN", decision.reasonCode());
    }

    @Test
    void auditFailurePreventsTheAuthorizationDecisionFromBeingReleased() {
        var service = new CurrentEvidenceCompositeAuthorizationAdapter(
                actor -> Optional.of(identity("R1-COUNSELOR")),
                query -> responsibility(ACCOUNT),
                query -> availableEvidence(Set.of()),
                lineage -> false,
                CurrentEvidenceCompositeAuthorizationAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(),
                ignored -> { throw new IllegalStateException("injected audit failure"); });

        assertThrows(RuntimeException.class, () -> service.authorize(request()));
    }

    private static CurrentEvidenceCompositeAuthorizationAdapter service(
            AuthoritativeIdentityContext identity,
            ResponsibilityScopeView responsibility,
            AuthorizationObjectEvidence evidence,
            boolean fenced) {
        return new CurrentEvidenceCompositeAuthorizationAdapter(
                actor -> Optional.of(identity),
                query -> responsibility,
                query -> evidence,
                lineage -> fenced,
                () -> new TrustedTime(
                        NOW, profile()),
                RoleFieldPolicyCatalog.approved(),
                ignored -> {});
    }

    private static CompositeAuthorizationRequest request() {
        return requestWithActor("actor-pseudonym");
    }

    private static CompositeAuthorizationRequest requestWithActor(String actor) {
        return new CompositeAuthorizationRequest(
                actor,
                "CANDIDATE",
                "care.read",
                "a".repeat(64),
                7,
                Optional.of("b".repeat(64)),
                Optional.of("lin_responsibility_case_a_000000000000"),
                "0".repeat(32));
    }

    private static CompositeAuthorizationRequest request(
            String objectClass, String action, long expectedObjectVersion) {
        return new CompositeAuthorizationRequest(
                "actor-pseudonym", objectClass, action, "a".repeat(64),
                expectedObjectVersion, Optional.empty(), Optional.empty(), "0".repeat(32));
    }

    private static void assertDependencyUnavailable(
            CurrentEvidenceCompositeAuthorizationAdapter service,
            String expectedReason) {
        var decision = service.authorize(request());

        assertEquals(CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE, decision.outcome());
        assertEquals(expectedReason, decision.reasonCode());
        assertTrue(decision.applicableRolePackages().isEmpty());
        assertTrue(decision.scopeAnchorSummary().isEmpty());
    }

    private static TrustedTime trustedTime() {
        return new TrustedTime(NOW, profile());
    }

    private static TimeSourceProfile profile() {
        return new TimeSourceProfile(
                "campus-ntp-a",
                "AUDIT-CLOCK-BINDING-1.0.0",
                5,
                NOW.minusSeconds(10),
                NOW.plusSeconds(10),
                "evidence://signed/clock/campus-ntp-a.json");
    }

    private static AuthoritativeIdentityContext identity(String role) {
        return identity(role, "RFP-1.0.0");
    }

    private static AuthoritativeIdentityContext identity(String role, String rfpVersion) {
        return new AuthoritativeIdentityContext(
                ACCOUNT,
                List.of(role),
                List.of(COLLEGE),
                3,
                7,
                7,
                IdentityFreshness.FRESH,
                Map.of(
                        "identitySessionPolicy", "ISP-1.0.0",
                        "roleFieldPolicy", rfpVersion,
                        "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
                        "roleMappingDigest", "sha256:" + "a".repeat(64)),
                NOW);
    }

    private static ResponsibilityScopeView responsibility(UUID counselor) {
        return new ResponsibilityScopeView(
                "b".repeat(64),
                counselor,
                COLLEGE,
                8,
                8,
                8,
                NOW.minusSeconds(60),
                null,
                ResponsibilityScopeValidity.VALID,
                ResponsibilityScopeFreshness.FRESH,
                "RESPONSIBILITY_SCOPE_CURRENT",
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                NOW);
    }

    private static AuthorizationObjectEvidence availableEvidence(
            Set<AuthorizationScopeEvidence> anchors) {
        return new AuthorizationObjectEvidence(
                AuthorizationEvidenceAvailability.AVAILABLE,
                anchors,
                "CARE",
                Set.of(),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60),
                Set.of(),
                false,
                8,
                0,
                3,
                7,
                Optional.empty());
    }
}
