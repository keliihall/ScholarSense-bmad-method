package cn.edu.suda.scholarsense.identityaccess.domain;

public enum AccessInvalidationJobState {
    PENDING,
    RUNNING,
    RETRY,
    COMPLETED,
    QUARANTINED
}
