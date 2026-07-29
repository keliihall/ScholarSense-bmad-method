package cn.edu.suda.scholarsense.identityaccess.application;

public enum IdentityRecordKind {
    ACCOUNT("account"),
    ORGANIZATION("organization"),
    EMPLOYMENT_ROLE("employment-role");

    private final String wireName;

    IdentityRecordKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
