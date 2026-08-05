package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataSourceCatalogTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID CATALOG_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000001");
    private static final UUID RELEASE_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000002");

    @Test
    void draftCanBecomeInvalidThenPublishableAndPublishedWithoutHistoryMutation() {
        DataSourceCatalog draft = draft();
        DataSourceCatalog invalid = draft.validated(
                List.of(new CatalogValidationFailure("DCC_OWNER_MISSING", "sources[0].ownerName")), NOW);
        DataSourceCatalog publishable = invalid.validated(List.of(), NOW.plusSeconds(1));
        DataSourceCatalog published = publishable.publish(
                RELEASE_ID, "sha256:" + "b".repeat(64), NOW.plusSeconds(2));

        assertEquals(CatalogStatus.DRAFT, draft.status());
        assertEquals(CatalogStatus.INVALID, invalid.status());
        assertEquals(CatalogStatus.PUBLISHABLE, publishable.status());
        assertEquals(CatalogStatus.PUBLISHED, published.status());
        assertEquals(1, draft.aggregateVersion());
        assertEquals(4, published.aggregateVersion());
        assertEquals(RELEASE_ID, published.catalogReleaseId());
        assertThrows(IngestionQualityException.class, () -> published.validated(List.of(), NOW.plusSeconds(3)));
        assertThrows(IngestionQualityException.class, () -> published.publish(RELEASE_ID, "sha256:" + "b".repeat(64), NOW.plusSeconds(3)));
    }

    @Test
    void publishRequiresPublishableStateUuidV7AndImmutableEvidenceDigest() {
        assertThrows(IngestionQualityException.class, () -> draft().publish(
                RELEASE_ID, "sha256:" + "b".repeat(64), NOW));
        DataSourceCatalog publishable = draft().validated(List.of(), NOW);
        assertThrows(IngestionQualityException.class, () -> publishable.publish(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "sha256:" + "b".repeat(64), NOW));
        assertThrows(IngestionQualityException.class, () -> publishable.publish(
                RELEASE_ID, "fixture://mutable", NOW));
    }

    @Test
    void aggregateVersionUsesTheCrossLanguageSafeIntegerBoundary() {
        DataSourceCatalog atMaximum = DataSourceCatalog.restore(
                CATALOG_ID, null, "DCC-1.0.0", draft().sources(), draft().dependencies(),
                "sha256:" + "a".repeat(64), null, CatalogStatus.DRAFT,
                DataSourceCatalog.MAX_VERSION, List.of(), NOW, NOW, null);

        assertEquals(DataSourceCatalog.MAX_VERSION, atMaximum.aggregateVersion());
        assertThrows(IngestionQualityException.class,
                () -> atMaximum.validated(List.of(), NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> DataSourceCatalog.restore(
                CATALOG_ID, null, "DCC-1.0.0", draft().sources(), draft().dependencies(),
                "sha256:" + "a".repeat(64), null, CatalogStatus.DRAFT,
                DataSourceCatalog.MAX_VERSION + 1, List.of(), NOW, NOW, null));
    }

    private DataSourceCatalog draft() {
        return DataSourceCatalog.draft(
                CATALOG_ID,
                "DCC-1.0.0",
                List.of(new SourceContract(
                        "SRC-P0-CALENDAR-001", "business-calendar-control", "BC-1.0.0",
                        "QG-1.0.0", "evidence+sha256://" + "a".repeat(64),
                        RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(new DependencyBinding(
                        "SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001",
                        DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF)),
                "sha256:" + "a".repeat(64),
                NOW);
    }
}
