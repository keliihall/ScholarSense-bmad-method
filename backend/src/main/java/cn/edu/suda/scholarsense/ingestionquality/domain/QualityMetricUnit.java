package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum QualityMetricUnit {
    BASIS_POINT("basis-point"),
    COUNT("count"),
    MILLISECOND("millisecond"),
    MEMBER_COUNT("member-count");

    private final String wireValue;

    QualityMetricUnit(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    static QualityMetricUnit fromWire(String value) {
        for (QualityMetricUnit candidate : values()) {
            if (candidate.wireValue.equals(value)) return candidate;
        }
        throw IngestionQualityDomainRules.invalid();
    }
}
