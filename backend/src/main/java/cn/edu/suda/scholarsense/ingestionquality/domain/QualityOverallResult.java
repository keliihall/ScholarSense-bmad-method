package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum QualityOverallResult {
    QUALITY_PASSED("quality-passed"),
    QUALITY_FAILED("quality-failed");

    private final String wireValue;

    QualityOverallResult(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
