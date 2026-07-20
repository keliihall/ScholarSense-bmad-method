package cn.edu.suda.scholarsense.identityaccess.application;

public final class SessionCookiePolicy {
    public static final String NAME = "__Host-ScholarSense";

    private SessionCookiePolicy() {}

    public static String render(String opaqueSessionId) {
        if (opaqueSessionId == null || !opaqueSessionId.matches("[A-Za-z0-9._~-]{1,256}")) {
            throw new IllegalArgumentException("IDENTITY_SESSION_COOKIE_INVALID");
        }
        return NAME + "=" + opaqueSessionId + "; Path=/; Secure; HttpOnly; SameSite=Lax";
    }

    public static String clear() {
        return NAME + "=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax";
    }
}
