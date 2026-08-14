package cn.edu.suda.scholarsense.identityaccess.api;

/** Opaque current person authority result; no account or person plaintext crosses the port. */
public record CurrentNaturalPersonPrincipalResult(
        Availability availability,
        String naturalPersonPrincipalDigest,
        long bindingVersion,
        String bindingSetDigest,
        String reasonCode,
        String traceId) {
    public enum Availability { AVAILABLE, UNAVAILABLE, NOT_INSTALLED }

    public CurrentNaturalPersonPrincipalResult {
        boolean available = availability == Availability.AVAILABLE;
        if (availability == null
                || available != digest(naturalPersonPrincipalDigest)
                || available != (bindingVersion >= 1)
                || available != digest(bindingSetDigest)
                || available != (reasonCode == null)
                || traceId == null || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw new IllegalArgumentException("IDENTITY_NATURAL_PERSON_RESULT_INVALID");
        }
    }

    public static CurrentNaturalPersonPrincipalResult available(
            String principalDigest, long bindingVersion,
            String bindingSetDigest, String traceId) {
        return new CurrentNaturalPersonPrincipalResult(
                Availability.AVAILABLE, principalDigest, bindingVersion,
                bindingSetDigest, null, traceId);
    }

    public static CurrentNaturalPersonPrincipalResult unavailable(
            String reasonCode, String traceId) {
        return new CurrentNaturalPersonPrincipalResult(
                Availability.UNAVAILABLE, null, 0, null, reasonCode, traceId);
    }

    private static boolean digest(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
