package cn.edu.suda.scholarsense.identityaccess.domain;

public enum OrganizationType {
    SCHOOL("school"),
    COLLEGE("college"),
    DEPARTMENT("department");

    private final String wireName;

    OrganizationType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
