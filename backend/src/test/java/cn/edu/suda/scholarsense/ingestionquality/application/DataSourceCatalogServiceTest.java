package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataSourceCatalogServiceTest {

    private static final UUID CATALOG_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000011");
    private static final UUID RELEASE_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000012");
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    @Test
    void validatePersistsInvalidAttemptAndNeverChangesCurrentPointer() {
        MemoryStore store = new MemoryStore();
        store.catalogs.put(CATALOG_ID, CatalogFixtures.draft(CATALOG_ID, NOW));
        DataSourceCatalogService service = service(store, catalog -> List.of(
                new CatalogContractViolation("DCC_OWNER_MISSING", "sources[0].ownerName")));

        CatalogView result = service.validate(new ValidateCatalogCommand(
                CATALOG_ID, 1, "actor-r6", "trace-001", NOW));

        assertEquals(CatalogStatus.INVALID, result.status());
        assertEquals(2, result.aggregateVersion());
        assertEquals(Optional.empty(), store.current());
    }

    @Test
    void publishUsesOptimisticLockAndReplaysSameIdempotencyIdentityOnce() {
        MemoryStore store = new MemoryStore();
        DataSourceCatalog publishable = CatalogFixtures.draft(CATALOG_ID, NOW).validated(List.of(), NOW);
        store.catalogs.put(CATALOG_ID, publishable);
        DataSourceCatalogService service = service(store, catalog -> List.of());
        PublishCatalogCommand command = new PublishCatalogCommand(
                CATALOG_ID, 2, RELEASE_ID, "idem-key-001", "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64), "actor-r6", "trace-002", NOW.plusSeconds(1));

        CatalogView first = service.publish(command);
        CatalogView replay = service.publish(command);

        assertEquals(first, replay);
        assertEquals(CatalogStatus.PUBLISHED, first.status());
        assertEquals(CATALOG_ID, store.current().orElseThrow().catalogId());
        assertEquals(1, store.publishWrites);

        PublishCatalogCommand mismatch = new PublishCatalogCommand(
                CATALOG_ID, 3, RELEASE_ID, "idem-key-001", "sha256:" + "e".repeat(64),
                "sha256:" + "d".repeat(64), "actor-r6", "trace-002", NOW.plusSeconds(2));
        IngestionQualityApplicationException error = assertThrows(
                IngestionQualityApplicationException.class, () -> service.publish(mismatch));
        assertEquals("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", error.code());
    }

    @Test
    void staleExpectedVersionFailsBeforeMutation() {
        MemoryStore store = new MemoryStore();
        store.catalogs.put(CATALOG_ID, CatalogFixtures.draft(CATALOG_ID, NOW).validated(List.of(), NOW));
        DataSourceCatalogService service = service(store, catalog -> List.of());
        PublishCatalogCommand stale = new PublishCatalogCommand(
                CATALOG_ID, 1, RELEASE_ID, "idem-key-002", "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64), "actor-r6", "trace-003", NOW);
        IngestionQualityApplicationException error = assertThrows(
                IngestionQualityApplicationException.class, () -> service.publish(stale));
        assertEquals("INGESTION_QUALITY_VERSION_CONFLICT", error.code());
        assertEquals(CatalogStatus.PUBLISHABLE, store.catalogs.get(CATALOG_ID).status());
    }

    private DataSourceCatalogService service(MemoryStore store, CatalogContractPolicyPort policy) {
        return new DataSourceCatalogService(
                store, store, policy,
                (actor, action, catalog, trace) -> CatalogAuthorizationDecision.ALLOW,
                work -> work.get(), event -> store.audits.add(event), trace -> {});
    }

    private static final class MemoryStore implements CatalogRepository, CatalogIdempotencyPort {
        private final Map<UUID, DataSourceCatalog> catalogs = new HashMap<>();
        private final Map<String, CatalogIdempotencyResult> idempotency = new HashMap<>();
        private final List<CatalogAuditEvent> audits = new ArrayList<>();
        private UUID current;
        private int publishWrites;

        @Override public Optional<DataSourceCatalog> find(UUID id) { return Optional.ofNullable(catalogs.get(id)); }
        @Override public List<DataSourceCatalog> list(int offset, int limit) { return catalogs.values().stream().skip(offset).limit(limit).toList(); }
        @Override public Optional<DataSourceCatalog> current() { return Optional.ofNullable(current).flatMap(this::find); }
        @Override public void save(DataSourceCatalog catalog, long expectedVersion) {
            DataSourceCatalog previous = catalogs.get(catalog.catalogId());
            long actual = previous == null ? 0 : previous.aggregateVersion();
            if (actual != expectedVersion) throw new CatalogVersionConflictException(actual);
            catalogs.put(catalog.catalogId(), catalog);
            if (catalog.status() == CatalogStatus.PUBLISHED) { current = catalog.catalogId(); publishWrites++; }
        }
        @Override public Optional<CatalogIdempotencyResult> find(String key) { return Optional.ofNullable(idempotency.get(key)); }
        @Override public void save(CatalogIdempotencyResult result) { idempotency.put(result.idempotencyKey(), result); }
    }
}
