package cn.edu.suda.scholarsense.identityaccess.domain;

public enum AccessInvalidationDeliveryDecision {
    APPLIED,
    DUPLICATE,
    CONFLICT,
    OLD_IGNORED,
    GAP_BACKFILL_REQUIRED
}
