package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Set;

/** Provider external student reference protected for the responsibility purpose only. */
public record ResponsibilityStudentSourceReference(
        String purposeCode,
        String keyVersion,
        String tokenValue,
        String digest,
        String equivalenceDomain) {
    private static final String PURPOSE = "RESPONSIBILITY-STUDENT-REF";
    private static final Set<String> KEY_VERSIONS =
            Set.of("resp-student-v1", "resp-student-v2");

    public ResponsibilityStudentSourceReference {
        if (!PURPOSE.equals(purposeCode)) {
            throw new IllegalArgumentException("RESPONSIBILITY_TOKEN_PURPOSE_INVALID");
        }
        if (!KEY_VERSIONS.contains(keyVersion)) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_TOKEN_KEY_VERSION_UNKNOWN");
        }
        if (tokenValue == null
                || !tokenValue.matches("stok_[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("RESPONSIBILITY_TOKEN_INVALID");
        }
        AuthorityValidation.digest(digest, "RESPONSIBILITY_STUDENT_REF");
        AuthorityValidation.digest(
                equivalenceDomain, "RESPONSIBILITY_STUDENT_EQUIVALENCE");
    }
}
