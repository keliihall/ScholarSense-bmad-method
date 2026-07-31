package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current responsibility scope only; this is not an authorization decision. */
public record ResponsibilityScopeView(
        String studentSourceRefDigest,
        UUID counselorAccountId,
        UUID collegeOrganizationId,
        long sourceVersion,
        long sourceWatermark,
        long aggregateVersion,
        Instant effectiveFrom,
        Instant effectiveTo,
        ResponsibilityScopeValidity validity,
        ResponsibilityScopeFreshness freshness,
        String reasonCode,
        String contractVersion,
        Instant evaluatedAt) {
    public ResponsibilityScopeView {
        if (studentSourceRefDigest == null
                || !studentSourceRefDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_STUDENT_REF_INVALID");
        }
        Objects.requireNonNull(validity, "validity");
        Objects.requireNonNull(freshness, "freshness");
        if (reasonCode == null
                || !reasonCode.matches("RESPONSIBILITY_[A-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_REASON_INVALID");
        }
        if (!"RESPONSIBILITY-AUTHORITY-1.0.0".equals(contractVersion)) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_CONTRACT_INVALID");
        }
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (validity == ResponsibilityScopeValidity.VALID) {
            requireUuidV7(counselorAccountId);
            requireUuidV7(collegeOrganizationId);
            if (sourceVersion < 1
                    || sourceWatermark < 1
                    || aggregateVersion < 1
                    || effectiveFrom == null
                    || freshness != ResponsibilityScopeFreshness.FRESH
                    || evaluatedAt.isBefore(effectiveFrom)
                    || (effectiveTo != null && !evaluatedAt.isBefore(effectiveTo))) {
                throw new IllegalArgumentException(
                        "RESPONSIBILITY_SCOPE_VALID_STATE_INVALID");
            }
        } else if (counselorAccountId != null || collegeOrganizationId != null) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_INVALID_RECIPIENT_LEAK");
        }
    }

    public static ResponsibilityScopeView unavailable(
            String studentSourceRefDigest, String reasonCode, Instant evaluatedAt) {
        return new ResponsibilityScopeView(
                studentSourceRefDigest,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                ResponsibilityScopeValidity.DEPENDENCY_UNAVAILABLE,
                ResponsibilityScopeFreshness.UNKNOWN,
                reasonCode,
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                evaluatedAt);
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_UUIDV7_REQUIRED");
        }
    }
}
