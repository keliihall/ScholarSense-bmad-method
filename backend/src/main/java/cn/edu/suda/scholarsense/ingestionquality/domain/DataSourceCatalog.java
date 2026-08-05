package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DataSourceCatalog {
    /** Largest version that can be represented exactly by every JSON/JavaScript consumer. */
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    private final UUID catalogId;
    private final UUID catalogReleaseId;
    private final String contractVersion;
    private final List<SourceContract> sources;
    private final List<DependencyBinding> dependencies;
    private final String contentDigest;
    private final String evidenceSetDigest;
    private final CatalogStatus status;
    private final long aggregateVersion;
    private final List<CatalogValidationFailure> validationFailures;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant publishedAt;

    private DataSourceCatalog(
            UUID catalogId,
            UUID catalogReleaseId,
            String contractVersion,
            List<SourceContract> sources,
            List<DependencyBinding> dependencies,
            String contentDigest,
            String evidenceSetDigest,
            CatalogStatus status,
            long aggregateVersion,
            List<CatalogValidationFailure> validationFailures,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt) {
        requireUuidV7(catalogId);
        if (catalogReleaseId != null) requireUuidV7(catalogReleaseId);
        if (!"DCC-1.0.0".equals(contractVersion)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CONTRACT_VERSION_INVALID");
        }
        List<SourceContract> sourceCopy = List.copyOf(Objects.requireNonNull(sources));
        List<DependencyBinding> dependencyCopy = List.copyOf(Objects.requireNonNull(dependencies));
        if (sourceCopy.isEmpty()) throw new IllegalArgumentException("INGESTION_QUALITY_SOURCES_EMPTY");
        if (new HashSet<>(sourceCopy.stream().map(SourceContract::sourceId).toList()).size() != sourceCopy.size()) {
            throw new IllegalArgumentException("INGESTION_QUALITY_SOURCE_ID_DUPLICATE");
        }
        if (new HashSet<>(dependencyCopy.stream().map(DependencyBinding::dependencyId).toList()).size() != dependencyCopy.size()) {
            throw new IllegalArgumentException("INGESTION_QUALITY_DEPENDENCY_ID_DUPLICATE");
        }
        this.sources = sourceCopy.stream()
                .sorted(Comparator.comparing(SourceContract::sourceId)).toList();
        this.dependencies = dependencyCopy.stream()
                .sorted(Comparator.comparing(DependencyBinding::dependencyId)).toList();
        requireDigest(contentDigest, IngestionQualityErrorCode.INGESTION_QUALITY_CONTRACT_INVALID);
        if (evidenceSetDigest != null) {
            requireDigest(evidenceSetDigest, IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
        }
        if (aggregateVersion < 1 || aggregateVersion > MAX_VERSION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_AGGREGATE_VERSION_INVALID");
        }
        this.catalogId = catalogId;
        this.catalogReleaseId = catalogReleaseId;
        this.contractVersion = contractVersion;
        this.contentDigest = contentDigest;
        this.evidenceSetDigest = evidenceSetDigest;
        this.status = Objects.requireNonNull(status);
        this.aggregateVersion = aggregateVersion;
        this.validationFailures = List.copyOf(Objects.requireNonNull(validationFailures));
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.publishedAt = publishedAt;
        if ((status == CatalogStatus.PUBLISHED) != (catalogReleaseId != null && publishedAt != null && evidenceSetDigest != null)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_PUBLISHED_SHAPE_INVALID");
        }
    }

    public static DataSourceCatalog draft(
            UUID catalogId, String contractVersion, List<SourceContract> sources,
            List<DependencyBinding> dependencies, String contentDigest, Instant createdAt) {
        return new DataSourceCatalog(
                catalogId, null, contractVersion, sources, dependencies, contentDigest, null,
                CatalogStatus.DRAFT, 1, List.of(), createdAt, createdAt, null);
    }

    public static DataSourceCatalog restore(
            UUID catalogId, UUID releaseId, String contractVersion, List<SourceContract> sources,
            List<DependencyBinding> dependencies, String contentDigest, String evidenceSetDigest,
            CatalogStatus status, long aggregateVersion, List<CatalogValidationFailure> failures,
            Instant createdAt, Instant updatedAt, Instant publishedAt) {
        return new DataSourceCatalog(
                catalogId, releaseId, contractVersion, sources, dependencies, contentDigest,
                evidenceSetDigest, status, aggregateVersion, failures, createdAt, updatedAt, publishedAt);
    }

    public DataSourceCatalog validated(List<CatalogValidationFailure> failures, Instant validatedAt) {
        if (status == CatalogStatus.PUBLISHED) throw invalidState();
        long next = nextVersion();
        List<CatalogValidationFailure> copy = List.copyOf(Objects.requireNonNull(failures));
        return new DataSourceCatalog(
                catalogId, null, contractVersion, sources, dependencies, contentDigest, null,
                copy.isEmpty() ? CatalogStatus.PUBLISHABLE : CatalogStatus.INVALID,
                next, copy, createdAt, Objects.requireNonNull(validatedAt), null);
    }

    public DataSourceCatalog publish(UUID releaseId, String newEvidenceSetDigest, Instant at) {
        if (status != CatalogStatus.PUBLISHABLE) throw invalidState();
        requireUuidV7(releaseId);
        requireDigest(newEvidenceSetDigest, IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
        if (sources.stream().anyMatch(source -> source.runtimeEvidenceClaim() != RuntimeEvidenceClaim.TARGET_VERIFIED)) {
            throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
        }
        return new DataSourceCatalog(
                catalogId, releaseId, contractVersion, sources, dependencies, contentDigest,
                newEvidenceSetDigest, CatalogStatus.PUBLISHED, nextVersion(), List.of(),
                createdAt, Objects.requireNonNull(at), at);
    }

    private long nextVersion() {
        if (aggregateVersion == MAX_VERSION) {
            throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_VERSION_CONFLICT);
        }
        return aggregateVersion + 1;
    }

    private static IngestionQualityException invalidState() {
        return new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_INVALID_STATE);
    }

    private static void requireDigest(String value, IngestionQualityErrorCode code) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IngestionQualityException(code);
        }
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_CONTRACT_INVALID);
        }
    }

    public UUID catalogId() { return catalogId; }
    public UUID catalogReleaseId() { return catalogReleaseId; }
    public String contractVersion() { return contractVersion; }
    public List<SourceContract> sources() { return sources; }
    public List<DependencyBinding> dependencies() { return dependencies; }
    public String contentDigest() { return contentDigest; }
    public String evidenceSetDigest() { return evidenceSetDigest; }
    public CatalogStatus status() { return status; }
    public long aggregateVersion() { return aggregateVersion; }
    public List<CatalogValidationFailure> validationFailures() { return validationFailures; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant publishedAt() { return publishedAt; }
}
