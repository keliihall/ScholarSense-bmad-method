package cn.edu.suda.scholarsense.identityaccess.domain;

public enum RolePackage {
    R1("R1-COUNSELOR"),
    R2("R2-COLLEGE-MANAGER"),
    R3("R3-STUDENT-AFFAIRS"),
    R4("R4-SCHOOL-LEADER"),
    R5("R5-COLLABORATOR"),
    R6("R6-DATA-OWNER"),
    R7("R7-PLATFORM-OPS");

    private final String authorityId;

    RolePackage(String authorityId) {
        this.authorityId = authorityId;
    }

    public String authorityId() {
        return authorityId;
    }

    public static RolePackage fromAuthorityId(String authorityId) {
        for (RolePackage role : values()) {
            if (role.authorityId.equals(authorityId)) {
                return role;
            }
        }
        throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_ROLE_UNKNOWN");
    }
}
