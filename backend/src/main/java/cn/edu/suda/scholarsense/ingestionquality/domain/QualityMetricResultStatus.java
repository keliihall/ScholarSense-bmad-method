package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum QualityMetricResultStatus {
    PASSED("passed"),
    FAILED("failed"),
    NOT_APPLICABLE("not-applicable");

    private final String wireValue;

    QualityMetricResultStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
