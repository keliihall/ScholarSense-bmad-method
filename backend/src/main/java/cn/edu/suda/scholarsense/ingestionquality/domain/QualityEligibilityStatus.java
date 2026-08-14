package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum QualityEligibilityStatus {
    ELIGIBLE("eligible"),
    FUSED("fused"),
    RECOVERING("recovering"),
    MISSING("missing");

    private final String wireValue;

    QualityEligibilityStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
