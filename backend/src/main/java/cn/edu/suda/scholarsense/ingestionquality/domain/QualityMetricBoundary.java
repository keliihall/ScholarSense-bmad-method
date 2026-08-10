package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum QualityMetricBoundary {
    INCLUSIVE("inclusive");

    private final String wireValue;

    QualityMetricBoundary(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    static QualityMetricBoundary fromWire(String value) {
        if (INCLUSIVE.wireValue.equals(value)) return INCLUSIVE;
        throw IngestionQualityDomainRules.invalid();
    }
}
