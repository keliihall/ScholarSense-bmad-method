package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class NormalizedFactSet {
    private final UUID batchId;
    private final BatchIdentity ownerIdentity;
    private final BatchLineage ownerLineage;
    private final String declaredManifestDigest;
    private final List<NormalizedFact> facts;
    private final String sealedManifestDigest;

    private NormalizedFactSet(
            UUID batchId,
            BatchIdentity ownerIdentity,
            BatchLineage ownerLineage,
            String declaredManifestDigest,
            List<NormalizedFact> facts,
            String sealedManifestDigest) {
        this.batchId = IngestionQualityDomainRules.requireUuidV7(batchId);
        this.ownerIdentity = Objects.requireNonNull(ownerIdentity);
        this.ownerLineage = Objects.requireNonNull(ownerLineage);
        this.declaredManifestDigest = IngestionQualityDomainRules.requireSha256(
                declaredManifestDigest);
        this.facts = List.copyOf(Objects.requireNonNull(facts));
        this.sealedManifestDigest = sealedManifestDigest == null
                ? null : IngestionQualityDomainRules.requireSha256(sealedManifestDigest);
    }

    public static NormalizedFactSet empty(DataBatch owner) {
        Objects.requireNonNull(owner);
        if (owner.status() != DataBatchStatus.RECEIVING) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_BATCH_IMMUTABLE);
        }
        return new NormalizedFactSet(
                owner.batchId(), owner.identity(), owner.lineage(),
                owner.declaredManifestDigest(), List.of(), null);
    }

    public NormalizedFactSet accept(DataBatch owner, NormalizedFact candidate) {
        requireOwner(owner);
        Objects.requireNonNull(candidate);
        if (sealedManifestDigest != null || owner.status() != DataBatchStatus.RECEIVING) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_BATCH_IMMUTABLE);
        }
        requireBinding(owner, candidate.identity());
        NormalizedFact existing = facts.stream()
                .filter(fact -> fact.identity().sameRecordIdentity(candidate.identity()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (existing.equals(candidate)) {
                return this;
            }
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_NORMALIZED_FACT_CONFLICT);
        }
        ArrayList<NormalizedFact> next = new ArrayList<>(facts);
        next.add(candidate);
        return new NormalizedFactSet(
                batchId, ownerIdentity, ownerLineage, declaredManifestDigest, next, null);
    }

    public NormalizedFactSet validatedForSeal(DataBatch owner) {
        requireOwner(owner);
        if (owner.status() != DataBatchStatus.SEALED || owner.manifest() == null) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_INVALID_STATE);
        }
        validateManifest(owner.manifest());
        if (owner.manifest().manifestDigest().equals(sealedManifestDigest)) return this;
        return new NormalizedFactSet(
                batchId, ownerIdentity, ownerLineage, declaredManifestDigest,
                facts, owner.manifest().manifestDigest());
    }

    public List<NormalizedFact> visibleFacts(DataBatch owner) {
        requireOwner(owner);
        if (owner.status() != DataBatchStatus.PUBLISHED) return List.of();
        if (owner.manifest() == null
                || !owner.manifest().manifestDigest().equals(sealedManifestDigest)) {
            throw bindingInvalid();
        }
        validateManifest(owner.manifest());
        return facts;
    }

    private void validateManifest(BatchManifest manifest) {
        boolean valid = facts.size() == manifest.validRecordCount()
                && facts.stream().allMatch(fact ->
                        fact.identity().sourceSchemaVersion().equals(
                                manifest.sourceSchemaVersion())
                                && fact.identity().sourceSchemaDigest().equals(
                                        manifest.sourceSchemaDigest()));
        if (!valid) throw bindingInvalid();
    }

    private void requireOwner(DataBatch owner) {
        if (owner == null
                || !batchId.equals(owner.batchId())
                || !ownerIdentity.equals(owner.identity())
                || !ownerLineage.equals(owner.lineage())
                || !declaredManifestDigest.equals(owner.declaredManifestDigest())) {
            throw bindingInvalid();
        }
    }

    private static void requireBinding(DataBatch owner, NormalizedFactIdentity identity) {
        boolean matches = owner.batchId().equals(identity.batchId())
                && owner.identity().sourceId().equals(identity.sourceId())
                && owner.identity().businessKey().equals(identity.businessKey())
                && owner.identity().sourceVersion() == identity.sourceVersion()
                && owner.lineage().lineageId().equals(identity.lineageId());
        if (!matches) {
            throw bindingInvalid();
        }
    }

    private static IngestionQualityException bindingInvalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_NORMALIZED_FACT_BINDING_INVALID);
    }

    public UUID batchId() {
        return batchId;
    }

    public List<NormalizedFact> facts() {
        return facts;
    }

    public String sealedManifestDigest() {
        return sealedManifestDigest;
    }
}
