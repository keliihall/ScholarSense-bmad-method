package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Current object-owner facts. This value is evidence, never an authorization decision. */
public record AuthorizationObjectEvidence(
        AuthorizationEvidenceAvailability availability,
        Set<AuthorizationScopeEvidence> scopeEvidence,
        String purpose,
        Set<String> fieldAllowlist,
        Instant taskStartAt,
        Instant taskEndAt,
        Set<String> highRiskApprovals,
        boolean separationOfDutyConflict,
        long relationVersion,
        long grantVersion,
        long invalidationVersion,
        long objectVersion,
        Optional<AuthorizationDelegationEvidence> delegation) {
    public AuthorizationObjectEvidence {
        Objects.requireNonNull(availability, "availability");
        scopeEvidence = Set.copyOf(scopeEvidence);
        fieldAllowlist = Set.copyOf(fieldAllowlist);
        highRiskApprovals = Set.copyOf(highRiskApprovals);
        delegation = Objects.requireNonNull(delegation, "delegation");
        if ((taskStartAt == null) != (taskEndAt == null)
                || (taskStartAt != null && !taskStartAt.isBefore(taskEndAt))) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_TASK_WINDOW_INVALID");
        }
        if (availability == AuthorizationEvidenceAvailability.AVAILABLE) {
            if (relationVersion < 0 || grantVersion < 0 || invalidationVersion < 0
                    || objectVersion < 1) {
                throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_EVIDENCE_VERSION_INVALID");
            }
        } else if (!scopeEvidence.isEmpty()
                || !fieldAllowlist.isEmpty()
                || !highRiskApprovals.isEmpty()
                || purpose != null
                || taskStartAt != null
                || delegation.isPresent()
                || relationVersion != 0
                || grantVersion != 0
                || invalidationVersion != 0
                || objectVersion != 0) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_UNAVAILABLE_EVIDENCE_LEAK");
        }
    }

    public static AuthorizationObjectEvidence notInstalled() {
        return unavailable(AuthorizationEvidenceAvailability.NOT_INSTALLED);
    }

    public static AuthorizationObjectEvidence unavailable() {
        return unavailable(AuthorizationEvidenceAvailability.UNAVAILABLE);
    }

    private static AuthorizationObjectEvidence unavailable(
            AuthorizationEvidenceAvailability availability) {
        return new AuthorizationObjectEvidence(
                availability,
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
                Optional.empty());
    }
}
