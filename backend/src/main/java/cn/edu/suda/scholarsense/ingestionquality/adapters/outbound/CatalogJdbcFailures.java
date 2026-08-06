package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Exact JDBC availability classification; invariant/integrity failures retain their original type. */
final class CatalogJdbcFailures {
    private static final String RELEASE_ID_CONSTRAINT =
            "iq_data_source_catalog_catalog_release_id_key";

    private CatalogJdbcFailures() {}

    static RuntimeException translate(DataAccessException failure) {
        if (isReleaseIdConflict(failure)) {
            return new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT", failure);
        }
        if (failure instanceof TransientDataAccessException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof QueryTimeoutException
                || failure instanceof CannotGetJdbcConnectionException) {
            return new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", failure);
        }
        return failure;
    }

    private static boolean isReleaseIdConflict(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(RELEASE_ID_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}
