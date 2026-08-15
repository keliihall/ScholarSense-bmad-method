package cn.edu.suda.scholarsense.shared.observability;

public record TraceExtraction(
        W3cTraceContext context,
        TraceExtractionReason reason,
        boolean remoteParent) {}
