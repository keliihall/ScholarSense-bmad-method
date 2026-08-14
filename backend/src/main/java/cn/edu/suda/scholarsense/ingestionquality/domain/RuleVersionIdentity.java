package cn.edu.suda.scholarsense.ingestionquality.domain;

public record RuleVersionIdentity(String ruleId, String ruleVersion) {
    public RuleVersionIdentity {
        if (ruleId == null || !ruleId.matches("^[A-Z][A-Z0-9-]{2,63}$")
                || ruleVersion == null || !ruleVersion.matches("^[1-9][0-9]*\\.[0-9]+\\.[0-9]+$")) {
            throw invalid();
        }
    }

    public String businessKey(String registryVersion) {
        return ruleId + "@" + ruleVersion + "@"
                + IngestionQualityDomainRules.requireText(registryVersion, 128);
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_REGISTRY_INVALID);
    }
}
