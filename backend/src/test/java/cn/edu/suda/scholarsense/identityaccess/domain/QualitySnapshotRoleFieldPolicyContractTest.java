package cn.edu.suda.scholarsense.identityaccess.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class QualitySnapshotRoleFieldPolicyContractTest {
    private final RoleFieldPolicyCatalog policy = RoleFieldPolicyCatalog.approved();

    @Test
    void r6QualitySnapshotIsReadOnlyAndSourceRemainsTheReconciliationObject() {
        assertTrue(policy.matches(
                RolePackage.R6, ObjectClass.QUALITY_SNAPSHOT,
                new ActionId("data-quality.read")));
        assertFalse(policy.matches(
                RolePackage.R6, ObjectClass.QUALITY_SNAPSHOT,
                new ActionId("data-quality.reconcile")));
        assertFalse(policy.matches(
                RolePackage.R6, ObjectClass.QUALITY_SNAPSHOT,
                new ActionId("data-quality.repair")));
        assertTrue(policy.matches(
                RolePackage.R6, ObjectClass.SOURCE,
                new ActionId("data-quality.reconcile")));
        assertTrue(policy.fieldVisibility(RolePackage.R6, ObjectClass.QUALITY_SNAPSHOT)
                .entrySet().containsAll(Map.of(
                        FieldClass.BASIC, Visibility.CLEAR,
                        FieldClass.IDENTITY, Visibility.HIDDEN,
                        FieldClass.CONTACT, Visibility.HIDDEN,
                        FieldClass.SENSITIVE_CARE, Visibility.HIDDEN,
                        FieldClass.EVIDENCE, Visibility.CLEAR,
                        FieldClass.NARRATIVE, Visibility.HIDDEN,
                        FieldClass.GOVERNANCE, Visibility.CLEAR,
                        FieldClass.TECHNICAL, Visibility.CLEAR).entrySet()));

        for (String forbidden : new String[] {
                "data-batch.seal", "data-batch.pass", "data-batch.publish"
        }) {
            assertFalse(policy.knownAction(new ActionId(forbidden)));
        }
    }
}
