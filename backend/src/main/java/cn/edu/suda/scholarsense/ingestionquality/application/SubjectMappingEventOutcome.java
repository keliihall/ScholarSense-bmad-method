package cn.edu.suda.scholarsense.ingestionquality.application;

public enum SubjectMappingEventOutcome {
    APPLIED,
    BACKFILL_APPLIED,
    DUPLICATE,
    OLD_VERSION,
    GAP_PAUSED,
    POISON_QUARANTINED
}
