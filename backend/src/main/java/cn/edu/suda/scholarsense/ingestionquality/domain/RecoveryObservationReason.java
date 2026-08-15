package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum RecoveryObservationReason {
    NO_DATA,
    CONSECUTIVE_BATCHES_INSUFFICIENT,
    DURATION_INCOMPLETE,
    SEQUENCE_GAP,
    POISONED_PAIR,
    EVIDENCE_UNKNOWN,
    TECHNICAL_UNAVAILABLE,
    VERIFIED_QUALITY_FAILURE,
    CURRENT_FACT_DRIFT,
    READY
}
