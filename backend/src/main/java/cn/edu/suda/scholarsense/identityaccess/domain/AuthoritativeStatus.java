package cn.edu.suda.scholarsense.identityaccess.domain;

public enum AuthoritativeStatus {
    ACTIVE("active"),
    INACTIVE("inactive");

    private final String wireName;

    AuthoritativeStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }
}
