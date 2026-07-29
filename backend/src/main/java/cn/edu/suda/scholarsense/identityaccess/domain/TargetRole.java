package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Arrays;

public enum TargetRole {
    R1_COUNSELOR("R1-COUNSELOR"),
    R2_COLLEGE_MANAGER("R2-COLLEGE-MANAGER"),
    R3_STUDENT_AFFAIRS("R3-STUDENT-AFFAIRS"),
    R4_SCHOOL_LEADER("R4-SCHOOL-LEADER"),
    R5_COLLABORATOR("R5-COLLABORATOR"),
    R6_DATA_OWNER("R6-DATA-OWNER"),
    R7_PLATFORM_OPS("R7-PLATFORM-OPS");

    private final String wireName;

    TargetRole(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static TargetRole fromWire(String value) {
        return Arrays.stream(values())
                .filter(role -> role.wireName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("IDENTITY_ROLE_UNKNOWN"));
    }
}
