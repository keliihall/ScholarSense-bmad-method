package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class BatchLineage {
    private final UUID lineageId;
    private final UUID supersedesBatchId;
    private final BatchCorrectionReason reasonCode;
    private final Instant effectiveAt;

    private BatchLineage(
            UUID lineageId,
            UUID supersedesBatchId,
            BatchCorrectionReason reasonCode,
            Instant effectiveAt) {
        this.lineageId = IngestionQualityDomainRules.requireUuidV7(lineageId);
        boolean root = supersedesBatchId == null && reasonCode == null && effectiveAt != null;
        boolean successor = supersedesBatchId != null && reasonCode != null && effectiveAt != null;
        if (!root && !successor) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (supersedesBatchId != null) {
            IngestionQualityDomainRules.requireUuidV7(supersedesBatchId);
        }
        this.supersedesBatchId = supersedesBatchId;
        this.reasonCode = reasonCode;
        this.effectiveAt = effectiveAt;
    }

    public static BatchLineage root(UUID lineageId, Instant effectiveAt) {
        return new BatchLineage(lineageId, null, null, effectiveAt);
    }

    public static BatchLineage successor(
            UUID lineageId,
            UUID supersedesBatchId,
            BatchCorrectionReason reasonCode,
            Instant effectiveAt) {
        return new BatchLineage(lineageId, supersedesBatchId, reasonCode, effectiveAt);
    }

    public UUID lineageId() {
        return lineageId;
    }

    public UUID supersedesBatchId() {
        return supersedesBatchId;
    }

    public BatchCorrectionReason reasonCode() {
        return reasonCode;
    }

    public Instant effectiveAt() {
        return effectiveAt;
    }

    public boolean successor() {
        return supersedesBatchId != null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BatchLineage that)) return false;
        return lineageId.equals(that.lineageId)
                && Objects.equals(supersedesBatchId, that.supersedesBatchId)
                && reasonCode == that.reasonCode
                && Objects.equals(effectiveAt, that.effectiveAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lineageId, supersedesBatchId, reasonCode, effectiveAt);
    }

    @Override
    public String toString() {
        return "BatchLineage[lineageId=" + lineageId
                + ", supersedesBatchId=" + supersedesBatchId
                + ", reasonCode=" + reasonCode
                + ", effectiveAt=" + effectiveAt + "]";
    }
}
