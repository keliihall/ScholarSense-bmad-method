package cn.edu.suda.scholarsense.ingestionquality.domain;

public record BatchIdentity(String sourceId, String businessKey, long sourceVersion) {
    public BatchIdentity {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw IngestionQualityDomainRules.invalid();
        }
        businessKey = IngestionQualityDomainRules.requireText(businessKey, 1024);
        sourceVersion = IngestionQualityDomainRules.requireVersion(sourceVersion);
    }
}
