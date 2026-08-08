package cn.edu.suda.scholarsense.subjectregistry.api;

/** Stable outcomes used by the producer-owned relay acknowledgement policy. */
public enum SubjectMappingConsumptionOutcome {
    APPLIED,
    BACKFILL_APPLIED,
    DUPLICATE,
    OLD_VERSION,
    GAP_PAUSED,
    POISON_QUARANTINED
}
