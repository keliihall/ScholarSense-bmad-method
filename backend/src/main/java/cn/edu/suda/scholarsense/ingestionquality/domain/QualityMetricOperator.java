package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum QualityMetricOperator {
    GREATER_THAN_OR_EQUAL(">="),
    LESS_THAN_OR_EQUAL("<="),
    EQUAL("=");

    private final String wireValue;

    QualityMetricOperator(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    static QualityMetricOperator fromWire(String value) {
        for (QualityMetricOperator candidate : values()) {
            if (candidate.wireValue.equals(value)) return candidate;
        }
        throw IngestionQualityDomainRules.invalid();
    }
}
