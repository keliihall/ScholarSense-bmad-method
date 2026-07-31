package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Map;
import java.util.Set;

/**
 * Candidate action vocabulary for later Story 1.7 authorization.
 *
 * <p>Membership here is not an ALLOW decision; owner binding and full scope
 * authorization remain deliberately absent.
 */
public final class ResponsibilityAdministrativeProjectionContract {
    private static final Map<String, Set<String>> ACTIONS = Map.of(
            "R6-DATA-OWNER",
            Set.of(
                    "data-quality.read",
                    "data-quality.reconcile"),
            "R7-PLATFORM-OPS",
            Set.of(
                    "platform.read",
                    "platform.diagnose",
                    "platform.retry",
                    "platform.reconcile"));

    private ResponsibilityAdministrativeProjectionContract() {}

    public static Set<String> candidateActions(String roleId) {
        return ACTIONS.getOrDefault(roleId, Set.of());
    }

    public static boolean isCandidateAction(
            String roleId, String actionId) {
        return actionId != null
                && actionId.indexOf('/') < 0
                && candidateActions(roleId).contains(actionId);
    }
}
