package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenDataCatalogBootstrapTest {
    private static final UUID ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000051");
    private static final Instant SERVER_NOW = Instant.parse("2026-08-05T02:03:04Z");

    @Test
    void materializesOnlyTheDeterministicFrozenSnapshotAndIsReplaySafe() {
        MemoryCatalogs catalogs = new MemoryCatalogs();
        DataSourceCatalog projection = CatalogFixtures.draft(ID, Instant.EPOCH);
        FrozenDataCatalogSnapshot snapshot = new FrozenDataCatalogSnapshot(
                ID, projection.contractVersion(), projection.sources(), projection.dependencies(),
                projection.contentDigest(), SERVER_NOW,
                CatalogFixtures.evidence(projection, SERVER_NOW));
        FrozenDataCatalogBootstrap bootstrap = new FrozenDataCatalogBootstrap(
                () -> snapshot, catalogs, ignored -> List.of(), work -> work.get());

        CatalogView first = bootstrap.run();
        CatalogView replay = bootstrap.run();

        assertEquals(first, replay);
        assertEquals(SERVER_NOW, first.updatedAt());
        assertEquals(1, catalogs.insertCount);
        assertEquals(17, catalogs.find(ID).orElseThrow().sources().size());
    }

    private static final class MemoryCatalogs implements CatalogRepository {
        private final Map<UUID, DataSourceCatalog> values = new HashMap<>();
        private int insertCount;

        @Override public Optional<DataSourceCatalog> find(UUID catalogId) {
            return Optional.ofNullable(values.get(catalogId));
        }
        @Override public List<DataSourceCatalog> list(int offset, int limit) { return List.of(); }
        @Override public Optional<CatalogCurrentPointer> currentPointer() { return Optional.empty(); }
        @Override public void save(DataSourceCatalog catalog, long expectedVersion) {
            if (expectedVersion != 0 || values.putIfAbsent(catalog.catalogId(), catalog) != null) {
                throw new CatalogVersionConflictException(1);
            }
            insertCount++;
        }
        @Override public void publish(
                DataSourceCatalog catalog, long expectedVersion, long expectedCurrentVersion,
                CatalogEvidenceSet evidence) {
            throw new UnsupportedOperationException();
        }
    }
}
