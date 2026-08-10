package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum DataBatchStatus {
    RECEIVING("receiving"),
    SEALED("sealed"),
    QUALITY_PASSED("quality-passed"),
    QUALITY_FAILED("quality-failed"),
    PUBLISHED("published");

    private final String wireValue;

    DataBatchStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
