package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.UUID;

public record NormalizedFactIdentity(
        UUID batchId,
        String recordId,
        String sourceId,
        String businessKey,
        long sourceVersion,
        String sourceSchemaVersion,
        String sourceSchemaDigest,
        UUID lineageId) {
    public NormalizedFactIdentity {
        batchId = IngestionQualityDomainRules.requireUuidV7(batchId);
        recordId = IngestionQualityDomainRules.requireText(recordId, 1024);
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw IngestionQualityDomainRules.invalid();
        }
        businessKey = IngestionQualityDomainRules.requireText(businessKey, 1024);
        sourceVersion = IngestionQualityDomainRules.requireVersion(sourceVersion);
        sourceSchemaVersion = IngestionQualityDomainRules.requireText(sourceSchemaVersion, 128);
        sourceSchemaDigest = IngestionQualityDomainRules.requireSha256(sourceSchemaDigest);
        lineageId = IngestionQualityDomainRules.requireUuidV7(lineageId);
    }

    boolean sameRecordIdentity(NormalizedFactIdentity other) {
        return other != null && batchId.equals(other.batchId) && recordId.equals(other.recordId);
    }
}
