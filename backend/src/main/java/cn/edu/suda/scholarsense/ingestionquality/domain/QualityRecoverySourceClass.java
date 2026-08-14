package cn.edu.suda.scholarsense.ingestionquality.domain;

/** Approved source classes whose recovery thresholds are defined by QRP-1.0.0. */
public enum QualityRecoverySourceClass {
    STREAMING("streaming"),
    DAILY_BATCH("dailyBatch");

    private final String contractValue;

    QualityRecoverySourceClass(String contractValue) {
        this.contractValue = contractValue;
    }

    public String contractValue() {
        return contractValue;
    }

    public static QualityRecoverySourceClass fromContractValue(String value) {
        return switch (value) {
            case "streaming" -> STREAMING;
            case "dailyBatch" -> DAILY_BATCH;
            default -> throw new IllegalArgumentException(
                    "QUALITY_RECOVERY_SOURCE_CLASS_INVALID");
        };
    }
}
