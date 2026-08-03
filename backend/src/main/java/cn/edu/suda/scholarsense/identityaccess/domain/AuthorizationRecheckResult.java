package cn.edu.suda.scholarsense.identityaccess.domain;

public record AuthorizationRecheckResult(boolean current, String reasonCode) {
    public AuthorizationRecheckResult {
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_RECHECK_REASON_INVALID");
        }
    }
}
