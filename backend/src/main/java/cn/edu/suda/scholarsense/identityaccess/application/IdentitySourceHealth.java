package cn.edu.suda.scholarsense.identityaccess.application;

public enum IdentitySourceHealth {
    HEALTHY("healthy"),
    DEGRADED("degraded");

    private final String wireName;

    IdentitySourceHealth(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
