package cn.edu.suda.scholarsense.auditoperations.api;

public record AuditIngressResult(
        AuditIngressOutcome outcome,
        Long ledgerSequence,
        String entryHash,
        String errorCode,
        boolean retryable,
        String traceId) {
    public AuditIngressResult {
        if (outcome == null || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_INGRESS_RESULT_INVALID");
        }
        boolean success = outcome == AuditIngressOutcome.APPENDED
                || outcome == AuditIngressOutcome.EXACT_DUPLICATE;
        if (success != (ledgerSequence != null && ledgerSequence > 0
                        && entryHash != null && entryHash.matches("[0-9a-f]{64}"))
                || success != (errorCode == null)
                || !success && (ledgerSequence != null || entryHash != null
                        || errorCode == null || !errorCode.matches("AUDIT_[A-Z0-9_]+"))) {
            throw new IllegalArgumentException("AUDIT_INGRESS_RESULT_INVALID");
        }
    }

    public static AuditIngressResult appended(long sequence, String hash, String traceId) {
        return new AuditIngressResult(AuditIngressOutcome.APPENDED, sequence, hash, null, false, traceId);
    }

    public static AuditIngressResult exactDuplicate(long sequence, String hash, String traceId) {
        return new AuditIngressResult(
                AuditIngressOutcome.EXACT_DUPLICATE, sequence, hash, null, false, traceId);
    }

    public static AuditIngressResult collision(String traceId) {
        return new AuditIngressResult(
                AuditIngressOutcome.COLLISION,
                null,
                null,
                "AUDIT_INGESTION_DUPLICATE_CONFLICT",
                false,
                traceId);
    }

    public static AuditIngressResult rejected(String code, boolean retryable, String traceId) {
        return new AuditIngressResult(AuditIngressOutcome.REJECTED, null, null, code, retryable, traceId);
    }
}
