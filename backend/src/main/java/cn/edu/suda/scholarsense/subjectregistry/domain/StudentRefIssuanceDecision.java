package cn.edu.suda.scholarsense.subjectregistry.domain;

public enum StudentRefIssuanceDecision {
    ISSUE_STUDENT_REF,
    RESOLVE_EXISTING_ONLY,
    ISOLATE_NO_MATCH,
    ISOLATE_AMBIGUOUS,
    ISOLATE_REISSUE_UNPROVEN
}
