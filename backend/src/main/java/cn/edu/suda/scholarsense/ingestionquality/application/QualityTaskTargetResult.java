package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

/** Target acknowledgement classification; confirmed means transport receipt only. */
public record QualityTaskTargetResult(Outcome outcome, String receiptId, String errorCode) {
    public enum Outcome { CONFIRMED, RETRYABLE, PERMANENT_FAILURE }

    public QualityTaskTargetResult {
        outcome = Objects.requireNonNull(outcome);
        if (outcome == Outcome.CONFIRMED) {
            if (receiptId == null || receiptId.isBlank() || receiptId.length() > 256
                    || errorCode != null) throw invalid();
        } else if (receiptId != null || errorCode == null
                || !errorCode.matches("^QUALITY_TASK_[A-Z0-9_]{2,110}$")) {
            throw invalid();
        }
    }

    public static QualityTaskTargetResult confirmed(String receiptId) {
        return new QualityTaskTargetResult(Outcome.CONFIRMED, receiptId, null);
    }

    public static QualityTaskTargetResult retryable(String errorCode) {
        return new QualityTaskTargetResult(Outcome.RETRYABLE, null, errorCode);
    }

    public static QualityTaskTargetResult permanentFailure(String errorCode) {
        return new QualityTaskTargetResult(Outcome.PERMANENT_FAILURE, null, errorCode);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_TASK_TARGET_RESULT_INVALID");
    }
}
