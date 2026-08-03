package cn.edu.suda.scholarsense.identityaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompositeAuthorizationEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final AuthorizationEvidenceVersions VERSIONS =
            new AuthorizationEvidenceVersions(11, 21, 31, 3, 1);
    private final CompositeAuthorizationEvaluator evaluator = new CompositeAuthorizationEvaluator();
    private final RoleFieldPolicyCatalog policy = RoleFieldPolicyCatalog.approved();

    @Test
    void sevenRoleRepresentativeOracleMatchesApprovedPolicy() {
        assertAllow(request(RolePackage.R1, ObjectClass.CLUE, "care.read", ScopeAnchor.CURRENT_RESPONSIBILITY));
        assertAllow(request(RolePackage.R2, ObjectClass.AGGREGATE_REPORT, "aggregate.read", ScopeAnchor.COLLEGE_AGGREGATE));
        assertAllow(request(RolePackage.R3, ObjectClass.RULE, "governance.read", ScopeAnchor.SCHOOL_GOVERNANCE));
        assertAllow(request(RolePackage.R4, ObjectClass.AGGREGATE_REPORT, "aggregate.read", ScopeAnchor.SCHOOL_AGGREGATE));
        assertAllow(transferRequest(RolePackage.R5, "transfer.process"));
        assertAllow(request(RolePackage.R6, ObjectClass.QUALITY_SNAPSHOT, "data-quality.read", ScopeAnchor.OWNED_SOURCE));
        assertAllow(request(RolePackage.R7, ObjectClass.JOB, "platform.read", ScopeAnchor.TECHNICAL_OBJECT));

        assertDeny(request(RolePackage.R2, ObjectClass.CLUE, "care.read", ScopeAnchor.CURRENT_RESPONSIBILITY),
                "EXPLICIT_WORKITEM_REQUIRED");
        assertDeny(request(RolePackage.R4, ObjectClass.CLUE, "care.read", ScopeAnchor.CURRENT_RESPONSIBILITY),
                "EXPLICIT_INDIVIDUAL_DRILLDOWN_DENIED");
        assertDeny(request(RolePackage.R7, ObjectClass.CLUE, "care.read", ScopeAnchor.CURRENT_RESPONSIBILITY),
                "EXPLICIT_BUSINESS_OBJECT_DENIED");
    }

    @Test
    void alternativeAnchorsDoNotRequireAnIntersection() {
        var workItem = request(RolePackage.R2, ObjectClass.CLUE, "care.read", ScopeAnchor.GOVERNANCE_WORK_ITEM);
        assertAllow(workItem);

        var both = copy(workItem, EnumSet.of(
                ScopeAnchor.GOVERNANCE_WORK_ITEM,
                ScopeAnchor.CURRENT_RESPONSIBILITY));
        assertAllow(both);
    }

    @Test
    void onlyApplicableRolesParticipateInStrictestFieldMerge() {
        var r1r2 = request(
                Set.of(RolePackage.R1, RolePackage.R2),
                ObjectClass.CLUE,
                "care.read",
                EnumSet.of(ScopeAnchor.CURRENT_RESPONSIBILITY, ScopeAnchor.GOVERNANCE_WORK_ITEM));
        var strict = evaluator.evaluate(r1r2, policy);

        assertEquals(CompositeAuthorizationOutcome.ALLOW, strict.outcome());
        assertEquals(Set.of(RolePackage.R1, RolePackage.R2), strict.applicableRolePackages());
        assertEquals(Visibility.MASKED, strict.fieldProjectionSummary().get(FieldClass.IDENTITY));
        assertEquals(Visibility.HIDDEN, strict.fieldProjectionSummary().get(FieldClass.CONTACT));

        var r1r7 = request(
                Set.of(RolePackage.R1, RolePackage.R7),
                ObjectClass.CLUE,
                "care.read",
                EnumSet.of(ScopeAnchor.CURRENT_RESPONSIBILITY));
        var irrelevant = evaluator.evaluate(r1r7, policy);
        assertEquals(Set.of(RolePackage.R1), irrelevant.applicableRolePackages());
        assertEquals(Visibility.CLEAR, irrelevant.fieldProjectionSummary().get(FieldClass.IDENTITY));
        assertEquals(Visibility.MASKED, irrelevant.fieldProjectionSummary().get(FieldClass.TECHNICAL));
    }

    @Test
    void unknownOrUnexpandedActionAndUnavailableDependenciesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> ActionId.of("care.read/candidate.review"));
        assertDeny(request(RolePackage.R1, ObjectClass.CLUE, "care.delete", ScopeAnchor.CURRENT_RESPONSIBILITY),
                "ACTION_UNKNOWN");

        var unavailable = evaluator.evaluate(
                request(RolePackage.R1, ObjectClass.CLUE, "care.read", ScopeAnchor.CURRENT_RESPONSIBILITY),
                RoleFieldPolicyCatalog.unavailable("POLICY_DIGEST_MISMATCH"));
        assertEquals(CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE, unavailable.outcome());
        assertEquals("POLICY_DIGEST_MISMATCH", unavailable.reasonCode());

        var untrustedClock = request(RolePackage.R1, ObjectClass.CLUE, "care.read", ScopeAnchor.CURRENT_RESPONSIBILITY)
                .withTrustedClock(false);
        assertEquals(CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                evaluator.evaluate(untrustedClock, policy).outcome());
    }

    @Test
    void applicableDenyAndSeparationOfDutyOverrideAllow() {
        var conflict = request(RolePackage.R7, ObjectClass.CONFIG, "role-binding.apply-approved", ScopeAnchor.TECHNICAL_OBJECT)
                .withSeparationOfDutyConflict(true);
        assertDeny(conflict, "SEPARATION_OF_DUTY_CONFLICT");
    }

    @Test
    void highRiskActionsRequireTheExactMappedApproval() {
        var publish = request(RolePackage.R3, ObjectClass.RULE, "governance.publish", ScopeAnchor.SCHOOL_GOVERNANCE);
        assertDeny(publish, "HRAP_APPROVAL_REQUIRED");

        var approved = publish.withHighRiskApprovals(Set.of("rule.publish"));
        assertAllow(approved);

        var wrong = publish.withHighRiskApprovals(Set.of("strategy.publish"));
        assertDeny(wrong, "HRAP_APPROVAL_REQUIRED");
    }

    @Test
    void delegationUsesHalfOpenWindowAndCanNeverExpandBasePermission() {
        var grant = new DelegationEvidence(
                NOW,
                NOW.plusSeconds(60),
                true,
                Set.of(ObjectClass.CLUE),
                Set.of(ActionId.of("care.read")),
                Set.of(FieldClass.BASIC, FieldClass.IDENTITY),
                31);

        assertDeny(delegated(grant, NOW.minusNanos(1)), "DELEGATION_INACTIVE");
        assertAllow(delegated(grant, NOW));
        assertAllow(delegated(grant, NOW.plusSeconds(60).minusNanos(1)));
        assertDeny(delegated(grant, NOW.plusSeconds(60)), "DELEGATION_INACTIVE");

        var expanding = new DelegationEvidence(
                NOW,
                NOW.plusSeconds(60),
                false,
                Set.of(ObjectClass.CLUE),
                Set.of(ActionId.of("care.read")),
                Set.of(FieldClass.BASIC),
                31);
        assertDeny(delegated(expanding, NOW), "DELEGATION_BASE_PERMISSION_REQUIRED");
    }

    @Test
    void transferPurposeAndFieldAllowlistNarrowConditionalClearFields() {
        var decision = evaluator.evaluate(transferRequest(RolePackage.R5, "transfer.process"), policy);

        assertEquals(CompositeAuthorizationOutcome.ALLOW, decision.outcome());
        assertEquals(
                Set.of("studentContactPhone", "requestedServiceCode", "referralSummary", "resultSummary"),
                decision.clearConditionalFields());
        assertFalse(decision.clearConditionalFields().contains("studentContactEmail"));

        var noPurpose = transferRequest(RolePackage.R5, "transfer.process").withPurpose(null);
        assertDeny(noPurpose, "TRANSFER_PURPOSE_REQUIRED");
    }

    @Test
    void defaultSurfaceSelectionIsSingleAndDeterministic() {
        assertEquals("care-workbench", policy.selectDefaultSurface(
                Set.of(RolePackage.R1, RolePackage.R2, RolePackage.R7)));
        assertEquals("rule-operations-governance", policy.selectDefaultSurface(
                Set.of(RolePackage.R2, RolePackage.R3, RolePackage.R6)));
        assertEquals("technical-operations", policy.selectDefaultSurface(Set.of(RolePackage.R7)));
    }

    @Test
    void preCommitRecheckRejectsAnyEvidenceOrObjectVersionChange() {
        var decision = evaluator.evaluate(
                request(RolePackage.R1, ObjectClass.CLUE, "care.read", ScopeAnchor.CURRENT_RESPONSIBILITY),
                policy);
        var guard = new AuthorizationDecisionRechecker();

        assertTrue(guard.isCurrent(decision.decisionToken(), VERSIONS, 7, "RFP-1.0.0"));
        assertFalse(guard.isCurrent(decision.decisionToken(),
                new AuthorizationEvidenceVersions(11, 22, 31, 3, 1), 7, "RFP-1.0.0"));
        assertFalse(guard.isCurrent(decision.decisionToken(), VERSIONS, 8, "RFP-1.0.0"));
        assertFalse(guard.isCurrent(decision.decisionToken(), VERSIONS, 7, "RFP-1.0.1"));
        assertEquals("IDENTITY_AUTHORIZATION_DECISION_STALE",
                guard.recheck(decision.decisionToken(), VERSIONS, 8, "RFP-1.0.0").reasonCode());
    }

    private CompositeAuthorizationRequest delegated(DelegationEvidence grant, Instant at) {
        return new CompositeAuthorizationRequest(
                "actor-pseudonym",
                Set.of(RolePackage.R1),
                ObjectClass.CLUE,
                ActionId.of("care.read"),
                7,
                EnumSet.of(ScopeAnchor.VALID_DELEGATION_GRANT),
                null,
                Set.of(),
                VERSIONS,
                at,
                true,
                true,
                false,
                Set.of(),
                Optional.of(grant),
                Optional.empty());
    }

    private CompositeAuthorizationRequest transferRequest(RolePackage role, String action) {
        return new CompositeAuthorizationRequest(
                "actor-pseudonym",
                Set.of(role),
                ObjectClass.TRANSFER_ORDER,
                ActionId.of(action),
                7,
                EnumSet.of(ScopeAnchor.CURRENT_TRANSFER_ASSIGNMENT),
                "STUDENT_SUPPORT_REFERRAL",
                Set.of("studentContactPhone", "requestedServiceCode", "referralSummary", "resultSummary"),
                VERSIONS,
                NOW,
                true,
                true,
                false,
                Set.of(),
                Optional.empty(),
                Optional.of(new EffectiveWindow(NOW.minusSeconds(60), NOW.plusSeconds(60))));
    }

    private CompositeAuthorizationRequest request(
            RolePackage role, ObjectClass objectClass, String action, ScopeAnchor anchor) {
        return request(Set.of(role), objectClass, action, EnumSet.of(anchor));
    }

    private CompositeAuthorizationRequest request(
            Set<RolePackage> roles,
            ObjectClass objectClass,
            String action,
            Set<ScopeAnchor> anchors) {
        return new CompositeAuthorizationRequest(
                "actor-pseudonym",
                roles,
                objectClass,
                ActionId.of(action),
                7,
                anchors,
                null,
                Set.of(),
                VERSIONS,
                NOW,
                true,
                true,
                false,
                Set.of(),
                Optional.empty(),
                Optional.empty());
    }

    private CompositeAuthorizationRequest copy(
            CompositeAuthorizationRequest source, Set<ScopeAnchor> anchors) {
        return new CompositeAuthorizationRequest(
                source.actorPseudonym(), source.rolePackages(), source.objectClass(), source.actionId(),
                source.objectVersion(), anchors, source.purpose(), source.fieldAllowlist(),
                source.evidenceVersions(), source.serverNow(), source.identityAvailable(),
                source.trustedClock(), source.separationOfDutyConflict(), source.highRiskApprovals(),
                source.delegation(), source.taskWindow());
    }

    private void assertAllow(CompositeAuthorizationRequest request) {
        assertEquals(CompositeAuthorizationOutcome.ALLOW, evaluator.evaluate(request, policy).outcome());
    }

    private void assertDeny(CompositeAuthorizationRequest request, String reason) {
        var decision = evaluator.evaluate(request, policy);
        assertEquals(CompositeAuthorizationOutcome.DENY, decision.outcome());
        assertEquals(reason, decision.reasonCode());
    }
}
