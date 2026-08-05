package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import java.sql.SQLException;

class CatalogJdbcFailuresTest {
    @Test
    void mapsOnlyAvailabilityAndTransientJdbcFailuresToStableUnavailable() {
        for (var failure : java.util.List.of(
                new TransientDataAccessResourceException("transient"),
                new QueryTimeoutException("timeout"),
                new CannotGetJdbcConnectionException("connection"))) {
            var mapped = (IngestionQualityApplicationException)
                    CatalogJdbcFailures.translate(failure);
            assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", mapped.code());
            assertSame(failure, mapped.getCause());
        }
    }

    @Test
    void doesNotMisclassifyDatabaseInvariantFailureAsAvailability() {
        var invariant = new DataIntegrityViolationException("constraint");
        assertSame(invariant, CatalogJdbcFailures.translate(invariant));
    }

    @Test
    void mapsOnlyTheNamedCatalogReleaseUniquenessConstraintToStableConflict() {
        var releaseConflict = new DataIntegrityViolationException(
                "release id duplicate",
                new SQLException(
                        "duplicate key violates constraint "
                                + "iq_data_source_catalog_catalog_release_id_key",
                        "23505"));

        var mapped = (IngestionQualityApplicationException)
                CatalogJdbcFailures.translate(releaseConflict);
        assertEquals("INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT", mapped.code());
        assertSame(releaseConflict, mapped.getCause());

        var unrelated = new DataIntegrityViolationException(
                "other duplicate",
                new SQLException("duplicate key violates constraint another_key", "23505"));
        assertSame(unrelated, CatalogJdbcFailures.translate(unrelated));
        var wrongState = new DataIntegrityViolationException(
                "constraint check",
                new SQLException(
                        "constraint iq_data_source_catalog_catalog_release_id_key", "23514"));
        assertSame(wrongState, CatalogJdbcFailures.translate(wrongState));
    }
}
