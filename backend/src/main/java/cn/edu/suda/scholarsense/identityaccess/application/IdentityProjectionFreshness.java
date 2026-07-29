package cn.edu.suda.scholarsense.identityaccess.application;

public enum IdentityProjectionFreshness {
    FRESH("fresh"),
    STALE("stale");

    private final String wireName;

    IdentityProjectionFreshness(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
