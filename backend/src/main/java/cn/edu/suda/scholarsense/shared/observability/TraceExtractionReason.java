package cn.edu.suda.scholarsense.shared.observability;

/** Stable low-cardinality reason recorded for ingress trace extraction. */
public enum TraceExtractionReason {
    INHERITED,
    MISSING,
    INVALID,
    ALL_ZERO,
    UNTRUSTED
}
