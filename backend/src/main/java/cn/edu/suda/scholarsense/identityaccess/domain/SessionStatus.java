package cn.edu.suda.scholarsense.identityaccess.domain;

public enum SessionStatus {
    ACTIVE,
    REVOKED,
    REFRESH_FAMILY_REVOKED,
    REAUTHENTICATION_REQUIRED,
    EXPIRED
}
