package cn.edu.suda.scholarsense.identityaccess.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ResponsibilityAdministrativeProjectionContractTest {
    @Test
    void slashShorthandExpandsToSingleActionIdsButIsNeverAnAction() {
        assertEquals(
                Set.of(
                        "data-quality.read",
                        "data-quality.reconcile"),
                ResponsibilityAdministrativeProjectionContract
                        .candidateActions("R6-DATA-OWNER"));
        assertEquals(
                Set.of(
                        "platform.read",
                        "platform.diagnose",
                        "platform.retry",
                        "platform.reconcile"),
                ResponsibilityAdministrativeProjectionContract
                        .candidateActions("R7-PLATFORM-OPS"));
        assertFalse(
                ResponsibilityAdministrativeProjectionContract
                        .isCandidateAction(
                                "R6-DATA-OWNER",
                                "data-quality.read/reconcile"));
        assertFalse(
                ResponsibilityAdministrativeProjectionContract
                        .isCandidateAction(
                                "R7-PLATFORM-OPS",
                                "platform.read/diagnose/retry/reconcile"));
        assertTrue(
                ResponsibilityAdministrativeProjectionContract
                        .isCandidateAction(
                                "R7-PLATFORM-OPS",
                                "platform.reconcile"));
    }

    @Test
    void unknownRoleOrActionHasNoCandidateProjection() {
        assertTrue(
                ResponsibilityAdministrativeProjectionContract
                        .candidateActions("authorization-admin")
                        .isEmpty());
        assertFalse(
                ResponsibilityAdministrativeProjectionContract
                        .isCandidateAction(
                                "R6-DATA-OWNER",
                                "responsibility.reconcile"));
    }
}
