package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;

public record BatchManifest(
        long recordCount,
        long validRecordCount,
        long rejectedRecordCount,
        BatchObservationWindow observationWindow,
        Instant cutoffAt,
        String timezone,
        String watermark,
        String sourceSchemaVersion,
        String sourceSchemaDigest,
        String dataCatalogVersion,
        String dataCatalogDigest,
        String qualityGateVersion,
        String qualityGateDigest,
        String qualityMetricDecisionProfileVersion,
        String qualityMetricDecisionProfileDigest,
        Instant sourceOccurredAt,
        Instant scheduledDueAt,
        Instant receivedAt,
        String laneId,
        String manifestDigest) {

    public BatchManifest {
        if (recordCount < 0 || recordCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || validRecordCount < 0
                || validRecordCount > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || rejectedRecordCount < 0
                || rejectedRecordCount > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw IngestionQualityDomainRules.invalid();
        }
        try {
            if (Math.addExact(validRecordCount, rejectedRecordCount) != recordCount) {
                throw IngestionQualityDomainRules.invalid();
            }
        } catch (ArithmeticException overflow) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (observationWindow == null || cutoffAt == null
                || sourceOccurredAt == null || scheduledDueAt == null || receivedAt == null) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (!"Asia/Shanghai".equals(timezone)) {
            throw IngestionQualityDomainRules.invalid();
        }
        watermark = IngestionQualityDomainRules.requireText(watermark, 512);
        sourceSchemaVersion = IngestionQualityDomainRules.requireText(sourceSchemaVersion, 128);
        sourceSchemaDigest = IngestionQualityDomainRules.requireSha256(sourceSchemaDigest);
        dataCatalogVersion = IngestionQualityDomainRules.requireText(dataCatalogVersion, 128);
        dataCatalogDigest = IngestionQualityDomainRules.requireSha256(dataCatalogDigest);
        qualityGateVersion = IngestionQualityDomainRules.requireText(qualityGateVersion, 128);
        qualityGateDigest = IngestionQualityDomainRules.requireSha256(qualityGateDigest);
        qualityMetricDecisionProfileVersion = IngestionQualityDomainRules.requireText(
                qualityMetricDecisionProfileVersion, 128);
        qualityMetricDecisionProfileDigest = IngestionQualityDomainRules.requireSha256(
                qualityMetricDecisionProfileDigest);
        laneId = IngestionQualityDomainRules.requireText(laneId, 128);
        manifestDigest = IngestionQualityDomainRules.requireSha256(manifestDigest);
    }
}
