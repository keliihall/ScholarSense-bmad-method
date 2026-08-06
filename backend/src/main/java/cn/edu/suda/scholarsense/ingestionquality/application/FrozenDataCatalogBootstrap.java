package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.util.Objects;

/** Worker-facing entry point; intentionally has no HTTP/controller adapter. */
public final class FrozenDataCatalogBootstrap {
    private final FrozenDataCatalogSource source;
    private final CatalogRepository catalogs;
    private final CatalogContractPolicyPort policy;
    private final CatalogTransactionPort transactions;

    public FrozenDataCatalogBootstrap(
            FrozenDataCatalogSource source,
            CatalogRepository catalogs,
            CatalogContractPolicyPort policy,
            CatalogTransactionPort transactions) {
        this.source = Objects.requireNonNull(source);
        this.catalogs = Objects.requireNonNull(catalogs);
        this.policy = Objects.requireNonNull(policy);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public CatalogView run() {
        FrozenDataCatalogSnapshot snapshot = source.load();
        DataSourceCatalog candidate = snapshot.draft();
        if (!policy.validate(candidate).isEmpty()) {
            throw new IngestionQualityApplicationException("INGESTION_QUALITY_CONTRACT_INVALID");
        }
        try {
            return (CatalogView) transactions.execute(() -> materialize(candidate));
        } catch (RuntimeException race) {
            DataSourceCatalog existing = catalogs.find(candidate.catalogId()).orElse(null);
            if (existing == null) throw race;
            return sameFrozenCatalog(existing, candidate);
        }
    }

    private CatalogView materialize(DataSourceCatalog candidate) {
        DataSourceCatalog existing = catalogs.find(candidate.catalogId()).orElse(null);
        if (existing != null) return sameFrozenCatalog(existing, candidate);
        catalogs.save(candidate, 0);
        return CatalogView.from(candidate,
                catalogs.currentPointer().map(CatalogCurrentPointer::pointerVersion).orElse(0L));
    }

    private CatalogView sameFrozenCatalog(DataSourceCatalog existing, DataSourceCatalog expected) {
        if (!existing.contentDigest().equals(expected.contentDigest())
                || !existing.sources().equals(expected.sources())
                || !existing.dependencies().equals(expected.dependencies())) {
            throw new IngestionQualityApplicationException("INGESTION_QUALITY_CONTRACT_INVALID");
        }
        long pointerVersion = catalogs.currentPointer()
                .map(CatalogCurrentPointer::pointerVersion).orElse(0L);
        return CatalogView.from(existing, pointerVersion);
    }
}
